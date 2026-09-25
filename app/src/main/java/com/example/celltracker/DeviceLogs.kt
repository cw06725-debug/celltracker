package com.example.celltracker

import android.app.*
import android.content.*
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/** Device Logs / ADB Tools. All privileged operations go through an authenticated adbd session. */
data class AdbEndpoint(val host:String="", val pairingPort:Int=0, val connectPort:Int=0)
data class AdbUiState(
    val localEndpoint:AdbEndpoint=AdbEndpoint(), val localStatus:String="Not connected", val localIdentity:String="--",
    val remoteEndpoint:AdbEndpoint=AdbEndpoint(), val remoteStatus:String="Not connected", val remoteIdentity:String="--",
    val usbStatus:String="Disconnected", val usbDevice:String="", val usbDetectedBrand:String="", val usbError:String="",
    val logcatRunning:Boolean=false, val logcatBytes:Long=0, val logcatPath:String="",
    val exportRunning:Boolean=false, val exportPhase:String="", val exportBytes:Long=0, val exportTotalBytes:Long=0,
    val exportFiles:Long=0, val exportFound:Long=0, val exportSkipped:Long=0, val exportStartedMs:Long=0,
    val exportPullMs:Long=0, val exportTotalMs:Long=0, val exportZipMs:Long=0, val exportPullBytes:Long=0,
    val exportDeleted:Long=0, val exportDeleteFailed:Long=0, val exportSymlinks:Long=0, val exportListFailed:Long=0,
    val exportPath:String="", val exportResult:String="", val exportError:String="",
    val refLabel:String="vivo_REF",
    val mftRunning:Boolean=false, val mftPhase:String="", val mftSource:String="", val mftSavedPath:String="", val mftCleanup:String="",
    val mftFiles:List<String> = emptyList(), val mftSelected:String="", val mftTransport:String="",
    val message:String=""
)
object AdbToolStore { val state=MutableStateFlow(AdbUiState()) }

object CellTrackerAdbEngine {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var logcatJob:Job?=null
    private var exportJob:Job?=null
    @Volatile private var discoveredPair:AdbEndpoint?=null
    @Volatile private var discoveredConnect:AdbEndpoint?=null
    @Volatile private var pairDiscoveryListener:NsdManager.DiscoveryListener?=null
    @Volatile private var connectDiscoveryListener:NsdManager.DiscoveryListener?=null
    private val adbSessionMutex=kotlinx.coroutines.sync.Mutex()

    fun startDiscovery(context:Context) {
        startDiscoveryType(context,"_adb-tls-pairing._tcp") { ep ->
            discoveredPair=ep
            val old=AdbToolStore.state.value
            AdbToolStore.state.value=old.copy(
                localEndpoint=old.localEndpoint.copy(host=ep.host,pairingPort=ep.pairingPort),
                message="Pairing device found: ${ep.host}:${ep.pairingPort}"
            )
            pairingNotification(
                context,
                "CellTracker · Pairing device found",
                "${ep.host}:${ep.pairingPort} · Expand notification and enter the 6-digit pairing code",
                true
            )
        }
        startDiscoveryType(context,"_adb-tls-connect._tcp") { ep ->
            discoveredConnect=ep
            val old=AdbToolStore.state.value
            AdbToolStore.state.value=old.copy(
                localEndpoint=old.localEndpoint.copy(host=ep.host,connectPort=ep.connectPort)
            )
        }
    }

    private fun stopDiscoveryQuietly(nsd:NsdManager, listener:NsdManager.DiscoveryListener?) {
        if(listener==null) return
        runCatching { nsd.stopServiceDiscovery(listener) }
    }

