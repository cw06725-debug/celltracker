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
    val exportFiles:Long=0, val exportStartedMs:Long=0, val exportPath:String="", val exportResult:String="", val exportError:String="",
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
            pairingNotification(context,"CellTracker · Pairing device found","${ep.host}:${ep.pairingPort} · Tap to enter the 6-digit code in CellTracker",true)
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
    private fun pairingNotification(context:Context, title:String, text:String, allowInput:Boolean) {
        val nm=context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("adb_pair","ADB pairing",NotificationManager.IMPORTANCE_HIGH))
        val launch=context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPi=launch?.let{PendingIntent.getActivity(context,8802,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val b=NotificationCompat.Builder(context,"adb_pair")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(!allowInput).setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if(contentPi!=null) b.setContentIntent(contentPi)
        nm.notify(8801,b.build())
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

    suspend fun pairLocal(context:Context,code:String):Result<String> = withContext(Dispatchers.IO) { runCatching {
        require(code.trim().matches(Regex("\\d{6}"))){"Pairing code must be 6 digits"}
        pairingNotification(context,"CellTracker ADB pairing","Code received · discovering pairing service…",false)
        val ep=waitForPairEndpoint(context)
        AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Pairing ${ep.host}:${ep.pairingPort}…")
        pairingNotification(context,"CellTracker ADB pairing","Pairing with ${ep.host}:${ep.pairingPort}…",false)

        val mgr=CellTrackerAdbConnectionManager.getInstance(context)
        val paired=withTimeout(15_000){ mgr.pair(ep.host,ep.pairingPort,code.trim()) }
        check(paired){"ADB pairing rejected"}

        pairingNotification(context,"CellTracker ADB paired","Pairing succeeded · discovering ADB connection…",false)
        AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Paired · discovering ADB connection…")
        discoveredConnect=null
        startDiscovery(context)
        val connectEp=waitForConnectEndpoint(10_000)
        val ok=withTimeout(12_000){
            if(connectEp!=null) mgr.connect(connectEp.host,connectEp.connectPort)
            else mgr.connectTls(context,8_000)
        }
        check(ok){"Paired, but ADB TLS connection failed"}

        val id=withTimeout(8_000){ command(context,"id").getOrThrow().trim() }
        check(id.contains("uid=2000")){"Connected, but identity is not shell: $id"}
        AdbToolStore.state.value=AdbToolStore.state.value.copy(
            localStatus="Connected", localIdentity=id, message="Local ADB ready"
        )
        pairingNotification(context,"CellTracker Local ADB connected","Connected as uid=2000(shell)",false)
        delay(1800)
        context.getSystemService(NotificationManager::class.java).cancel(8801)
        "Connected as shell"
    }.onFailure { e ->
        val msg=when(e){
            is TimeoutCancellationException -> "Pairing timed out. Reopen the pairing-code dialog and retry."
            else -> e.message ?: e.javaClass.simpleName
        }
        AdbToolStore.state.value=AdbToolStore.state.value.copy(localStatus="Not connected",message="Pair failed: $msg")
        pairingNotification(context,"CellTracker ADB pairing failed",msg,false)
    } }

    suspend fun connectLocal(context:Context):Result<String> = withContext(Dispatchers.IO){ runCatching {
        val mgr=CellTrackerAdbConnectionManager.getInstance(context)
        val ep=discoveredConnect
        val ok= if(ep!=null && ep.connectPort>0) mgr.connect(ep.host,ep.connectPort) else mgr.connectTls(context,5000)
        check(ok){"ADB TLS connection failed"}; val id=command(context,"id").getOrThrow().trim(); check(id.contains("uid=2000")){"Connected, but shell identity is not uid=2000: $id"}
        AdbToolStore.state.value=AdbToolStore.state.value.copy(localStatus="Connected",localIdentity=id,message="Local ADB ready")
        id
    } }
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

    suspend fun exportDebuglogger(context:Context,path:String="/data/debuglogger"):Result<String> = withContext(Dispatchers.IO){ runCatching {
        check(exportJob?.isActive!=true){"An export is already running"}
        val source=path.trim().ifBlank{"/data/debuglogger"}
        val started=System.currentTimeMillis()
        val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date())
        val session="debuglogger_$stamp"
        val publicPath="Download/CellTracker/Logs/DUT/$session/"
        AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=true,exportPhase="Starting ADB Sync pull…",exportBytes=0,exportFiles=0,exportStartedMs=started,exportPath="",exportResult="",exportError="",message="Opening sync: service")
        exportJob=scope.launch{
            try{
                val result=AdbSyncPuller(context).pullTree(source,session){pr->
                    AdbToolStore.state.value=AdbToolStore.state.value.copy(exportPhase="Pulling files…",exportBytes=pr.bytesDone,exportFiles=pr.filesDone,message="Pulling ${pr.current}")
                }
                check(result.first>0){"No files were pulled from $source"}
                AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Completed",exportFiles=result.first,exportBytes=result.second,exportPath=publicPath,exportResult="SUCCESS",exportError="",message="ADB Sync pull completed")
            }catch(e:CancellationException){
                AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Cancelled",exportPath="",exportResult="CANCELLED",exportError="",message="Export cancelled")
            }catch(e:Throwable){
                AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Failed",exportPath="",exportResult="FAILED",exportError=e.message?:e.javaClass.simpleName,message="ADB Sync pull failed")
            }
        }
        "ADB Sync pull started"
    }.onFailure{e->AdbToolStore.state.value=AdbToolStore.state.value.copy(exportRunning=false,exportPhase="Failed",exportPath="",exportResult="FAILED",exportError=e.message?:e.javaClass.simpleName,message="Export failed")} }
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
