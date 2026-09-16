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
    val logcatRunning:Boolean=false, val logcatBytes:Long=0, val logcatPath:String="", val message:String=""
)
object AdbToolStore { val state=MutableStateFlow(AdbUiState()) }

object CellTrackerAdbEngine {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var logcatJob:Job?=null
    private var discoveredPair:AdbEndpoint?=null
    private var discoveredConnect:AdbEndpoint?=null

    fun startDiscovery(context:Context) {
        discover(context,"_adb-tls-pairing._tcp") { ep ->
            discoveredPair=ep; val old=AdbToolStore.state.value
            AdbToolStore.state.value=old.copy(localEndpoint=old.localEndpoint.copy(host=ep.host,pairingPort=ep.pairingPort), message="Pairing device found: ${ep.host}:${ep.pairingPort}")
            // Shizuku-style UX: as soon as Android exposes the temporary pairing service,
            // show a heads-up notification with inline RemoteInput while Settings stays open.
            pairingNotification(context,"CellTracker · Pairing device found","${ep.host}:${ep.pairingPort} · Enter the 6-digit pairing code",true)
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
        val b=NotificationCompat.Builder(context,"adb_pair")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title).setContentText(text)
            .setOngoing(allowInput).setOnlyAlertOnce(false).setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if(allowInput){
            val ri=RemoteInput.Builder("pair_code").setLabel("6-digit pairing code").build()
            val pi=PendingIntent.getBroadcast(context,8801,Intent(context,AdbPairCodeReceiver::class.java).setAction("PAIR_CODE"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_send,"ENTER PAIRING CODE",pi).addRemoteInput(ri).build())
        }
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
    suspend fun command(context:Context,cmd:String):Result<String> = withContext(Dispatchers.IO){ runCatching {
        val stream=CellTrackerAdbConnectionManager.getInstance(context).openStream("shell:${cmd.trim()}")
        stream.openInputStream().bufferedReader().use{it.readText()}
    } }
    suspend fun exportDebuglogger(context:Context,path:String="/data/debuglogger"):Result<String> = withContext(Dispatchers.IO){ runCatching {
        command(context,"test -r '$path' && echo READABLE || echo DENIED").getOrThrow().also{check(it.contains("READABLE")){"ADB shell cannot read $path"}}
        val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date()); val name="DUT_debuglogger_$stamp.tar.gz"
        val values=android.content.ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,name);put(MediaStore.Downloads.MIME_TYPE,"application/gzip");put(MediaStore.Downloads.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Logs/DUT");put(MediaStore.Downloads.IS_PENDING,1)}
        val r=context.contentResolver; val uri=r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create output")
        try { r.openOutputStream(uri)?.use{out-> val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("shell:toybox tar -czf - '$path'"); s.openInputStream().use{it.copyTo(out)}}?:error("Cannot open output"); values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);r.update(uri,values,null,null) } catch(t:Throwable){r.delete(uri,null,null);throw t}
        "Download/CellTracker/Logs/DUT/$name"
    } }
    fun startLogcat(context:Context,command:String="logcat -v threadtime") {
        if(logcatJob?.isActive==true)return
        logcatJob=scope.launch { runCatching {
            val dir=File(context.getExternalFilesDir(null),"adb_logs").apply{mkdirs()}; val stamp=SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date()); val f=File(dir,"REF_AP_log_$stamp.txt")
            AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=true,logcatBytes=0,logcatPath=f.absolutePath,message="AP log recording")
            val s=CellTrackerAdbConnectionManager.getInstance(context).openStream("shell:$command")
            s.openInputStream().use{input->FileOutputStream(f).use{out->val b=ByteArray(32768);while(isActive){val n=input.read(b);if(n<0)break;out.write(b,0,n);AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatBytes=f.length())}}}
        }.onFailure{AdbToolStore.state.value=AdbToolStore.state.value.copy(message="Logcat failed: ${it.message}")}; AdbToolStore.state.value=AdbToolStore.state.value.copy(logcatRunning=false) }
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
