package com.example.celltracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

@SuppressLint("MissingPermission")
class DeviceLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val adapter: BluetoothAdapter? by lazy { getSystemService(BluetoothManager::class.java)?.adapter }
    private lateinit var cellular: CellularRepository
    private lateinit var location: LocationRepository
    private lateinit var repository: CallSetupRepository
    private lateinit var deviceId: String
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var writer: BufferedWriter? = null
    private var readerJob: Job? = null
    private var linkJob: Job? = null
    private var heartbeatJob: Job? = null
    private var locationJob: Job? = null
    private var testJob: Job? = null
    private var reconnectAddress: String? = null
    private val intentionallyDisconnected = AtomicBoolean(false)
    private val messages = Channel<DeviceLinkMessage>(Channel.UNLIMITED)
    private var sessionDir: File? = null
    private var ownedRecordingPath: String? = null
    private var sessionConfig = CallSetupConfig()
    private var attemptId = ""
    private var endpointRole = "--"
    private var localCallState = "IDLE"
    private var hadActiveCall = false
    private var ringingSeen = false
    private var dialElapsed = 0L
    private var activeSubscriptionId = -1
    private var disconnectedBeforeConnected = false
    private var bothConnectedConfirmed = false
    private val remoteSnapshots = ConcurrentHashMap<String, MutableList<CallNetworkSnapshot>>()
    private val localLiveSnapshots = ConcurrentHashMap<String, MutableList<CallNetworkSnapshot>>()
    private var telephonyManager: TelephonyManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION") private var phoneListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this); location = LocationRepository(this); repository = CallSetupRepository(this)
        deviceId = getSharedPreferences("device_link", MODE_PRIVATE).let { p ->
            p.getString("device_id", null) ?: UUID.randomUUID().toString().also { p.edit().putString("device_id", it).apply() }
        }
        createChannel()
        registerDiscoveryReceiver()
        refreshDevices()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            val prefs=getSharedPreferences("device_link",MODE_PRIVATE)
            when(prefs.getString("last_mode","")) {
                "agent" -> startAgent()
                "controller" -> prefs.getString("last_address",null)?.let(::connect) ?: stopSelf()
                else -> stopSelf()
            }
            return START_STICKY
        }
        when (intent?.action) {
            ACTION_DISCOVER -> discover()
            ACTION_PAIR -> pair(intent.getStringExtra(EXTRA_ADDRESS).orEmpty())
            ACTION_AGENT -> startAgent()
            ACTION_CONTROLLER -> switchToController()
            ACTION_CONNECT -> connect(intent.getStringExtra(EXTRA_ADDRESS).orEmpty())
            ACTION_DISCONNECT -> disconnect(true)
            ACTION_REFRESH -> refreshDevices()
            ACTION_SAVE_PROFILE -> saveProfile(intent.getStringExtra(EXTRA_PHONE).orEmpty(), intent.getIntExtra(EXTRA_SIM_SLOT, 0))
            ACTION_START_TEST -> startControllerTest(readConfig(intent))
            ACTION_STOP_TEST -> stopTest("Stopped by user", true)
        }
        return START_STICKY
    }

    private fun ensureForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, n, type)
        }
        else startForeground(NOTIFICATION_ID, n)
        if (locationJob?.isActive != true) locationJob = scope.launch { runCatching { location.locations().collect {} } }
        ensureWakeLock()
    }

    private fun ensureWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CellTracker:DeviceLink")
            .apply { setReferenceCounted(false); acquire(12L * 60L * 60L * 1000L) }
    }

    private fun startAgent() {
        if (!canConnectBluetooth() || !canScanBluetooth()) { setLinkStatus(DeviceLinkStatus.PERMISSION_REQUIRED, "Bluetooth permissions are required"); return }
        if (adapter?.isEnabled != true) { setLinkStatus(DeviceLinkStatus.BLUETOOTH_OFF, "Bluetooth is off. Turn it on and try again."); return }
        stopCurrentLinkTransport(clearPeer = true)
        ensureForeground("Agent waiting for Controller")
        getSharedPreferences("device_link",MODE_PRIVATE).edit().putString("last_mode","agent").remove("last_address").apply()
        intentionallyDisconnected.set(false)
        refreshDevices()
        linkJob = scope.launch {
            DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(
                role=DeviceLinkRole.AGENT,
                status=DeviceLinkStatus.WAITING,
                statusMessage="Waiting for Controller",
                bluetoothEnabled=true,
                discoveryActive=false,
                discoverable=isAdapterDiscoverable()
            )
            while (isActive && !intentionallyDisconnected.get()) {
                try {
                    runCatching { serverSocket?.close() }
                    serverSocket = adapter!!.listenUsingRfcommWithServiceRecord(DeviceLinkProtocol.SERVICE_NAME, DeviceLinkProtocol.SERVICE_UUID)
                    val incoming = serverSocket!!.accept()
                    DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.CONNECTING,statusMessage="Controller connecting")
                    attach(incoming)
                    readerJob?.join()
                    if (!intentionallyDisconnected.get()) {
                        DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.WAITING,statusMessage="Disconnected; waiting for Controller",peer=null,peerProfile=null)
                    }
                } catch (e: Exception) {
                    if (!intentionallyDisconnected.get() && currentCoroutineContext().isActive) {
                        DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.WAITING,statusMessage="Waiting for Controller",peer=null,peerProfile=null)
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun switchToController() {
        stopCurrentLinkTransport(clearPeer = true)
        intentionallyDisconnected.set(false)
        getSharedPreferences("device_link",MODE_PRIVATE).edit().putString("last_mode","controller").remove("last_address").apply()
        refreshDevices()
        releaseForegroundResources()
        DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(
            role=DeviceLinkRole.CONTROLLER,
            status=if (adapter?.isEnabled == true) DeviceLinkStatus.IDLE else DeviceLinkStatus.BLUETOOTH_OFF,
            statusMessage=if (adapter?.isEnabled == true) "Ready to scan or connect" else "Bluetooth is off",
            peer=null,
            peerProfile=null,
            discoveryActive=false
        )
    }

    private fun stopCurrentLinkTransport(clearPeer: Boolean) {
        intentionallyDisconnected.set(true)
        runCatching { adapter?.cancelDiscovery() }
        readerJob?.cancel(); readerJob=null
        heartbeatJob?.cancel(); heartbeatJob=null
        linkJob?.cancel(); linkJob=null
        runCatching { socket?.close() }; socket=null
        runCatching { serverSocket?.close() }; serverSocket=null
        writer=null
        if (clearPeer) DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(peer=null,peerProfile=null,latencyMs=null,clockOffsetMs=null,lastHeartbeatMs=0L,discoveryActive=false)
    }

    private fun connect(address: String) {
        if (address.isBlank()) { setLinkStatus(DeviceLinkStatus.CONNECTION_FAILED, "Select a Bluetooth device"); return }
        if (!canConnectBluetooth()) { setLinkStatus(DeviceLinkStatus.PERMISSION_REQUIRED, "Bluetooth permission is required"); return }
        if (adapter?.isEnabled != true) { setLinkStatus(DeviceLinkStatus.BLUETOOTH_OFF, "Bluetooth is off. Turn it on and try again."); return }
        runCatching { adapter?.cancelDiscovery() }
        ensureForeground("Connecting Device Link")
        getSharedPreferences("device_link",MODE_PRIVATE).edit().putString("last_mode","controller").putString("last_address",address).apply()
        reconnectAddress = address; intentionallyDisconnected.set(false)
        linkJob?.cancel(); linkJob = scope.launch { connectLoop(address) }
    }

    private suspend fun connectLoop(address: String) {
        var first = true
        while (currentCoroutineContext().isActive && !intentionallyDisconnected.get()) {
            try {
                DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(role=DeviceLinkRole.CONTROLLER,status=if(first) DeviceLinkStatus.CONNECTING else DeviceLinkStatus.RECONNECTING,statusMessage=if(first) "Connecting" else "Reconnecting",bluetoothEnabled=true)
                adapter?.cancelDiscovery()
                val remote = adapter!!.getRemoteDevice(address)
                val candidate = remote.createRfcommSocketToServiceRecord(DeviceLinkProtocol.SERVICE_UUID)
                candidate.connect(); attach(candidate); readerJob?.join()
                if (testJob?.isActive == true) failForLinkLoss()
            } catch (e: Exception) {
                if (testJob?.isActive == true) failForLinkLoss()
                if (intentionallyDisconnected.get()) break
                val detail=e.message?.take(80).orEmpty().ifBlank{"RFCOMM connection failed"}
                DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.CONNECTION_FAILED,statusMessage="Connection failed: $detail",peer=null,peerProfile=null)
                if (!DeviceLinkStore.link.value.reconnectEnabled) break
                first = false; delay(3000)
            }
        }
    }

    private suspend fun attach(connected: BluetoothSocket) {
        socket?.close(); socket = connected
        val remote = connected.remoteDevice
        writer = BufferedWriter(OutputStreamWriter(connected.outputStream, Charsets.UTF_8))
        DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.CONNECTED,statusMessage="Connected",peer=BluetoothPeer(remote.name ?: "Unknown",address=remote.address,bonded=remote.bondState==BluetoothDevice.BOND_BONDED),bluetoothEnabled=true,permissionGranted=true,discoveryActive=false)
        send("HELLO", payload=profilePayload(buildProfile()))
        readerJob?.cancel(); readerJob = scope.launch {
            try {
                BufferedReader(InputStreamReader(connected.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.forEach { if (it.isNotBlank()) handleMessage(DeviceLinkProtocol.decode(it)) }
                }
            } catch (_: Exception) { } finally {
                if (socket === connected) {
                    socket = null; writer = null
                    DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.DISCONNECTED,statusMessage="Link disconnected",peerProfile=null)
                }
            }
        }
        heartbeatJob?.cancel(); heartbeatJob = scope.launch {
            var heartbeatCount = 0
            while (isActive && socket === connected) {
                ensureWakeLock()
                val now = System.currentTimeMillis()
                send("HEARTBEAT", payload=mapOf("echo" to "0", "sent_wall" to now.toString(), "sent_elapsed" to SystemClock.elapsedRealtime().toString()))
                if (++heartbeatCount % 5 == 0) send("DEVICE_STATUS", payload=profilePayload(buildProfile()))
                delay(2000)
            }
        }
    }

    private fun handleMessage(m: DeviceLinkMessage) {
        if (m.protocolVersion != DeviceLinkProtocol.VERSION) { setLinkError("Unsupported protocol v${m.protocolVersion}"); return }
        when (m.messageType) {
            "HELLO", "DEVICE_STATUS" -> {
                val profile = profileFrom(m.payload)
                DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(peerProfile=profile,peer=DeviceLinkStore.link.value.peer?.copy(deviceId=profile.deviceId))
            }
            "HEARTBEAT" -> {
                if (m.payload["echo"] != "1") send("HEARTBEAT", payload=m.payload + mapOf("echo" to "1", "remote_wall" to System.currentTimeMillis().toString()))
                else {
                    val now = System.currentTimeMillis(); val sent = m.payload["sent_wall"]?.toLongOrNull() ?: now
                    val rtt = (now-sent).coerceAtLeast(0).toDouble(); val remote = m.payload["remote_wall"]?.toLongOrNull() ?: now
                    DeviceLinkStore.link.value = DeviceLinkStore.link.value.copy(latencyMs=rtt,clockOffsetMs=remote-(sent+rtt/2.0),lastHeartbeatMs=now)
                }
            }
            "PREPARE_TEST" -> if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) prepareAgentTest(m)
            "START_TEST" -> if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(statusMessage="Test started by Controller")
            "PREPARE_CALL" -> if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) prepareAgentCall(m)
            "DIAL" -> if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) agentDial(m)
            "CALL_ENDED" -> if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) {
                if(!endCall())DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(statusMessage="Please hang up manually")
                captureAndSend("CALL_END")
            }
            "ATTEMPT_RESULT" -> if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) agentAttemptResult(m)
            "STOP_TEST", "TEST_FINISHED" -> {
                val reason = m.payload["status"] ?: "Stopped by peer"
                if (DeviceLinkStore.link.value.role == DeviceLinkRole.AGENT) finishAgentTest(reason)
                else if (m.messageType == "STOP_TEST") testJob?.cancel(CancellationException(reason))
            }
            "NETWORK_SNAPSHOT" -> {
                val snapshot = snapshotFrom(m.payload)
                remoteSnapshots.getOrPut(m.attemptId) { Collections.synchronizedList(mutableListOf()) }.add(snapshot)
                val s=DeviceLinkStore.callTest.value; DeviceLinkStore.callTest.value=s.copy(peerSnapshot=snapshot)
            }
        }
        messages.trySend(m)
    }

    private fun send(type: String, session: String = DeviceLinkStore.callTest.value.sessionId, attempt: String = attemptId, payload: Map<String,String> = emptyMap()): Boolean = synchronized(this) {
        try { writer?.apply { write(DeviceLinkProtocol.encode(DeviceLinkMessage(messageType=type,deviceId=deviceId,sessionId=session,attemptId=attempt,payload=payload))); flush() } != null }
        catch (_: Exception) { false }
    }

    private fun prepareAgentTest(m: DeviceLinkMessage) {
        sessionConfig = configFrom(m.payload); repository.saveConfig(sessionConfig)
        val state=CallSetupTestState(isRunning=true,config=sessionConfig,sessionId=m.sessionId,startedAt=System.currentTimeMillis(),statusMessage="Prepared by Controller",automationCapability=automationCapability())
        DeviceLinkStore.callTest.value=state
        if(sessionConfig.autoRecord) startOwnedRecording(sessionConfig.taskName, subscriptionForSlot(sessionConfig.bCallSimSlot))
        scope.launch { send("DEVICE_STATUS",m.sessionId,payload=profilePayload(buildProfile(sessionConfig.bCallSimSlot))+mapOf("ready" to "true")) }
    }

    private fun prepareAgentCall(m: DeviceLinkMessage) {
        attemptId=m.attemptId; endpointRole=m.payload["role"] ?: "--"; activeSubscriptionId=subscriptionForSlot(sessionConfig.bCallSimSlot)
        ringingSeen=false; hadActiveCall=false; disconnectedBeforeConnected=false; bothConnectedConfirmed=false; dialElapsed=0; registerCallMonitor(activeSubscriptionId)
        DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(currentAttempt=m.payload["number"]?.toIntOrNull()?:0,currentDirection=m.payload["direction"]?:"--",localRole=endpointRole,peerRole=if(endpointRole=="MO")"MT" else "MO",localCallState="WAITING",statusMessage="Ready as $endpointRole")
        val ready = !runCatching { getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)
        scope.launch { captureAndSend("BEFORE_DIAL"); send("DEVICE_STATUS",m.sessionId,m.attemptId,mapOf("ready" to ready.toString(),"role" to endpointRole,"reason" to if(ready)"" else "Agent already in a call")) }
    }

    private fun agentDial(m: DeviceLinkMessage) {
        dialElapsed=SystemClock.elapsedRealtime(); localCallState="DIALING"
        DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(localCallState="DIALING",statusMessage="Dialing")
        scope.launch { captureAndSend("DIAL") }
        if(!placeCall(m.payload["number"].orEmpty(),sessionConfig.bCallSimSlot)) send("CALL_FAILED",m.sessionId,m.attemptId,mapOf("reason" to "MO_DIAL_FAILED"))
        else send("DIAL",m.sessionId,m.attemptId,mapOf("started" to "true"))
    }

    private fun startControllerTest(config: CallSetupConfig) {
        if(DeviceLinkStore.link.value.role!=DeviceLinkRole.CONTROLLER || DeviceLinkStore.link.value.status!=DeviceLinkStatus.CONNECTED){ setLinkError("Connect to Agent before starting Call Setup"); return }
        if(testJob?.isActive==true)return
        val local=buildProfile(config.aCallSimSlot); val peer=DeviceLinkStore.link.value.peerProfile
        if(local.phoneNumber.isBlank() || peer?.phoneNumber.isNullOrBlank()){ setLinkError("Configure phone numbers on both DUTs first"); return }
        if(runCatching { getSystemService(TelecomManager::class.java).isInCall }.getOrDefault(false)){setLinkError("End the current call before starting the test");return}
        repository.saveConfig(config); ensureForeground("Call Setup test running")
        testJob=scope.launch { runControllerTest(config,local,peer!!) }
    }

    private suspend fun runControllerTest(config: CallSetupConfig, a: DeviceProfile, b: DeviceProfile) {
        sessionConfig=config; val session=UUID.randomUUID().toString().take(8); val started=System.currentTimeMillis()
        sessionDir=repository.createSession(session,config,a,b,started); ownedRecordingPath=null
        DeviceLinkStore.callTest.value=CallSetupTestState(true,config,session,startedAt=started,resultPath=sessionDir?.absolutePath,statusMessage="Preparing both DUTs",automationCapability=automationCapability())
        if(config.autoRecord) startOwnedRecording(config.taskName,subscriptionForSlot(config.aCallSimSlot))
        send("PREPARE_TEST",session,payload=configPayload(config))
        delay(600)
        send("START_TEST",session,payload=mapOf("status" to "started"))
        val directions=when(config.direction){
            CallDirection.A_TO_B -> List(config.callCount){"A_TO_B"}
            CallDirection.B_TO_A -> List(config.callCount){"B_TO_A"}
            CallDirection.BIDIRECTIONAL_BLOCK -> List(config.callCount){"A_TO_B"}+List(config.callCount){"B_TO_A"}
            CallDirection.BIDIRECTIONAL_ALTERNATE -> List(config.callCount){if(it%2==0)"A_TO_B" else "B_TO_A"}
        }
        var consecutive=0; var status="Completed"
        try {
            directions.forEachIndexed { index,direction ->
                currentCoroutineContext().ensureActive()
                val result=runAttempt(index+1,directions.size,direction,a,b,config,session)
                repository.appendAttempt(sessionDir!!,result)
                val eventType=when { result.result=="SUCCESS" -> "CALL_SETUP_SUCCESS"; result.result=="SETUP_TIMEOUT" -> "CALL_SETUP_TIMEOUT"; result.bluetoothLost -> "DEVICE_LINK_LOST"; else -> "CALL_SETUP_FAILURE" }
                recordAutoEvent(eventType,"${result.direction} #${result.attemptNumber}: ${result.result}")
                repository.appendEvent(sessionDir!!,System.currentTimeMillis(),eventType,result.attemptId,result.direction,result.failureDetail.ifBlank{result.result})
                val high = result.result=="SUCCESS" && (result.setupLatencyMs?:0)>=config.highLatencyThresholdMs
                if(high){
                    recordAutoEvent("HIGH_CALL_SETUP_LATENCY","${result.setupLatencyMs} ms"); repository.appendEvent(sessionDir!!,System.currentTimeMillis(),"HIGH_CALL_SETUP_LATENCY",result.attemptId,result.direction,"${result.setupLatencyMs} ms")
                }
                consecutive=if(result.result=="SUCCESS")0 else consecutive+1
                val s=DeviceLinkStore.callTest.value; DeviceLinkStore.callTest.value=s.copy(attempts=s.attempts+result,consecutiveFailures=consecutive,currentSetupLatencyMs=result.setupLatencyMs,statusMessage=result.result)
                send("ATTEMPT_RESULT",session,result.attemptId,mapOf("result" to result.result,"event" to eventType,"detail" to result.failureDetail,"high" to high.toString(),"latency" to (result.setupLatencyMs?.toString()?:"")))
                if(index<directions.lastIndex) delay(config.interCallIntervalMs)
            }
        } catch(e:CancellationException){ status=if(DeviceLinkStore.link.value.status!=DeviceLinkStatus.CONNECTED)"Bluetooth link lost" else "Stopped" }
        catch(e:Exception){status="Error: ${e.message}"}
        finally {
            val end=System.currentTimeMillis(); repository.finish(sessionDir!!,config,a,b,started,end,status)
            send("TEST_FINISHED",session,payload=mapOf("status" to status)); stopOwnedRecording()
            val s=DeviceLinkStore.callTest.value; DeviceLinkStore.callTest.value=s.copy(isRunning=false,endedAt=end,statusMessage=status)
            unregisterCallMonitor(); testJob=null; updateNotification("Device Link connected")
        }
    }

    private suspend fun runAttempt(number:Int,total:Int,direction:String,a:DeviceProfile,b:DeviceProfile,c:CallSetupConfig,session:String):CallAttemptResult {
        attemptId="C${number}_${System.currentTimeMillis()}"; val start=System.currentTimeMillis(); val snapshots=mutableListOf<CallNetworkSnapshot>()
        val localRole=if(direction=="A_TO_B")"MO" else "MT"; val peerRole=if(localRole=="MO")"MT" else "MO"
        activeSubscriptionId=subscriptionForSlot(c.aCallSimSlot); endpointRole=localRole; ringingSeen=false; hadActiveCall=false; disconnectedBeforeConnected=false; bothConnectedConfirmed=false; localCallState=if(localRole=="MO")"DIALING" else "WAITING"; dialElapsed=0
        remoteSnapshots[attemptId] = Collections.synchronizedList(mutableListOf())
        localLiveSnapshots[attemptId] = Collections.synchronizedList(mutableListOf())
        registerCallMonitor(activeSubscriptionId)
        var dialAt:Long?=null; var ringAt:Long?=null; var moAt:Long?=null; var mtAt:Long?=null; var failure=""; var linkLost=false
        val before=captureSnapshot("A","BEFORE_DIAL",activeSubscriptionId); snapshots+=before
        DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(currentAttempt=number,currentDirection=direction,localRole=localRole,peerRole=peerRole,localCallState=localCallState,peerCallState="PREPARING",statusMessage="Attempt $number / $total",localSnapshot=before)
        send("PREPARE_CALL",session,attemptId,mapOf("role" to peerRole,"direction" to direction,"number" to number.toString()))
        val ready=awaitMessage(5000){it.attemptId==attemptId && it.messageType=="DEVICE_STATUS" && it.payload.containsKey("ready")}
        if(ready?.payload?.get("ready")!="true") failure=if(DeviceLinkStore.link.value.status!=DeviceLinkStatus.CONNECTED)"Device Link lost" else ready?.payload?.get("reason").orEmpty().ifBlank{"Agent not ready"}
        if(failure.isBlank()) {
            dialAt=System.currentTimeMillis(); dialElapsed=SystemClock.elapsedRealtime()
            if(direction=="A_TO_B") {
                snapshots+=captureSnapshot("A","DIAL",activeSubscriptionId)
                if(!placeCall(b.phoneNumber,c.aCallSimSlot)) failure="Unable to place MO call"
            } else send("DIAL",session,attemptId,mapOf("number" to a.phoneNumber))
        }
        val deadline=SystemClock.elapsedRealtime()+c.setupTimeoutMs
        var remoteConnected=false; var remoteFailure:String?=null
        while(failure.isBlank() && SystemClock.elapsedRealtime()<deadline) {
            if(DeviceLinkStore.link.value.status!=DeviceLinkStatus.CONNECTED){linkLost=true;failure="Bluetooth control link lost";break}
            val msg=withTimeoutOrNull(200){messages.receive()}
            if(msg?.attemptId==attemptId) when(msg.messageType){
                "RINGING"->{val offset=DeviceLinkStore.link.value.clockOffsetMs?:0.0;ringAt=(msg.timestamp-offset).toLong(); DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(peerCallState="RINGING")}
                "CONNECTED"->{remoteConnected=true; DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(peerCallState="CONNECTED")}
                "CALL_FAILED"->{remoteFailure=msg.payload["reason"]?:"UNKNOWN_FAILURE";failure=remoteFailure!!}
                "CALL_ENDED"->if(!remoteConnected){failure="Remote call ended before both endpoints connected"}
            }
            if(ringingSeen&&ringAt==null)ringAt=System.currentTimeMillis()
            if(disconnectedBeforeConnected){failure="Local MT call ended before connected"}
            val localConnected=localCallState=="OFFHOOK"
            val mtConfirmed=if(direction=="A_TO_B")remoteConnected else ringingSeen&&localConnected
            val moConfirmed=if(direction=="A_TO_B")localConnected else remoteConnected
            if(mtConfirmed&&moConfirmed){
                bothConnectedConfirmed=true
                val now=System.currentTimeMillis()
                if(direction=="A_TO_B"){moAt=now;mtAt=ringAt?.let{now}?:now}else{mtAt=now;moAt=now}
                break
            }
        }
        val connected=moAt!=null&&mtAt!=null
        val latency=if(connected) SystemClock.elapsedRealtime()-dialElapsed else null
        if(connected){
            val snap=captureSnapshot("A","CONNECTED",activeSubscriptionId);snapshots+=snap;DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(localSnapshot=snap,currentSetupLatencyMs=latency,statusMessage="Connected · holding")
            delay(c.holdTimeMs)
        }
        val endedAutomatically=endCall(); send("CALL_ENDED",session,attemptId)
        if(!endedAutomatically&&localCallState!="IDLE") {
            DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(statusMessage="Please hang up manually")
            withTimeoutOrNull(10_000L){while(localCallState!="IDLE")delay(200)}
        }
        delay(1200)
        snapshots+=captureSnapshot("A",if(connected)"CALL_END" else "FAILURE",activeSubscriptionId)
        snapshots += localLiveSnapshots.remove(attemptId).orEmpty().toList()
        snapshots += remoteSnapshots.remove(attemptId).orEmpty().toList()
        val result=when { connected->CallResultCodes.SUCCESS; linkLost->CallResultCodes.BLUETOOTH_LINK_LOST; remoteFailure!=null->remoteFailure!!; failure.contains("before",true)->CallResultCodes.DISCONNECTED_BEFORE_CONNECTED; failure.contains("place",true)->CallResultCodes.MO_DIAL_FAILED; ringAt==null && direction=="A_TO_B"->CallResultCodes.MT_NO_INCOMING_CALL; !ringingSeen && direction=="B_TO_A"->CallResultCodes.MT_NO_INCOMING_CALL; SystemClock.elapsedRealtime()>=deadline->CallResultCodes.SETUP_TIMEOUT; localRole=="MO"&&localCallState!="OFFHOOK"->CallResultCodes.MO_NOT_CONNECTED; else->CallResultCodes.MT_NOT_CONNECTED }
        val end=System.currentTimeMillis()
        return CallAttemptResult(number,attemptId,direction,start,end,dialAt,ringAt,moAt,mtAt,end,latency,result,"MEDIUM_PUBLIC_API",failure,linkLost,snapshots)
    }

    private suspend fun awaitMessage(timeout:Long,predicate:(DeviceLinkMessage)->Boolean):DeviceLinkMessage?=withTimeoutOrNull(timeout){while(true){val m=messages.receive();if(predicate(m))return@withTimeoutOrNull m};null}

    private fun onCallState(state:Int) {
        val name=when(state){TelephonyManager.CALL_STATE_RINGING->"RINGING";TelephonyManager.CALL_STATE_OFFHOOK->"OFFHOOK";else->"IDLE"}
        val previous=localCallState; localCallState=name
        val s=DeviceLinkStore.callTest.value; DeviceLinkStore.callTest.value=s.copy(localCallState=name,statusMessage="$endpointRole · $name")
        when(state){
            TelephonyManager.CALL_STATE_RINGING->{
                ringingSeen=true;scope.launch{captureAndSend("MT_RINGING")};send("RINGING",payload=mapOf("elapsed" to SystemClock.elapsedRealtime().toString()))
                if(endpointRole=="MT"&&sessionConfig.automationMode==AutomationMode.AUTO_WHEN_AVAILABLE&&!answerCall())DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(statusMessage="Ringing · please answer manually")
            }
            TelephonyManager.CALL_STATE_OFFHOOK->{
                hadActiveCall=true
                val setup=if(dialElapsed>0)SystemClock.elapsedRealtime()-dialElapsed else 0
                scope.launch{captureAndSend("CONNECTED")}
                if(endpointRole!="MT" || ringingSeen)send("CONNECTED",payload=mapOf("role" to endpointRole,"setup_ms" to setup.toString(),"mt_ringing_seen" to ringingSeen.toString()))
            }
            TelephonyManager.CALL_STATE_IDLE->if(hadActiveCall||previous=="RINGING"||previous=="OFFHOOK"){
                if (!bothConnectedConfirmed && (previous == "RINGING" || previous == "OFFHOOK")) disconnectedBeforeConnected = true
                scope.launch{captureAndSend("CALL_END")};send("CALL_ENDED",payload=mapOf("previous" to previous));hadActiveCall=false
            }
        }
    }

    private fun registerCallMonitor(subscriptionId:Int) {
        unregisterCallMonitor(); val base=getSystemService(TelephonyManager::class.java); val tm=if(subscriptionId>=0)base.createForSubscriptionId(subscriptionId)else base;telephonyManager=tm
        if(Build.VERSION.SDK_INT>=31){val cb=object:TelephonyCallback(),TelephonyCallback.CallStateListener{override fun onCallStateChanged(state:Int)=onCallState(state)};telephonyCallback=cb;runCatching{tm.registerTelephonyCallback(mainExecutor,cb)}}
        else {@Suppress("DEPRECATION") val l=object:PhoneStateListener(){override fun onCallStateChanged(state:Int,phoneNumber:String?)=onCallState(state)};phoneListener=l;@Suppress("DEPRECATION") runCatching{tm.listen(l,PhoneStateListener.LISTEN_CALL_STATE)}}
    }

    private fun unregisterCallMonitor(){telephonyCallback?.let{cb->if(Build.VERSION.SDK_INT>=31)runCatching{telephonyManager?.unregisterTelephonyCallback(cb)}};@Suppress("DEPRECATION") phoneListener?.let{runCatching{telephonyManager?.listen(it,PhoneStateListener.LISTEN_NONE)}};telephonyCallback=null;phoneListener=null}

    private fun placeCall(number:String,simSlot:Int):Boolean {
        if(number.isBlank()||ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED)return false
        return runCatching {
            val telecom=getSystemService(TelecomManager::class.java);val extras=Bundle();val accounts=telecom.callCapablePhoneAccounts
            val subId=subscriptionForSlot(simSlot)
            val account=accounts.firstOrNull { it.id==subId.toString() || it.id.endsWith(":$subId") } ?: accounts.getOrNull(simSlot.coerceAtLeast(0)) ?: accounts.firstOrNull()
            account?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,it) }
            telecom.placeCall(Uri.fromParts("tel",number,null),extras);true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION") private fun answerCall():Boolean=if(ContextCompat.checkSelfPermission(this,Manifest.permission.ANSWER_PHONE_CALLS)==PackageManager.PERMISSION_GRANTED)runCatching{getSystemService(TelecomManager::class.java).acceptRingingCall();true}.getOrDefault(false)else false
    @Suppress("DEPRECATION") private fun endCall():Boolean=if(ContextCompat.checkSelfPermission(this,Manifest.permission.ANSWER_PHONE_CALLS)==PackageManager.PERMISSION_GRANTED)runCatching{getSystemService(TelecomManager::class.java).endCall()}.getOrDefault(false)else false

    private suspend fun captureSnapshot(endpoint:String,moment:String,subscriptionId:Int):CallNetworkSnapshot {
        val sim=runCatching{cellular.readAllSims().firstOrNull{it.subscriptionId==subscriptionId}?:cellular.readAllSims().firstOrNull()}.getOrNull();val c=sim?.servingCell?:CellData();val l=LocationStore.latest.value
        return CallNetworkSnapshot(endpoint,moment,System.currentTimeMillis(),SystemClock.elapsedRealtime(),c.subscriptionId,c.simSlotIndex,c.operator,c.rat,c.displayRat,c.voiceRat,c.mcc,c.mnc,c.tac,c.cellId,c.pci,c.arfcn,c.band,c.bandwidth,c.rsrp,c.rsrq,c.sinr,c.rssi,c.carrierAggregation,NetworkStore.dataNetwork,l.latitude.toDoubleOrNull(),l.longitude.toDoubleOrNull(),l.speedKmh,l.accuracy)
    }
    private fun captureAndSend(moment:String)=scope.launch{
        val endpoint=if(DeviceLinkStore.link.value.role==DeviceLinkRole.CONTROLLER)"A" else "B"
        val s=captureSnapshot(endpoint,moment,activeSubscriptionId)
        if(endpoint=="A")localLiveSnapshots.getOrPut(attemptId){Collections.synchronizedList(mutableListOf())}.add(s)
        DeviceLinkStore.callTest.value=DeviceLinkStore.callTest.value.copy(localSnapshot=s)
        send("NETWORK_SNAPSHOT",payload=snapshotPayload(s))
    }

    private fun buildProfile(slot:Int=repository.loadLocalSimSlot()):DeviceProfile { val cached=runBlocking{runCatching{cellular.readAllSims().firstOrNull{it.simSlotIndex==slot}?:cellular.readAllSims().firstOrNull()}.getOrNull()};val c=cached?.servingCell;val battery=getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?:-1;return DeviceProfile(Build.MODEL,deviceId,repository.loadLocalNumber(),slot,c?.subscriptionId?:-1,c?.operator?:"--",c?.displayRat?:"--",c?.voiceRat?:"--",c?.rsrp?:"--",battery,BuildConfig.VERSION_NAME) }
    private fun subscriptionForSlot(slot:Int)=runCatching{cellular.activeSubscriptions().firstOrNull{it.simSlotIndex==slot}?.subscriptionId}.getOrNull()?:-1
    private fun saveProfile(number:String,slot:Int){repository.saveLocalIdentity(number,slot);scope.launch{val p=buildProfile(slot);DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(localProfile=p);send("DEVICE_STATUS",payload=profilePayload(p))}}
    private fun automationCapability()=when{ContextCompat.checkSelfPermission(this,Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED->"Semi-Auto: CALL_PHONE not granted";ContextCompat.checkSelfPermission(this,Manifest.permission.ANSWER_PHONE_CALLS)!=PackageManager.PERMISSION_GRANTED->"Semi-Auto: manual answer/hang-up required";else->"Auto answer/hang-up available (public API)"}

    private fun startOwnedRecording(task:String,sub:Int){if(isRecordingActive()||sub<0)return;ownedRecordingPath="pending";ContextCompat.startForegroundService(this,Intent(this,RecordingService::class.java).putExtra(RecordingService.EXTRA_SUBSCRIPTION_ID,sub).putExtra(RecordingService.EXTRA_BOTH_SIMS,false).putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID,sub).putExtra(RecordingService.EXTRA_TASK_NAME,task));scope.launch{delay(400);ownedRecordingPath=RecordingState.status.value.latestPath}}
    private fun stopOwnedRecording(){val owned=ownedRecordingPath;if(owned!=null&&(owned=="pending"||RecordingState.status.value.latestPath==owned))stopService(Intent(this,RecordingService::class.java));ownedRecordingPath=null}
    private fun isRecordingActive()=RecordingState.status.value.isRecording||getSharedPreferences("celltracker_recording",MODE_PRIVATE).getBoolean("active_recording",false)
    private fun recordAutoEvent(type:String,note:String){if(!isRecordingActive())return;val sub=activeSubscriptionId.takeIf{it>=0}?:return;ContextCompat.startForegroundService(this,Intent(this,RecordingService::class.java).apply{action=RecordingService.ACTION_MARK;putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID,sub);putExtra(RecordingService.EXTRA_EVENT_TYPE,type);putExtra(RecordingService.EXTRA_EVENT_NOTE,note);putExtra(RecordingService.EXTRA_EVENT_SOURCE,"AUTO")})}

    private fun agentAttemptResult(m:DeviceLinkMessage){
        val event=m.payload["event"]?:"CALL_SETUP_FAILURE";recordAutoEvent(event,m.payload["detail"]?:m.payload["result"].orEmpty())
        if(m.payload["high"]=="true")recordAutoEvent("HIGH_CALL_SETUP_LATENCY","${m.payload["latency"]} ms")
    }
    private fun finishAgentTest(status:String){endCall();stopOwnedRecording();unregisterCallMonitor();val s=DeviceLinkStore.callTest.value;DeviceLinkStore.callTest.value=s.copy(isRunning=false,endedAt=System.currentTimeMillis(),statusMessage=status)}
    private fun stopTest(reason:String,notifyPeer:Boolean){if(notifyPeer)send("STOP_TEST",payload=mapOf("status" to reason));testJob?.cancel(CancellationException(reason));if(DeviceLinkStore.link.value.role==DeviceLinkRole.AGENT)finishAgentTest(reason);endCall()}
    private fun failForLinkLoss(){if(testJob?.isActive==true){recordAutoEvent("DEVICE_LINK_LOST","Bluetooth control link lost");sessionDir?.let{repository.appendEvent(it,System.currentTimeMillis(),"DEVICE_LINK_LOST",attemptId,DeviceLinkStore.callTest.value.currentDirection,"Bluetooth control link lost")};testJob?.cancel(CancellationException("Bluetooth link lost"))}}

    private fun disconnect(user:Boolean){
        intentionallyDisconnected.set(true)
        if(user)getSharedPreferences("device_link",MODE_PRIVATE).edit().remove("last_mode").remove("last_address").apply()
        stopTest("Device Link disconnected",false)
        stopCurrentLinkTransport(clearPeer=true)
        releaseForegroundResources()
        refreshDevices()
        DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.DISCONNECTED,statusMessage="Disconnected",peer=null,peerProfile=null,discoveryActive=false)
        stopSelf()
    }
    private fun releaseForegroundResources(){runCatching{if(wakeLock?.isHeld==true)wakeLock?.release()};wakeLock=null;locationJob?.cancel();locationJob=null;stopForeground(STOP_FOREGROUND_REMOVE)}

    private fun canConnectBluetooth()=Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED
    private fun canScanBluetooth()=Build.VERSION.SDK_INT<31||ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED
    private fun isAdapterDiscoverable():Boolean = canConnectBluetooth() && runCatching { adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE }.getOrDefault(false)

    private fun discover(){
        if(!canScanBluetooth() || !canConnectBluetooth()){setLinkStatus(DeviceLinkStatus.PERMISSION_REQUIRED,"Nearby devices permission is required");return}
        if(adapter?.isEnabled!=true){setLinkStatus(DeviceLinkStatus.BLUETOOTH_OFF,"Bluetooth is off. Turn it on and try again.");return}
        if(DeviceLinkStore.link.value.role!=DeviceLinkRole.CONTROLLER) switchToController()
        intentionallyDisconnected.set(false)
        runCatching{adapter?.cancelDiscovery()}
        ensureForeground("Scanning Bluetooth devices")
        refreshDevices()
        val started=runCatching{adapter?.startDiscovery()==true}.getOrDefault(false)
        DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(
            role=DeviceLinkRole.CONTROLLER,
            status=if(started)DeviceLinkStatus.SCANNING else DeviceLinkStatus.CONNECTION_FAILED,
            statusMessage=if(started)"Scanning for nearby Bluetooth devices" else "Could not start Bluetooth scan",
            discoveredDevices=emptyList(),
            discoveryActive=started
        )
        if(!started) releaseForegroundResources()
    }

    private fun pair(address:String){
        if(!canConnectBluetooth()){setLinkStatus(DeviceLinkStatus.PERMISSION_REQUIRED,"Bluetooth connect permission is required");return}
        if(adapter?.isEnabled!=true){setLinkStatus(DeviceLinkStatus.BLUETOOTH_OFF,"Bluetooth is off");return}
        val d=runCatching{adapter?.getRemoteDevice(address)}.getOrNull() ?: run { setLinkStatus(DeviceLinkStatus.CONNECTION_FAILED,"Device is no longer available"); return }
        DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.PAIRING,statusMessage="Pairing with ${runCatching{d.name}.getOrNull()?:address}")
        val ok=runCatching{d.createBond()}.getOrDefault(false)
        if(!ok && d.bondState!=BluetoothDevice.BOND_BONDED)setLinkStatus(DeviceLinkStatus.CONNECTION_FAILED,"Unable to start pairing")
    }

    private fun refreshDevices(){
        val btOn=adapter?.isEnabled==true
        val permissions=canConnectBluetooth()&&canScanBluetooth()
        if(!canConnectBluetooth()){
            DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(bluetoothEnabled=btOn,permissionGranted=permissions,discoverable=false)
            return
        }
        val paired=runCatching{adapter?.bondedDevices.orEmpty().map{BluetoothPeer(it.name?:"Unknown",address=it.address,bonded=true)}.sortedBy{it.name}}.getOrDefault(emptyList())
        DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(
            bluetoothEnabled=btOn,
            permissionGranted=permissions,
            discoverable=isAdapterDiscoverable(),
            discoveryActive=runCatching{adapter?.isDiscovering==true}.getOrDefault(false),
            pairedDevices=paired,
            localProfile=buildProfile()
        )
    }

    private val discoveryReceiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            when(intent.action){
                BluetoothDevice.ACTION_FOUND->{
                    if(!canConnectBluetooth())return
                    val d=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)else @Suppress("DEPRECATION")(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
                    d?.let{
                        val peer=BluetoothPeer(runCatching{it.name}.getOrNull()?:"Unknown",address=it.address,bonded=it.bondState==BluetoothDevice.BOND_BONDED)
                        val st=DeviceLinkStore.link.value
                        val found=(st.discoveredDevices.filterNot{x->x.address==peer.address}+peer).sortedBy{x->x.name}
                        DeviceLinkStore.link.value=st.copy(status=DeviceLinkStatus.DEVICE_FOUND,statusMessage="Found ${found.size} device${if(found.size==1)"" else "s"}; scan still running",discoveredDevices=found,discoveryActive=true)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED->{
                    val st=DeviceLinkStore.link.value
                    DeviceLinkStore.link.value=st.copy(status=DeviceLinkStatus.SCANNING,statusMessage="Scanning for nearby Bluetooth devices",discoveryActive=true)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED->{
                    val st=DeviceLinkStore.link.value
                    if(st.role==DeviceLinkRole.CONTROLLER && st.status!=DeviceLinkStatus.CONNECTED && st.status!=DeviceLinkStatus.CONNECTING){
                        DeviceLinkStore.link.value=st.copy(status=DeviceLinkStatus.IDLE,statusMessage=if(st.discoveredDevices.isEmpty())"Scan complete: no nearby devices found" else "Scan complete: ${st.discoveredDevices.size} device(s) found",discoveryActive=false)
                        releaseForegroundResources()
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED->{
                    if(!canConnectBluetooth())return
                    val d=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice::class.java)else @Suppress("DEPRECATION")(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
                    refreshDevices()
                    d?.let{
                        when(it.bondState){
                            BluetoothDevice.BOND_BONDING->DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.PAIRING,statusMessage="Pairing with ${runCatching{it.name}.getOrNull()?:it.address}")
                            BluetoothDevice.BOND_BONDED->DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(status=DeviceLinkStatus.DEVICE_FOUND,statusMessage="Paired. Tap Connect.")
                            BluetoothDevice.BOND_NONE->if(DeviceLinkStore.link.value.status==DeviceLinkStatus.PAIRING)setLinkStatus(DeviceLinkStatus.CONNECTION_FAILED,"Pairing failed or was cancelled")
                        }
                    }
                }
                BluetoothAdapter.ACTION_SCAN_MODE_CHANGED->refreshDevices()
                BluetoothAdapter.ACTION_STATE_CHANGED->refreshDevices()
            }
        }
    }
    private fun registerDiscoveryReceiver(){
        val f=IntentFilter().apply{
            addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(discoveryReceiver,f,RECEIVER_EXPORTED)else @Suppress("DEPRECATION") registerReceiver(discoveryReceiver,f)
    }

    private fun setLinkStatus(status:DeviceLinkStatus,text:String){DeviceLinkStore.link.value=DeviceLinkStore.link.value.copy(status=status,statusMessage=text,bluetoothEnabled=adapter?.isEnabled==true,permissionGranted=canConnectBluetooth()&&canScanBluetooth(),discoverable=isAdapterDiscoverable())}
    private fun setLinkError(text:String)=setLinkStatus(DeviceLinkStatus.CONNECTION_FAILED,text)
    private fun updateNotification(text:String)=getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification(text))
    private fun notification(text:String):Notification{val stop=PendingIntent.getService(this,2,Intent(this,DeviceLinkService::class.java).setAction(ACTION_DISCONNECT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE);return NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("CellTracker Device Link").setContentText(text).setOngoing(true).addAction(0,"Disconnect",stop).build()}
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"Device Link and Call Test",NotificationManager.IMPORTANCE_LOW))}

    private fun profilePayload(p:DeviceProfile)=mapOf("device_name" to p.deviceName,"device_id" to p.deviceId,"phone" to p.phoneNumber,"sim_slot" to p.simSlot.toString(),"subscription_id" to p.subscriptionId.toString(),"operator" to p.operator,"rat" to p.rat,"voice_rat" to p.voiceRat,"signal" to p.signal,"battery" to p.batteryPercent.toString(),"version" to p.appVersion)
    private fun profileFrom(p:Map<String,String>)=DeviceProfile(p["device_name"]?:"Unknown",p["device_id"].orEmpty(),p["phone"].orEmpty(),p["sim_slot"]?.toIntOrNull()?:0,p["subscription_id"]?.toIntOrNull()?:-1,p["operator"]?:"--",p["rat"]?:"--",p["voice_rat"]?:"--",p["signal"]?:"--",p["battery"]?.toIntOrNull()?:-1,p["version"]?:"--")
    private fun configPayload(c:CallSetupConfig)=mapOf("task" to c.taskName,"direction" to c.direction.name,"count" to c.callCount.toString(),"timeout" to c.setupTimeoutMs.toString(),"hold" to c.holdTimeMs.toString(),"interval" to c.interCallIntervalMs.toString(),"threshold" to c.highLatencyThresholdMs.toString(),"auto_record" to c.autoRecord.toString(),"a_sim" to c.aCallSimSlot.toString(),"b_sim" to c.bCallSimSlot.toString(),"mode" to c.automationMode.name)
    private fun configFrom(p:Map<String,String>)=CallSetupConfig(p["task"]?:"CallSetup",runCatching{CallDirection.valueOf(p["direction"]!!)}.getOrDefault(CallDirection.A_TO_B),p["count"]?.toIntOrNull()?:10,p["timeout"]?.toLongOrNull()?:30000,p["hold"]?.toLongOrNull()?:10000,p["interval"]?.toLongOrNull()?:10000,p["threshold"]?.toLongOrNull()?:8000,p["auto_record"].toBoolean(),p["a_sim"]?.toIntOrNull()?:0,p["b_sim"]?.toIntOrNull()?:0,runCatching{AutomationMode.valueOf(p["mode"]!!)}.getOrDefault(AutomationMode.AUTO_WHEN_AVAILABLE))
    private fun snapshotPayload(s:CallNetworkSnapshot)=mapOf("endpoint" to s.endpoint,"moment" to s.moment,"timestamp" to s.timestampMs.toString(),"elapsed" to s.elapsedRealtimeMs.toString(),"sub" to s.subscriptionId.toString(),"slot" to s.simSlot.toString(),"operator" to s.operator,"rat" to s.rat,"display_rat" to s.displayRat,"voice_rat" to s.voiceRat,"mcc" to s.mcc,"mnc" to s.mnc,"tac" to s.tac,"cell_id" to s.cellId,"pci" to s.pci,"arfcn" to s.arfcn,"band" to s.band,"bandwidth" to s.bandwidth,"rsrp" to s.rsrp,"rsrq" to s.rsrq,"sinr" to s.sinr,"rssi" to s.rssi,"ca" to s.carrierAggregation,"data_net" to s.dataNetwork,"lat" to (s.latitude?.toString()?:""),"lon" to (s.longitude?.toString()?:""),"speed" to s.speedKmh,"accuracy" to s.accuracy)
    private fun snapshotFrom(p:Map<String,String>)=CallNetworkSnapshot(p["endpoint"]?:"B",p["moment"]?:"--",p["timestamp"]?.toLongOrNull()?:0,p["elapsed"]?.toLongOrNull()?:0,p["sub"]?.toIntOrNull()?:-1,p["slot"]?.toIntOrNull()?:-1,p["operator"]?:"--",p["rat"]?:"--",p["display_rat"]?:"--",p["voice_rat"]?:"--",p["mcc"]?:"--",p["mnc"]?:"--",p["tac"]?:"--",p["cell_id"]?:"--",p["pci"]?:"--",p["arfcn"]?:"--",p["band"]?:"--",p["bandwidth"]?:"--",p["rsrp"]?:"--",p["rsrq"]?:"--",p["sinr"]?:"--",p["rssi"]?:"--",p["ca"]?:"--",p["data_net"]?:"--",p["lat"]?.toDoubleOrNull(),p["lon"]?.toDoubleOrNull(),p["speed"]?:"--",p["accuracy"]?:"--")
    private fun readConfig(i:Intent)=CallSetupConfig(i.getStringExtra(EXTRA_TASK).orEmpty().ifBlank{"CallSetup"},runCatching{CallDirection.valueOf(i.getStringExtra(EXTRA_DIRECTION)!!)}.getOrDefault(CallDirection.A_TO_B),i.getIntExtra(EXTRA_COUNT,10).coerceIn(1,500),i.getLongExtra(EXTRA_TIMEOUT,30000).coerceIn(5000,120000),i.getLongExtra(EXTRA_HOLD,10000).coerceIn(1000,300000),i.getLongExtra(EXTRA_INTERVAL,10000).coerceIn(1000,300000),i.getLongExtra(EXTRA_THRESHOLD,8000).coerceIn(500,120000),i.getBooleanExtra(EXTRA_AUTO_RECORD,true),i.getIntExtra(EXTRA_A_SIM,0),i.getIntExtra(EXTRA_B_SIM,0),runCatching{AutomationMode.valueOf(i.getStringExtra(EXTRA_MODE)!!)}.getOrDefault(AutomationMode.AUTO_WHEN_AVAILABLE))

    override fun onDestroy(){intentionallyDisconnected.set(true);if(DeviceLinkStore.callTest.value.isRunning)endCall();testJob?.cancel();scope.cancel();unregisterCallMonitor();runCatching{if(wakeLock?.isHeld==true)wakeLock?.release()};wakeLock=null;runCatching{unregisterReceiver(discoveryReceiver)};runCatching{socket?.close()};runCatching{serverSocket?.close()};super.onDestroy()}
    override fun onBind(intent:Intent?)=null
    companion object { const val CHANNEL_ID="celltracker_device_link";const val NOTIFICATION_ID=1301;const val ACTION_DISCOVER="device_link.discover";const val ACTION_PAIR="device_link.pair";const val ACTION_AGENT="device_link.agent";const val ACTION_CONTROLLER="device_link.controller";const val ACTION_CONNECT="device_link.connect";const val ACTION_DISCONNECT="device_link.disconnect";const val ACTION_REFRESH="device_link.refresh";const val ACTION_SAVE_PROFILE="device_link.save_profile";const val ACTION_START_TEST="call_setup.start";const val ACTION_STOP_TEST="call_setup.stop";const val EXTRA_ADDRESS="address";const val EXTRA_PHONE="phone";const val EXTRA_SIM_SLOT="sim_slot";const val EXTRA_TASK="task";const val EXTRA_DIRECTION="direction";const val EXTRA_COUNT="count";const val EXTRA_TIMEOUT="timeout";const val EXTRA_HOLD="hold";const val EXTRA_INTERVAL="interval";const val EXTRA_THRESHOLD="threshold";const val EXTRA_AUTO_RECORD="auto_record";const val EXTRA_A_SIM="a_sim";const val EXTRA_B_SIM="b_sim";const val EXTRA_MODE="mode" }
}