    private fun startDiscoveryType(context:Context,type:String,onFound:(AdbEndpoint)->Unit) {
        val nsd=context.getSystemService(NsdManager::class.java)?:return
        val oldListener=if(type.contains("pairing")) pairDiscoveryListener else connectDiscoveryListener
        stopDiscoveryQuietly(nsd,oldListener)

        lateinit var listener:NsdManager.DiscoveryListener
        listener=object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(serviceType:String){}
            override fun onDiscoveryStopped(serviceType:String){}
            override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int){
                AdbToolStore.state.value=AdbToolStore.state.value.copy(message="ADB discovery failed: $errorCode")
                stopDiscoveryQuietly(nsd,listener)
            }
            override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int){}
            override fun onServiceLost(serviceInfo:NsdServiceInfo){}
            override fun onServiceFound(serviceInfo:NsdServiceInfo){
                runCatching {
                    nsd.resolveService(serviceInfo,object:NsdManager.ResolveListener{
                        override fun onResolveFailed(si:NsdServiceInfo,errorCode:Int){}
                        override fun onServiceResolved(si:NsdServiceInfo){
                            val h=si.host?.hostAddress?:return
                            val p=si.port
                            if(p<=0) return
                            onFound(if(type.contains("pairing")) AdbEndpoint(h,p,0) else AdbEndpoint(h,0,p))
                        }
                    })
                }
            }
        }
        if(type.contains("pairing")) pairDiscoveryListener=listener else connectDiscoveryListener=listener
        runCatching { nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener) }
            .onFailure { AdbToolStore.state.value=AdbToolStore.state.value.copy(message="ADB discovery start failed: ${it.message}") }
    }

    private fun pairingNotification(context:Context,title:String,text:String,allowInput:Boolean) {
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("adb_pair","ADB pairing",NotificationManager.IMPORTANCE_HIGH))
        val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi=launch?.let{PendingIntent.getActivity(context,8802,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val builder=NotificationCompat.Builder(context,"adb_pair")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(!allowInput).setOngoing(allowInput)
            .setOnlyAlertOnce(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_MAX)
        if(contentPi!=null) builder.setContentIntent(contentPi)
        if(allowInput){
            val remoteInput=RemoteInput.Builder("pair_code").setLabel("6-digit pairing code").setAllowFreeFormInput(true).build()
            val replyIntent=Intent(context,AdbPairCodeReceiver::class.java).apply{action="com.example.celltracker.ADB_PAIR_CODE"}
            val replyPi=PendingIntent.getBroadcast(context,8803,replyIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send,"ENTER PAIRING CODE",replyPi)
                .addRemoteInput(remoteInput).setAllowGeneratedReplies(false).build())
        }
        nm.notify(8801,builder.build())
    }

    fun showPairingNotification(context:Context) {
        // Start discovery before the user opens the system pairing-code dialog.
        discoveredPair=null
        startDiscovery(context)
        pairingNotification(context,"CellTracker ADB pairing","Waiting for Pair device with pairing code…",false)
    }

    private suspend fun waitForPairEndpoint(context:Context, timeoutMs:Long=10_000):AdbEndpoint {
        // The pairing port is intentionally ephemeral. Never fall back to a stale saved port.
        val deadline=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<deadline){
            discoveredPair?.takeIf{it.pairingPort>0}?.let{return it}
            delay(150)
        }
        error("Pairing service not found. Keep the system pairing-code dialog open, then retry.")
    }

    private suspend fun waitForConnectEndpoint(timeoutMs:Long=10_000):AdbEndpoint? {
        val deadline=System.currentTimeMillis()+timeoutMs
        while(System.currentTimeMillis()<deadline){
            discoveredConnect?.takeIf{it.connectPort>0}?.let{return it}
            delay(150)
        }
        return null
    }

    private suspend fun discoverAndConnectLocal(context:Context, timeoutMs:Long=9_000):String {
        val mgr=CellTrackerAdbConnectionManager.getInstance(context)
        val old=AdbToolStore.state.value
        AdbToolStore.state.value=old.copy(message="Finding Wireless ADB service…")

        // First use an endpoint that mDNS has already resolved. This is the common fast path.
        var candidate=discoveredConnect?.takeIf{it.connectPort>0}
        if(candidate==null) startDiscovery(context)

        val deadline=System.currentTimeMillis()+timeoutMs
        var attempted:String?=null
        var lastError="ADB TLS service not discovered"
        while(System.currentTimeMillis()<deadline){
            val ep=candidate ?: discoveredConnect?.takeIf{it.connectPort>0}
            if(ep!=null){
                val key="${ep.host}:${ep.connectPort}"
                if(key!=attempted){
                    attempted=key
                    AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Connecting $key…")
                    val connected=runCatching { withTimeout(4_000){ mgr.connect(ep.host,ep.connectPort) } }
                        .getOrElse { lastError=it.message ?: it.javaClass.simpleName; false }
                    if(connected){
                        val id=runCatching { withTimeout(3_500){ command(context,"id",3_500).getOrThrow().trim() } }
                            .getOrElse { lastError=it.message ?: it.javaClass.simpleName; "" }
                        if(id.contains("uid=2000")) return id
                        if(id.isNotBlank()) lastError="Identity is not shell: $id"
                    } else lastError="Connect failed at $key: $lastError"

                    // Endpoint may rotate after pairing. Restart only the connect discovery once,
                    // without stacking additional NSD listeners.
                    discoveredConnect=null
                    candidate=null
                    startDiscoveryType(context,"_adb-tls-connect._tcp") { fresh ->
                        discoveredConnect=fresh
                        val state=AdbToolStore.state.value
                        AdbToolStore.state.value=state.copy(localEndpoint=state.localEndpoint.copy(host=fresh.host,connectPort=fresh.connectPort))
                    }
                }
            }
            delay(120)
        }

        // One bounded fallback for ROMs where connect-service mDNS is hidden.
        val fallback=runCatching { withTimeout(4_000){ mgr.connectTls(context,4_000) } }.getOrDefault(false)
        if(fallback){
            val id=command(context,"id",3_500).getOrThrow().trim()
            if(id.contains("uid=2000")) return id
        }
        error(lastError)
    }

    suspend fun pairLocal(context:Context,code:String):Result<String> = withContext(Dispatchers.IO) {
        adbSessionMutex.lock()
        try { runCatching {
            require(code.trim().matches(Regex("\\d{6}"))){"Pairing code must be 6 digits"}
            pairingNotification(context,"CellTracker ADB pairing","Code received · discovering pairing service…",false)
            val ep=waitForPairEndpoint(context)
            val mgr=CellTrackerAdbConnectionManager.getInstance(context)
            AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Pairing ${ep.host}:${ep.pairingPort}…")
            pairingNotification(context,"CellTracker ADB pairing","Pairing with ${ep.host}:${ep.pairingPort}…",false)

            var pairWarning:String?=null
            val paired=try{
                withTimeout(10_000){mgr.pair(ep.host,ep.pairingPort,code.trim())}
            }catch(e:Throwable){
                // HiOS can accept the host key and then close the pairing socket with IOException.
                pairWarning=e.message ?: e.javaClass.simpleName
                false
            }

            if(paired){
                pairingNotification(context,"CellTracker ADB paired","Pairing accepted · discovering ADB TLS service…",false)
                AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Paired · discovering ADB connection…")
            }else{
                val note=pairWarning ?: "Pairing response was not confirmed"
                pairingNotification(context,"CellTracker ADB pairing","$note · checking whether the key was accepted…",false)
                AdbToolStore.state.value=AdbToolStore.state.value.copy(
                    localStatus="Verifying pairing",
                    message="$note · discovering ADB TLS service…"
                )
            }

            // Final truth is a working shell, not the pairing socket's final response.
            val id=discoverAndConnectLocal(context,10_000)
            AdbToolStore.state.value=AdbToolStore.state.value.copy(
                localStatus="Connected",localIdentity=id,message="Local ADB ready"
            )
            pairingNotification(
                context,
                "CellTracker Local ADB connected",
                if(pairWarning!=null)"Pairing accepted by device · connected as uid=2000(shell)"
                else "Connected as uid=2000(shell)",
                false
            )
            delay(250)
            context.getSystemService(NotificationManager::class.java).cancel(8801)
            "Connected as shell"
        }.onFailure { e ->
            val msg=when(e){
                is TimeoutCancellationException -> "Pair/connect timed out. Reopen Wireless debugging and retry."
                else -> e.message ?: e.javaClass.simpleName
            }
            AdbToolStore.state.value=AdbToolStore.state.value.copy(
                localStatus="Not connected",message="Pair/connect failed: $msg"
            )
            pairingNotification(context,"CellTracker ADB connection failed",msg,false)
        } } finally { adbSessionMutex.unlock() }
    }

    suspend fun connectLocal(context:Context):Result<String> = withContext(Dispatchers.IO){
        adbSessionMutex.lock()
        try {
            runCatching {
                var last:Throwable?=null
                var ready:String?=null
                for(attempt in 0..1){
                    try{
                        AdbToolStore.state.value=AdbToolStore.state.value.copy(
                            localStatus="Reconnecting",
                            message=if(attempt==0)"Finding Wireless ADB…" else "Retrying once…"
                        )
                        val id=discoverAndConnectLocal(context,if(attempt==0)8_000 else 6_000)
                        val verified=command(context,"id",3_500).getOrThrow().trim()
                        check(verified.contains("uid=2000")){"ADB shell verification failed: $verified"}
                        ready=verified
                        break
                    }catch(e:Throwable){
                        last=e
                        if(attempt==0){
                            discoveredConnect=null
                            startDiscoveryType(context,"_adb-tls-connect._tcp") { ep -> discoveredConnect=ep }
                            delay(300)
                        }
                    }
                }
                val id=ready ?: throw (last ?: IllegalStateException("ADB reconnect failed"))
                AdbToolStore.state.value=AdbToolStore.state.value.copy(
                    localStatus="Connected",localIdentity=id,message="Local ADB ready"
                )
                id
            }.onFailure { e ->
                AdbToolStore.state.value=AdbToolStore.state.value.copy(
                    localStatus="Not connected",
                    message="Reconnect failed: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        } finally { adbSessionMutex.unlock() }
    }
    fun refreshUsbRef(context:Context) {
        val h=UsbAdbHost.get(context);val info=h.info()
        AdbToolStore.state.value=AdbToolStore.state.value.copy(
            usbDevice=info?.let{"${it.name} · VID ${String.format("%04X",it.vendorId)} PID ${String.format("%04X",it.productId)}"} ?: "No USB ADB device",
            usbDetectedBrand=info?.name?.let{n->when{n.contains("SAMSUNG",true)->"Samsung";n.contains("vivo",true)->"vivo";else->"Custom"}} ?: "",
            usbStatus=if(h.connected)"Connected" else if(info!=null && h.hasPermission())"Ready" else if(info!=null)"Permission required" else "Disconnected",
            usbError=""
        )
    }
    fun requestUsbPermission(context:Context) = runCatching {
        UsbAdbHost.get(context).requestPermission("com.example.celltracker.USB_ADB_PERMISSION")
    }
    suspend fun connectUsbRef(context:Context):Result<String> = withContext(Dispatchers.IO){
        val h=UsbAdbHost.get(context)
        runCatching{
            val banner=h.connect()
            AdbToolStore.state.value=AdbToolStore.state.value.copy(usbStatus="Connected",usbError="",remoteStatus="USB Connected",remoteIdentity=banner,message="REF USB ADB ready")
            banner
        }.onFailure{e->
            AdbToolStore.state.value=AdbToolStore.state.value.copy(usbStatus="Failed · ${h.stage}",usbError=e.message ?: e.javaClass.simpleName,message="USB ADB: ${e.message}")
        }
    }
    suspend fun startUsbLogcat(context:Context,command:String,refLabel:String):Result<String> = withContext(Dispatchers.IO){runCatching{
        check(logcatJob?.isActive!=true){"A log capture is already running"}
        val resolver=context.contentResolver;val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date());val safe=refLabel.replace(Regex("[^A-Za-z0-9._-]"),"_")
        val name="${safe}_AP_Log_$stamp.txt";val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"text/plain");put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/$safe")}
        val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create AP log")
        AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=true,logcatBytes=0,logcatPath="Download/CellTracker/Logs/$safe/$name",message="USB logcat recording")
        logcatJob=scope.launch(Dispatchers.IO){try{resolver.openOutputStream(uri,"w")!!.use{out->UsbAdbHost.get(context).shell(command){b->out.write(b);out.flush();AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatBytes=AdbToolStore.state.value.logcatBytes+b.size)}}}finally{AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=false)}}
        "USB AP log started"
    }}
    suspend fun connectRemote(context:Context,host:String,port:Int):Result<String> = withContext(Dispatchers.IO){ runCatching {
        val mgr=CellTrackerAdbConnectionManager.getInstance(context); check(mgr.connect(host,port)){"Remote ADB connection failed"}; val id=command(context,"id").getOrThrow().trim()
        AdbToolStore.state.value=AdbToolStore.state.value.copy(remoteEndpoint=AdbEndpoint(host,0,port),remoteStatus="Connected",remoteIdentity=id,message="REF connected")
        id
    } }
    suspend fun pairRemote(context:Context,host:String,port:Int,code:String):Result<String> = withContext(Dispatchers.IO){ runCatching {
        val mgr=CellTrackerAdbConnectionManager.getInstance(context); check(mgr.pair(host,port,code)){"Remote pairing rejected"}; "REF paired. Enter its Wireless debugging connect port and press CONNECT."
    } }
    suspend fun command(context:Context,cmd:String,timeoutMs:Long=8_000):Result<String> = withContext(Dispatchers.IO){ runCatching {
        val marker="__CT_DONE_${System.nanoTime()}__"
        val wrapped="${cmd.trim()}; printf '\\n$marker\\n'"
        val stream=CellTrackerAdbConnectionManager.getInstance(context).openStream("shell:$wrapped")
        val input=stream.openInputStream()
        val out=ByteArrayOutputStream()
        val buf=ByteArray(4096)
        withTimeout(timeoutMs){
            while(true){
                val n=withContext(Dispatchers.IO){input.read(buf)}
                if(n<0) break
                if(n>0){
                    out.write(buf,0,n)
                    if(out.toString(Charsets.UTF_8.name()).contains(marker)) break
                }
            }
        }
        runCatching{input.close()}
        out.toString(Charsets.UTF_8.name()).substringBefore(marker).trimEnd()
    } }

    private suspend fun commandAny(context:Context,cmd:String,timeoutMs:Long=8_000):Result<String> = withContext(Dispatchers.IO) {
        if(UsbAdbHost.get(context).connected) {
            runCatching {
                val out=ByteArrayOutputStream()
                withTimeout(timeoutMs) {
                    UsbAdbHost.get(context).shell(cmd) { b -> out.write(b) }
                }
                out.toString(Charsets.UTF_8.name()).trimEnd()
            }
        } else command(context,cmd,timeoutMs)
    }

    suspend fun commandRef(context:Context,cmd:String,timeoutMs:Long=8_000):Result<String> =
        commandAny(context,cmd,timeoutMs)

    private suspend fun commandForMft(context:Context,cmd:String,timeoutMs:Long,useUsbRef:Boolean):Result<String> =
        if(useUsbRef) {
            if(!UsbAdbHost.get(context).connected) Result.failure(IllegalStateException("USB REF is not connected"))
            else commandAny(context,cmd,timeoutMs)
        } else command(context,cmd,timeoutMs)

    private suspend fun ensureMftReady(context:Context,reason:String,useUsbRef:Boolean):String {
        if(useUsbRef) {
            val usb=UsbAdbHost.get(context)
            check(usb.connected){"USB REF is not connected"}
            val id=commandForMft(context,"id",4_000,true).getOrElse{throw IllegalStateException("USB ADB shell failed: ${it.message}")}
            check(id.contains("uid=2000")){"USB ADB shell is not ready: $id"}
            AdbToolStore.state.value=AdbToolStore.state.value.copy(
                usbStatus="Connected",mftTransport="USB REF",message="USB ADB ready for $reason"
            )
            return id
        }
        val id=ensureLocalReady(context,reason)
        AdbToolStore.state.value=AdbToolStore.state.value.copy(mftTransport="Local DUT · Wireless ADB")
        return id
    }

    suspend fun refreshMftFiles(context:Context,useUsbRef:Boolean=false):Result<List<String>> = withContext(Dispatchers.IO) { runCatching {
        ensureMftReady(context,"MFT browser",useUsbRef)
        val root="/sdcard/Android/data/com.transsion.mft/files/Reports"
        AdbToolStore.state.value=AdbToolStore.state.value.copy(mftPhase="Refreshing files…",message="Reading MFT report folder")
        val esc=root.replace("'","'\\''")
        val raw=commandForMft(
            context,
            "find '$esc' -type f 2>/dev/null | grep -E '/MFT-Reports-.*\\.(xls|xlsx)$' | sort -r | head -50",
            10_000,
            useUsbRef
        ).getOrElse{throw it}
        val files=raw.lineSequence().map{it.trim()}.filter{it.isNotBlank()}.distinct().toList()
        val selected=AdbToolStore.state.value.mftSelected.takeIf{it in files} ?: files.firstOrNull().orEmpty()
        AdbToolStore.state.value=AdbToolStore.state.value.copy(
            mftFiles=files,mftSelected=selected,mftPhase=if(files.isEmpty())"No reports found" else "${files.size} report(s)",
            message=if(files.isEmpty())"No MFT reports found" else "MFT file list refreshed"
        )
        files
    }.onFailure{e->
        AdbToolStore.state.value=AdbToolStore.state.value.copy(mftPhase="Refresh failed",message="MFT browser: ${e.message}")
    } }

    fun selectMftFile(path:String) {
        if(path in AdbToolStore.state.value.mftFiles) {
            AdbToolStore.state.value=AdbToolStore.state.value.copy(mftSelected=path)
        }
    }

    suspend fun pullMftFile(context:Context,remotePath:String,taskName:String,deleteRemoteAfterVerified:Boolean=false,useUsbRef:Boolean=false):Result<String> = withContext(Dispatchers.IO) { runCatching {
        require(remotePath.isNotBlank()){"Select an MFT report first"}
        ensureMftReady(context,"MFT pull",useUsbRef)
        val task=taskName.trim().replace(Regex("[^A-Za-z0-9._-]+"),"_").trim('_').ifBlank{"MFT_Report"}
        val ext=remotePath.substringAfterLast('.', "xls").lowercase(Locale.US).let{if(it=="xlsx")"xlsx" else "xls"}
        val outName="$task.$ext"
        AdbToolStore.state.value=AdbToolStore.state.value.copy(
            mftRunning=true,mftSource=remotePath,mftSavedPath="",mftCleanup="",mftPhase="Pulling…",
            message="Pulling ${remotePath.substringAfterLast('/')} via ${if(useUsbRef)"USB REF" else "Local DUT Wireless ADB"}"
        )
        val puller=AdbSyncPuller(context)
        if(useUsbRef) {
            puller.pullSingleFileUsb(remotePath,"CellTracker/MFT",outName){pr->
                AdbToolStore.state.value=AdbToolStore.state.value.copy(mftPhase=pr.phase,exportBytes=pr.bytesDone)
            }
        } else {
            puller.pullSingleFileTo(remotePath,"CellTracker/MFT",outName){pr->
                AdbToolStore.state.value=AdbToolStore.state.value.copy(mftPhase=pr.phase,exportBytes=pr.bytesDone)
            }
        }
        val saved="Download/CellTracker/MFT/$outName"
        var cleanup="Kept on device"
        if(deleteRemoteAfterVerified){
            val esc=remotePath.replace("'","'\\''")
            val result=commandForMft(context,"rm -f '$esc' && if [ -e '$esc' ]; then echo DELETE_FAILED; else echo DELETED; fi",8_000,useUsbRef).getOrNull().orEmpty()
            cleanup=if(result.contains("DELETED"))"Source deleted after verification" else "Cleanup failed · source kept"
        }
        AdbToolStore.state.value=AdbToolStore.state.value.copy(
            mftRunning=false,mftPhase="Completed",mftSavedPath=saved,mftCleanup=cleanup,message="MFT report ready"
        )
        saved
    }.onFailure{e->
        AdbToolStore.state.value=AdbToolStore.state.value.copy(mftRunning=false,mftPhase="Failed",message="MFT pull failed: ${e.message}")
    } }

    private suspend fun ensureLocalReady(context:Context,reason:String):String {
        AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Checking ADB before $reason…")
        val first=command(context,"id",4_000).getOrNull()
        if(first?.contains("uid=2000") == true){
            AdbToolStore.state.value=AdbToolStore.state.value.copy(localStatus="Connected",localIdentity=first.trim(),message="ADB ready")
            return first
        }
        AdbToolStore.state.value=AdbToolStore.state.value.copy(localStatus="Reconnecting",message="ADB session stale · rediscovering Wireless debugging…")
        val connected=connectLocal(context).getOrElse{throw IllegalStateException("ADB reconnect failed: ${it.message ?: it.javaClass.simpleName}")}
        val verified=command(context,"id",5_000).getOrElse{throw IllegalStateException("ADB reconnected but shell verification failed: ${it.message}")}
        check(verified.contains("uid=2000")){"ADB shell is not ready: $verified"}
        AdbToolStore.state.value=AdbToolStore.state.value.copy(localStatus="Connected",localIdentity=verified.trim(),message="ADB recovered · ready")
        return connected
    }

    suspend fun exportMftReport(context:Context,taskName:String,deleteRemoteAfterVerified:Boolean=true,useUsbRef:Boolean=false):Result<String> = withContext(Dispatchers.IO) {
        val files=refreshMftFiles(context,useUsbRef).getOrElse{return@withContext Result.failure(it)}
        val selected=AdbToolStore.state.value.mftSelected.ifBlank{files.firstOrNull().orEmpty()}
        if(selected.isBlank()) return@withContext Result.failure(IllegalStateException("No MFT report found"))
        pullMftFile(context,selected,taskName,deleteRemoteAfterVerified,useUsbRef)
    }

    suspend fun exportDebuglogger(context:Context,path:String="/data/debuglogger",logName:String="",compress:Boolean=false,deleteAfterZip:Boolean=false):Result<String> = withContext(Dispatchers.IO){ runCatching {
        check(exportJob?.isActive!=true){"An export is already running"}
        ensureLocalReady(context,"DUT log pull")
        val source=path.trim().ifBlank{"/data/debuglogger"};val started=System.currentTimeMillis();val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        val safe=logName.trim().replace(Regex("[^A-Za-z0-9._-]+"),"_").trim('_').ifBlank{"DUT_debuglogger"};val session="${safe}_$stamp";val folderPath="Download/CellTracker/Logs/DUT/$session/"
        AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=true,exportPhase="Starting ADB Sync pull…",exportBytes=0,exportFiles=0,exportFound=0,exportSkipped=0,exportStartedMs=started,exportPullMs=0,exportTotalMs=0,exportZipMs=0,exportPullBytes=0,exportDeleted=0,exportDeleteFailed=0,exportSymlinks=0,exportListFailed=0,exportPath="",exportResult="",exportError="")
        exportJob=scope.launch{try{val puller=AdbSyncPuller(context);val result=puller.pullTree(source,session){pr->AdbToolStore.state.value=AdbToolStore.state.value.copy(exportPhase=pr.phase,exportBytes=pr.bytesDone,exportFiles=pr.filesDone,exportFound=pr.found,exportSkipped=pr.skipped,message="Pulling ${pr.current}")}
            check(result.pulled>0){"No files were pulled from $source"}
            val pullDone=System.currentTimeMillis();val pullMs=pullDone-started;var finalPath=folderPath;var zipMs=0L;var deleted=0L;var deleteFailed=0L
            if(compress){
                AdbToolStore.state.value=AdbToolStore.state.value.copy(exportPhase="Compressing…",exportPullMs=pullMs,exportPullBytes=result.bytes,message="Preparing ZIP")
                val zipStart=System.currentTimeMillis()
                val zr=puller.compress(session,result,deleteAfterZip){msg->AdbToolStore.state.value=AdbToolStore.state.value.copy(exportPhase=if(msg.startsWith("Deleting"))"Cleaning source…" else "Compressing…",message=msg)}
                zipMs=System.currentTimeMillis()-zipStart;finalPath=zr.path;deleted=zr.deleted.toLong();deleteFailed=zr.deleteFailed.toLong()
            }
            val totalMs=System.currentTimeMillis()-started
            AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Completed",exportFiles=result.pulled,exportFound=result.found,exportSkipped=result.skipped,exportBytes=result.bytes,exportPullBytes=result.bytes,exportPullMs=pullMs,exportZipMs=zipMs,exportTotalMs=totalMs,exportDeleted=deleted,exportDeleteFailed=deleteFailed,exportSymlinks=result.symlinks,exportListFailed=result.listFailed,exportPath=finalPath,exportResult=if(deleteFailed>0)"SUCCESS · CLEANUP PARTIAL" else "SUCCESS",exportError="",message="Export completed")
        }catch(e:CancellationException){AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Cancelled",exportResult="CANCELLED",exportPath="")}catch(e:Throwable){AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Failed",exportResult="FAILED",exportPath="",exportError=e.message?:e.javaClass.simpleName)}};"Export started"
    }.onFailure{e->AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Failed",exportResult="FAILED",exportPath="",exportError=e.message?:e.javaClass.simpleName)} }
    fun cancelExport(){ exportJob?.cancel(); exportJob=null }

    fun startLogcat(context:Context,command:String="logcat -v threadtime",refLabel:String="vivo_REF") {
        if(logcatJob?.isActive==true)return
        logcatJob=scope.launch { runCatching {
            val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
            val safeLabel=refLabel.trim().ifBlank{"REF"}.replace(Regex("[^A-Za-z0-9._-]"),"_")
            val name="${safeLabel}_AP_Log_$stamp.txt"
            val publicPath="Download/CellTracker/Logs/$safeLabel/$name"
            val values=android.content.ContentValues().apply{
                put(MediaStore.Downloads.DISPLAY_NAME,name)
                put(MediaStore.Downloads.MIME_TYPE,"text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/$safeLabel")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val resolver=context.contentResolver
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create $publicPath")
            var bytes=0L
            try {
                AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=true,logcatBytes=0,logcatPath=publicPath,message="AP log recording")
                val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("shell:$command")
                resolver.openOutputStream(uri,"w")?.use { out ->
                    s.openInputStream().use { input ->
                        val b=ByteArray(32768)
                        while(isActive){
                            val n=input.read(b); if(n<0)break
                            if(n>0){out.write(b,0,n);out.flush();bytes+=n;AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatBytes=bytes)}
                        }
                    }
                } ?: error("Cannot open AP log destination")
            } finally {
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); resolver.update(uri,values,null,null)
            }
        }.onFailure{AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Logcat failed: ${it.message}")}
          .also{AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=false)}
        }
    }
    fun stopLogcat(){logcatJob?.cancel();logcatJob=null;AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=false,message="AP log stopped")}
}

class AdbPairCodeReceiver:BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        val code=RemoteInput.getResultsFromIntent(intent)?.getCharSequence("pair_code")?.toString().orEmpty().trim()
        if(code.isBlank()) return
        AdbToolStore.state.value=AdbToolStore.state.value.copy(localStatus="Pairing…",message="Pairing code received")
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { CellTrackerAdbEngine.pairLocal(context.applicationContext,code) }
            finally { pending.finish() }
        }
    }
}
