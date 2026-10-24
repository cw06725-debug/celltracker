@file:Suppress("DEPRECATION")

package com.example.celltracker

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Post-test Wi-Fi Direct control plane. A P2P association does NOT mean an
 * Agent has registered, and registration does NOT mean its report is ready.
 * The report transfer itself remains in ExperienceLanV130.
 */
internal class ExperienceP2pV130(private val context: Context) {
    data class Host(val name:String,val address:String)
    data class Agent(
        val deviceId:String,val name:String,val role:String,val ip:String,
        val key:String,val session:String,val lastSeen:Long=System.currentTimeMillis()
    ) {
        val reportReady get()=key.length==12
    }

    private val manager=context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel:WifiP2pManager.Channel?=manager?.initialize(context,Looper.getMainLooper(),object:WifiP2pManager.ChannelListener{
        override fun onChannelDisconnected(){
            // OEM Wi-Fi resets can invalidate a Channel without a normal group broadcast.
            epoch.incrementAndGet()
            runCatching{coordinator?.close()};coordinator=null;coordinatorReady=false
            groupCreated=false;groupReleasing=false;connecting=false
            connected=false;running=false;groupOwnerIp="";groupClientCount=0
            status="Wi-Fi Direct channel reset; tap Start Controller / Find Controllers again"
            channel=manager?.initialize(context,Looper.getMainLooper(),null)
        }
    })
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newSingleThreadExecutor()
    private val epoch=AtomicInteger()
    private var advertised:WifiP2pDnsSdServiceInfo?=null
    private var request:WifiP2pDnsSdServiceRequest?=null
    private var receiverRegistered=false
    private var groupCreated=false
    private var connecting=false
    @Volatile private var groupReleasing=false
    @Volatile private var releasingEpoch=0
    @Volatile private var coordinator:ServerSocket?=null
    @Volatile private var coordinatorStarting=false
    private val agents=linkedMapOf<String,Agent>()
    private val hosts=linkedMapOf<String,Host>()
    @Volatile var status="Not connected";private set
    @Volatile var groupOwnerIp="";private set
    @Volatile var isOwner=false;private set
    @Volatile var connected=false;private set
    @Volatile var controllerMode=false;private set
    @Volatile var running=false;private set
    @Volatile var groupClientCount=0;private set
    @Volatile var coordinatorReady=false;private set
    @Volatile var registeredWithController=false;private set
    @Volatile var reportReadyWithController=false;private set
    var expectedSession=""

