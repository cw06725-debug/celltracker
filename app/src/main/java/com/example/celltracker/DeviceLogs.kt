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
    val logcatRunning:Boolean=false, val logcatBytes:Long=0, val logcatPath:String="",
    val exportRunning:Boolean=false, val exportPhase:String="", val exportBytes:Long=0, val exportTotalBytes:Long=0,
    val exportFiles:Long=0, val exportFound:Long=0, val exportSkipped:Long=0, val exportStartedMs:Long=0,
    val exportPullMs:Long=0, val exportTotalMs:Long=0, val exportZipMs:Long=0, val exportPullBytes:Long=0,
    val exportDeleted:Long=0, val exportDeleteFailed:Long=0, val exportSymlinks:Long=0, val exportListFailed:Long=0,
    val exportPath:String="", val exportResult:String="", val exportError:String="",
    val refLabel:String="vivo_REF",
    val message:String=""
)
object AdbToolStore { val state=MutableStateFlow(AdbUiState()) }

object CellTrackerAdbEngine {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var logcatJob:Job?=null
    private var exportJob:Job?=null
    private var discoveredPair:AdbEndpoint?=null
    private var discoveredConnect:AdbEndpoint?=null

    fun startDiscovery(context:Context) {
        discover(context,"_adb-tls-pairing._tcp") { ep ->
            discoveredPair=ep; val old=AdbToolStore.state.value
            AdbToolStore.state.value=old.copy(localEndpoint=old.localEndpoint.copy(host=ep.host,pairingPort=ep.pairingPort), message="Pairing device found: ${ep.host}:${ep.pairingPort}")
            // Shizuku-style UX: as soon as Android exposes the temporary pairing service,
            // show a heads-up notification with inline RemoteInput while Settings stays open.
            pairingNotification(context,"CellTracker · Pairing device found","${ep.host}:${ep.pairingPort} · Expand notification and enter the 6-digit pairing code",true)
        }
        discover(context,"_adb-tls-connect._tcp") { ep ->
            discoveredConnect=ep; val old=AdbToolStore.state.value
            AdbToolStore.state.value=old.copy(localEndpoint=old.localEndpoint.copy(host=ep.host,connectPort=ep.connectPort))
        }
    }
    private fun discover(context:Context,type:String,onFound:(AdbEndpoint)->Unit) {
        val nsd=context.getSystemService(NsdManager::class.java)?:return
        runCatching { nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,object:NsdManager.DiscoveryListener{
            override fun onDiscoveryStarted(s:String){}; override fun onDiscoveryStopped(s:String){}
            override fun onStartDiscoveryFailed(s:String,e:Int){ AdbToolStore.state.value=AdbToolStore.state.value.copy(message="ADB discovery failed: $e") }; override fun onStopDiscoveryFailed(s:String,e:Int){}
            override fun onServiceLost(s:NsdServiceInfo){}
            override fun onServiceFound(s:NsdServiceInfo){ runCatching { nsd.resolveService(s,object:NsdManager.ResolveListener{
                override fun onResolveFailed(si:NsdServiceInfo,e:Int){ AdbToolStore.state.value=AdbToolStore.state.value.copy(message="ADB service resolve failed: $e") }
                override fun onServiceResolved(si:NsdServiceInfo){ val h=si.host?.hostAddress?:return; val p=si.port; onFound(if(type.contains("pairing")) AdbEndpoint(h,p,0) else AdbEndpoint(h,0,p)) }
            }) } }
        }) }
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

    private suspend fun discoverAndConnectLocal(context:Context, timeoutMs:Long=15_000):String {
        val mgr=CellTrackerAdbConnectionManager.getInstance(context)
        discoveredConnect=null
        val old=AdbToolStore.state.value
        AdbToolStore.state.value=old.copy(
            localEndpoint=old.localEndpoint.copy(connectPort=0),
            message="Discovering current ADB TLS service…"
        )
        startDiscovery(context)

        val deadline=System.currentTimeMillis()+timeoutMs
        var lastError="ADB TLS service not discovered"
        while(System.currentTimeMillis()<deadline){
            val ep=discoveredConnect
            if(ep!=null && ep.connectPort>0){
                AdbToolStore.state.value=AdbToolStore.state.value.copy(
                    message="Connecting ${ep.host}:${ep.connectPort}…"
                )
                val connected=runCatching{
                    withTimeout(5_000){mgr.connect(ep.host,ep.connectPort)}
                }.getOrElse{
                    lastError=it.message ?: it.javaClass.simpleName
                    false
                }
                if(connected){
                    val id=runCatching{
                        withTimeout(5_000){command(context,"id",5_000).getOrThrow().trim()}
                    }.getOrElse{
                        lastError=it.message ?: it.javaClass.simpleName
                        ""
                    }
                    if(id.contains("uid=2000")) return id
                    if(id.isNotBlank()) lastError="Identity is not shell: $id"
                } else {
                    lastError="Connect failed at ${ep.host}:${ep.connectPort}: $lastError"
                }
                // HiOS may rotate the connect service/port after pairing.
                discoveredConnect=null
                startDiscovery(context)
            }
            delay(250)
        }
        // Keep the existing manager fallback for ROMs where mDNS connect discovery is unavailable.
        val fallback=runCatching{withTimeout(5_000){mgr.connectTls(context,5_000)}}.getOrDefault(false)
        if(fallback){
            val id=command(context,"id",5_000).getOrThrow().trim()
            if(id.contains("uid=2000")) return id
        }
        error(lastError)
    }

    suspend fun pairLocal(context:Context,code:String):Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(code.trim().matches(Regex("\\d{6}"))){"Pairing code must be 6 digits"}
            pairingNotification(context,"CellTracker ADB pairing","Code received · discovering pairing service…",false)
            val ep=waitForPairEndpoint(context)
            val mgr=CellTrackerAdbConnectionManager.getInstance(context)
            AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Pairing ${ep.host}:${ep.pairingPort}…")
            pairingNotification(context,"CellTracker ADB pairing","Pairing with ${ep.host}:${ep.pairingPort}…",false)

            var pairWarning:String?=null
            val paired=try{
                withTimeout(15_000){mgr.pair(ep.host,ep.pairingPort,code.trim())}
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
            val id=discoverAndConnectLocal(context,18_000)
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
            delay(1800)
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
        }
    }

    suspend fun connectLocal(context:Context):Result<String> = withContext(Dispatchers.IO){
        runCatching {
            // Never trust the previous port: Wireless debugging ports can rotate after pairing,
            // especially on HiOS. Rediscover the current _adb-tls-connect._tcp endpoint.
            val id=discoverAndConnectLocal(context,15_000)
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
    }
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

    suspend fun exportDebuglogger(context:Context,path:String="/data/debuglogger",logName:String="",compress:Boolean=false,deleteAfterZip:Boolean=false):Result<String> = withContext(Dispatchers.IO){ runCatching {
        check(exportJob?.isActive!=true){"An export is already running"}
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