    private val receiver=object:BroadcastReceiver(){
        override fun onReceive(c:Context,i:Intent){
            when(i.action){
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshStatus()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    if(i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,-1)!=WifiP2pManager.WIFI_P2P_STATE_ENABLED){
                        connected=false;groupOwnerIp="";groupClientCount=0;coordinatorReady=false
                        status="Wi-Fi Direct disabled: enable Wi-Fi and try again"
                    }
                }
            }
        }
    }
    val available get()=manager!=null&&channel!=null
    fun hosts():List<Host> = synchronized(hosts){hosts.values.toList()}
    fun agents():List<Agent> = synchronized(agents){agents.values.toList()}
    fun forgetAgent(deviceId:String){
        synchronized(agents){agents.remove(deviceId)}
        if(controllerMode)status="Agent record cleared; the device may reconnect and register again"
    }

    @SuppressLint("MissingPermission")
    private fun startReceiver(){
        if(receiverRegistered)return
        val filter=IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver,filter)
        receiverRegistered=true
    }

    /** Query the actual formed group; connect() success only means request accepted. */
    @SuppressLint("MissingPermission")
    fun refreshStatus(){
        val m=manager?:return;val ch=channel?:return
        val token=epoch.get()
        runCatching { m.requestConnectionInfo(ch){info->
            if(!running||epoch.get()!=token)return@requestConnectionInfo
            if(info.groupFormed){
                connected=true;isOwner=info.isGroupOwner;connecting=false
                // The GO address can be anonymized for the GO on recent Android.
                // Never depend on it when starting the Controller's listener.
                groupOwnerIp=if(info.isGroupOwner)"" else info.groupOwnerAddress?.hostAddress.orEmpty()
                if(isOwner&&controllerMode){
                    status=if(coordinatorReady)"Controller ready: $groupClientCount P2P peer(s), ${agents().count{it.reportReady}} report(s) ready"
                        else "Controller group formed; starting registration service…"
                    startCoordinator()
                } else if(!isOwner&&!controllerMode) {
                    status=when {
                        reportReadyWithController -> "Connected; report ready for Controller collection"
                        registeredWithController -> "Connected; registered, report not yet ready"
                        groupOwnerIp.isNotBlank() -> "Wi-Fi Direct connected; registering Agent…"
                        else -> "Group connected; waiting for Controller IPv4 address…"
                    }
                }
            } else {
                connected=false;isOwner=false;groupOwnerIp="";groupClientCount=0;coordinatorReady=false
                registeredWithController=false;reportReadyWithController=false
                if(running && !connecting)status=if(controllerMode)"Creating Controller group…" else "Awaiting Wi-Fi Direct connection"
            }
        } }
        if(controllerMode&&running)runCatching {m.requestGroupInfo(ch){group->
            if(!controllerMode||!running||epoch.get()!=token)return@requestGroupInfo
            groupClientCount=group?.clientList?.size?:0
            if(group!=null && group.isGroupOwner){
                // Some devices broadcast group formation before ConnectionInfo is ready.
                isOwner=true;connected=true;startCoordinator()
            }
            if(coordinatorReady)status="Controller ready: $groupClientCount P2P peer(s), ${agents().count{it.reportReady}} report(s) ready"
        }}
    }

    private fun reasonName(reason:Int)=when(reason){
        WifiP2pManager.ERROR->"ERROR (0)";WifiP2pManager.P2P_UNSUPPORTED->"UNSUPPORTED (1)"
        WifiP2pManager.BUSY->"BUSY (2)";else->"code $reason"
    }

    @SuppressLint("MissingPermission")
    fun startController(session:String){
        require(available){"Wi-Fi Direct is unsupported by this phone"}
        require(session.isNotBlank()){ "Choose a Session before starting Controller" }
        // A previous Controller/Agent group must finish leaving before createGroup().
        val hadGroup=groupCreated||connected||connecting
        reset { token ->
            controllerMode=true;running=true;expectedSession=session
            status="Creating CellTracker Controller group…"
            val launch={ if(epoch.get()==token&&controllerMode&&running)publishController(token) }
            if(hadGroup)main.postDelayed(launch,450) else launch()
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishController(token:Int){
        val m=manager?:return;val ch=channel?:return
        startReceiver()
        val service=WifiP2pDnsSdServiceInfo.newInstance("CellTrackerController","_celltracker._tcp",
            mapOf("app" to "CellTracker","purpose" to "posttest-report-sync","version" to "1"))
        advertised=service
        m.addLocalService(ch,service,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){
                if(epoch.get()!=token||!controllerMode)return
                m.createGroup(ch,object:WifiP2pManager.ActionListener{
                    override fun onSuccess(){
                        if(epoch.get()!=token)return
                        groupCreated=true;status="Controller group requested; waiting for P2P interface…"
                        refreshStatus()
                    }
                    override fun onFailure(reason:Int){if(epoch.get()==token)
                        status="Create group failed: ${reasonName(reason)}. Stop/retry or use LAN fallback"}
                })
            }
            override fun onFailure(reason:Int){if(epoch.get()==token)
                status="Controller service advertisement failed: ${reasonName(reason)}"}
        })
    }

    @SuppressLint("MissingPermission")
    fun findControllers(){
        require(available){"Wi-Fi Direct unavailable"}
        if(connected){status="Disconnect from current group before scanning again";return}
        if(controllerMode){stop();controllerMode=false}
        controllerMode=false;running=true;connecting=false
        startReceiver()
        val token=epoch.incrementAndGet()
        synchronized(hosts){hosts.clear()}
        main.postDelayed({ if(epoch.get()==token && running && !connected)beginDiscovery(token) },300)
    }

    @SuppressLint("MissingPermission")
    private fun beginDiscovery(token:Int, attempts:Int=0){
        if(epoch.get()!=token||!running||controllerMode||connected)return
        if(groupReleasing){
            if(attempts>=14){status="Previous P2P group did not close. Turn Wi-Fi off/on and retry";return}
            main.postDelayed({beginDiscovery(token,attempts+1)},500)
            return
        }
        val m=manager?:return;val ch=channel?:return
        m.setDnsSdResponseListeners(ch,
            WifiP2pManager.DnsSdServiceResponseListener{name,type,device->
                if(epoch.get()==token&&!connected&&ExperiencePeerFilterV130.matchesService(name,type)){
                    synchronized(hosts){hosts[device.deviceAddress]=Host(device.deviceName.ifBlank{"CellTracker Controller"},device.deviceAddress)}
                    status="${hosts().size} CellTracker Controller(s) discovered"
                }
            },
            WifiP2pManager.DnsSdTxtRecordListener{domain,record,device->
                if(epoch.get()==token&&!connected&&ExperiencePeerFilterV130.matchesTxt(domain,record)){
                    synchronized(hosts){hosts[device.deviceAddress]=Host(device.deviceName.ifBlank{"CellTracker Controller"},device.deviceAddress)}
                    status="${hosts().size} CellTracker Controller(s) discovered"
                }
            })
        // Clear old requests first: repeated scans formerly accumulated asynchronous state.
        m.clearServiceRequests(ch,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){installDiscovery(token)}
            override fun onFailure(reason:Int){installDiscovery(token)}
        })
    }

    @SuppressLint("MissingPermission")
    private fun installDiscovery(token:Int){
        if(epoch.get()!=token||controllerMode||connected)return
        val m=manager?:return;val ch=channel?:return
        val req=WifiP2pDnsSdServiceRequest.newInstance("_celltracker._tcp")
        request=req
        m.addServiceRequest(ch,req,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){
                if(epoch.get()!=token)return
                m.discoverServices(ch,object:WifiP2pManager.ActionListener{
                    override fun onSuccess(){if(epoch.get()==token)status="Searching for CellTracker Controllers…"}
                    override fun onFailure(reason:Int){if(epoch.get()==token)
                        status="Discovery failed: ${reasonName(reason)}. Check Nearby Wi-Fi and Location"}
                })
            }
            override fun onFailure(reason:Int){if(epoch.get()==token)status="Service discovery failed: ${reasonName(reason)}"}
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(host:Host){
        require(available){"Wi-Fi Direct unavailable"}
        require(!controllerMode){"This device is in Controller mode"}
        require(host.address in hosts().map{it.address}){"Select an advertised CellTracker Controller"}
        if(connected){status="Already joined a Wi-Fi Direct group";return}
        if(connecting){status="Connection request already pending; use Disconnect before retry";return}
        connecting=true;status="Connecting to ${host.name}; approve Android prompt if shown…"
        val m=manager!!;val ch=channel!!;val token=epoch.get()
        val config=WifiP2pConfig().apply {deviceAddress=host.address;wps.setup=WpsInfo.PBC;groupOwnerIntent=0}
        // Discovery can block group negotiation on some OEM Wi-Fi stacks.
        m.stopPeerDiscovery(ch,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){joinController(m,ch,config,token)}
            override fun onFailure(reason:Int){joinController(m,ch,config,token)}
        })
    }

    @SuppressLint("MissingPermission")
    private fun joinController(m:WifiP2pManager,ch:WifiP2pManager.Channel,c:WifiP2pConfig,token:Int){
        if(epoch.get()!=token||!connecting)return
        m.connect(ch,c,object:WifiP2pManager.ActionListener{
            override fun onSuccess(){if(epoch.get()==token)status="Connection request accepted; waiting for actual P2P group…"}
            override fun onFailure(reason:Int){if(epoch.get()==token){connecting=false
                status="Connect failed: ${reasonName(reason)}. Disconnect, then retry; other Agents may remain connected"}}
        })
    }

    /** First register presence; a second announcement upgrades this Agent to report-ready. */
    fun announcePresence(agentRole:String,session:String):String=sendRegistration(agentRole,session,"")
    fun announce(agentRole:String,session:String,accessCode:String):String=sendRegistration(agentRole,session,accessCode)

    private fun sendRegistration(agentRole:String,session:String,key:String):String{
        require(!controllerMode && connected && !isOwner && groupOwnerIp.isNotBlank()) {"Connect to the Controller first"}
        require(agentRole in ExperienceAnalyzer.roles && session.isNotBlank())
        require(key.isEmpty()||key.matches(Regex("[A-Z2-9]{12}"))){"Invalid report pairing code"}
        val source=ExperienceLanV130.p2pAddresses().firstOrNull()
            ?:error("P2P client IPv4 address not ready; use Retry after connecting")
        Socket().use{s->
            // Force the registration route over Wi-Fi Direct, not another Wi-Fi network.
            s.bind(InetSocketAddress(InetAddress.getByName(source),0))
            s.connect(InetSocketAddress(groupOwnerIp,48732),6000)
            s.soTimeout=8000
            val payload=JSONObject().apply {
                put("protocol",1);put("app","CellTracker");put("session",session)
                put("deviceId",ExperienceLanV130.deviceId(context));put("model",Build.MODEL.orEmpty())
                put("role",agentRole);put("key",key)
            }
            s.getOutputStream().write((payload.toString()+"\n").toByteArray(Charsets.UTF_8));s.getOutputStream().flush()
            val response=BufferedReader(InputStreamReader(s.getInputStream(),Charsets.UTF_8)).readLine().orEmpty()
            require(response=="OK"){"Controller registration rejected: ${response.ifBlank{"No response"}}"}
            registeredWithController=true
            if(key.isNotBlank())reportReadyWithController=true
            status=if(reportReadyWithController)"Connected; report ready for Controller collection"
                else "Connected; registered, report not yet ready"
            return if(key.isBlank())"Agent connected and visible to Controller; report not prepared yet."
                else "Agent report ready; Controller can now collect it."
        }
    }

    /** Bind only the actual P2P interface; the GO's reported groupOwnerAddress may be anonymized. */
    private fun startCoordinator(){
        if(coordinatorStarting||coordinator!=null||!controllerMode||!isOwner||!running)return
        coordinatorStarting=true
        val token=epoch.get()
        io.execute {
            var server:ServerSocket?=null
            try{
                var address:String?=null
                for(attempt in 0 until 30){
                    if(epoch.get()!=token||!controllerMode||!running)return@execute
                    address=ExperienceLanV130.p2pAddresses().firstOrNull()
                    if(address!=null)break
                    Thread.sleep(300)
                }
                val p2pIp=address?:error("Wi-Fi Direct owner interface has no IPv4 address")
                if(epoch.get()!=token)return@execute
                server=ServerSocket(48732,8,InetAddress.getByName(p2pIp))
                coordinator=server;coordinatorReady=true
                status="Controller ready: $groupClientCount P2P peer(s), ${agents().count{it.reportReady}} report(s) ready"
                while(epoch.get()==token && controllerMode && !server!!.isClosed){
                    val peer=try{server!!.accept()}catch(_:Exception){break}
                    peer.use {s->try{
                        s.soTimeout=6500
                        val line=BufferedReader(InputStreamReader(s.getInputStream(),Charsets.UTF_8)).readLine()?.take(2049).orEmpty()
                        require(line.length in 2..2048){"Registration too large"}
                        val o=JSONObject(line)
                        val ip=s.inetAddress.hostAddress.orEmpty()
                        require(ExperienceLanV130.safeAddress(ip)==ip)
                        require(o.optInt("protocol")==1&&o.optString("app")=="CellTracker")
                        require(o.optString("session")==expectedSession){"SESSION_MISMATCH"}
                        val role=o.optString("role");require(role in ExperienceAnalyzer.roles)
                        val key=o.optString("key");require(key.isEmpty()||key.matches(Regex("[A-Z2-9]{12}")))
                        val id=o.optString("deviceId");require(id.matches(Regex("[A-Za-z0-9-]{8,64}")))
                        val model=o.optString("model").take(100)
                        synchronized(agents){
                            require(agents.values.none{it.role==role&&it.deviceId!=id}){"ROLE_IN_USE"}
                            // A fresh presence handshake invalidates any old report token;
                            // otherwise a disconnected/restarted share looks falsely ready.
                            agents[id]=Agent(id,model,role,ip,key,expectedSession)
                        }
                        s.getOutputStream().write("OK\n".toByteArray());s.getOutputStream().flush()
                        status="Controller ready: $groupClientCount P2P peer(s), ${agents().count{it.reportReady}} report(s) ready"
                    }catch(e:Exception){runCatching {s.getOutputStream().write((e.message?.take(64)?:"REJECTED").plus("\n").toByteArray())}} }
                }
            }catch(e:Exception){if(epoch.get()==token && controllerMode)
                status="Controller registration service failed: ${e.message}. Restart Group"}
            finally{
                runCatching{server?.close()}
                if(epoch.get()==token){coordinator=null;coordinatorReady=false}
                coordinatorStarting=false
            }
        }
    }

    private fun whenReleased(token:Int,attempt:Int=0,after:(Int)->Unit){
        if(epoch.get()!=token)return
        if(!groupReleasing){after(token);return}
        if(attempt>=24){status="Previous Wi-Fi Direct group release timed out. Toggle Wi-Fi and retry";return}
        main.postDelayed({whenReleased(token,attempt+1,after)},350)
    }

    /** Leave both controller and client groups; old code only left when this device was GO. */
    @SuppressLint("MissingPermission")
    private fun reset(after:(Int)->Unit){
        val token=epoch.incrementAndGet()
        val wasReleasing=groupReleasing
        val oldGroup=groupCreated||connected||connecting||running
        controllerMode=false;running=false;connected=false;isOwner=false;connecting=false
        groupClientCount=0;coordinatorReady=false;groupOwnerIp="";status="Not connected"
        registeredWithController=false;reportReadyWithController=false
        runCatching{coordinator?.close()};coordinator=null
        val m=manager;val ch=channel
        request=null;advertised=null
        if(receiverRegistered){runCatching{context.unregisterReceiver(receiver)};receiverRegistered=false}
        synchronized(agents){agents.clear()};synchronized(hosts){hosts.clear()}
        groupCreated=false
        if(m==null||ch==null){groupReleasing=false;after(token);return}
        runCatching{m.stopPeerDiscovery(ch,null)}
        runCatching{m.clearServiceRequests(ch,null)}
        runCatching{m.clearLocalServices(ch,null)}
        if(oldGroup){
            // removeGroup also disconnects a P2P client, without touching Bluetooth.
            groupReleasing=true;releasingEpoch=token
            runCatching {m.cancelConnect(ch,null)}
            val complete={
                // Even if the UI has switched modes since stop(), the old release
                // must clear its pending flag. Otherwise discovery waits forever.
                if(releasingEpoch==token)groupReleasing=false
                if(epoch.get()==token)after(token)
            }
            runCatching {m.removeGroup(ch,object:WifiP2pManager.ActionListener{
                override fun onSuccess(){complete()}
                override fun onFailure(reason:Int){complete()}
            })}.onFailure{complete()}
        } else if(wasReleasing){
            whenReleased(token,after=after)
        } else {groupReleasing=false;after(token)}
    }
    fun stop(){reset{}}
    fun dispose(){stop();io.shutdownNow()}
}
