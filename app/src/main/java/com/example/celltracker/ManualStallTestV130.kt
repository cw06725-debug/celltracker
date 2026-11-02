package com.example.celltracker

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Same event-timing/report pipeline for Pakistani Tamasha live video and PUBG Mobile. */
enum class ManualStallKindV130(val label: String, val application: String, val packages: List<String>) {
    LIVE("Tamasha Live Streaming", "Tamasha", listOf("com.spbtv.mobilinktv")),
    GAME("PUBG Gaming", "PUBG MOBILE", listOf("com.tencent.ig", "com.pubg.krmobile", "com.vng.pubgmobile"))
}

data class ManualStallEventV130(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class ManualStallProgressV130(
    val running: Boolean = false, val kind: ManualStallKindV130 = ManualStallKindV130.LIVE,
    val count: Int = 0, val totalMs: Long = 0L, val stallStartedMs: Long? = null,
    val message: String = "Ready", val lastReport: String = ""
)

object ManualStallStatusV130 {
    val state = MutableStateFlow(ManualStallProgressV130())
}

/** File naming and CSV schema are shared; the original test events remain traceable. */
object ManualStallReportV130 {
    data class Stored(val uri: Uri, val name: String, val timestamp: Long)
    fun installed(context: Context, kind: ManualStallKindV130): Pair<String,String>? = kind.packages.firstNotNullOfOrNull { name ->
        runCatching { name to (context.packageManager.getPackageInfo(name, 0).versionName ?: "Unknown") }.getOrNull()
    }
    fun openOrInstall(context: Context, kind: ManualStallKindV130) {
        val installed = installed(context, kind)
        if (installed != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(installed.first)
            if (intent != null) { context.startActivity(intent); return }
        }
        val pkg = kind.packages.first()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun workbook(sheets: List<Pair<String,List<List<String>>>>): ByteArray {
        val files = mutableListOf<Pair<String,String>>()
        files += "[Content_Types].xml" to buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
            sheets.indices.forEach { append("<Override PartName=\"/xl/worksheets/sheet${it+1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>") }
            append("</Types>")
        }
        files += "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        files += "xl/workbook.xml" to buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
            sheets.forEachIndexed { i, s -> append("<sheet name=\"${xml(s.first)}\" sheetId=\"${i+1}\" r:id=\"rId${i+1}\"/>") }
            append("</sheets></workbook>")
        }
        files += "xl/_rels/workbook.xml.rels" to buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            sheets.indices.forEach { append("<Relationship Id=\"rId${it+1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it+1}.xml\"/>") }
            append("</Relationships>")
        }
        sheets.forEachIndexed { index, sheet ->
            files += "xl/worksheets/sheet${index+1}.xml" to buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
                sheet.second.forEachIndexed { rowIndex, row ->
                    append("<row r=\"${rowIndex+1}\">")
                    row.forEachIndexed { col, cell ->
                        var n = col+1; var key = ""
                        while (n > 0) { key = ('A'.code + (n-1)%26).toChar() + key; n=(n-1)/26 }
                        val address = "$key${rowIndex+1}"
                        if (rowIndex > 0 && cell.toDoubleOrNull()?.isFinite() == true) append("<c r=\"$address\"><v>$cell</v></c>")
                        else append("<c r=\"$address\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(cell)}</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData></worksheet>")
            }
        }
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            files.forEach { (name,content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        } }.toByteArray()
    }
    fun save(context: Context, kind: ManualStallKindV130, task: String, role: String, startMs: Long,
             endMs: Long, events: List<ManualStallEventV130>, operator: String, rat: String, band: String,
             pci: String, rsrp: String, sinr: String, appVersion: String, origin: String = "MANUAL_STALL_TAP"): List<Uri> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startMs))
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startMs))
        val cleanTask = task.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "Test" }
        val base = "Stall_${kind.name}_${role}_${cleanTask}_$stamp"
        val totalMs = events.sumOf { it.durationMs }
        val durationMs = (endMs-startMs).coerceAtLeast(1L)
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        // One summary line and individual event lines in the same immutable CSV.
        val headers = listOf("RecordType", "TestType", "Task", "Role", "StartMs", "EndMs", "DurationMs", "StallCount", "TotalStallMs", "StallRatioPct", "Operator", "RAT", "Band", "PCI", "RSRP", "SINR", "DeviceModel", "AppVersion", "Origin")
        fun row(type: String, eventStart: Long, eventEnd: Long, interval: Long, count: Int, total: Long) = listOf(type,kind.name,task,role,eventStart.toString(),eventEnd.toString(),interval.toString(),count.toString(),total.toString(),
            String.format(Locale.US,"%.3f",100.0*total/durationMs),operator,rat,band,pci,rsrp,sinr,device,appVersion,origin)
        val rows = listOf(headers, row("SUMMARY",startMs,endMs,durationMs,events.size,totalMs)) + events.map { row("EVENT",it.startMs,it.endMs,it.durationMs,1,it.durationMs) }
        val csv = rows.joinToString("\n") { it.joinToString(",", transform=::csvCell) }.toByteArray(Charsets.UTF_8)
        val summary = listOf(listOf("Field","Value"), listOf("Scenario",kind.label), listOf("Task",task),listOf("Device Role",role), listOf("Device Model",device), listOf("App Version",appVersion),listOf("Test Start (ms)",startMs.toString()),listOf("Test End (ms)",endMs.toString()),listOf("Test Duration (s)",String.format(Locale.US,"%.3f",durationMs/1000.0)),listOf("Stall Count",events.size.toString()),listOf("Total Stall Duration (s)",String.format(Locale.US,"%.3f",totalMs/1000.0)),listOf("Stall Ratio (%)",String.format(Locale.US,"%.3f",totalMs*100.0/durationMs)),listOf("Operator",operator),listOf("RAT",rat), listOf("Band",band),listOf("PCI",pci),listOf("RSRP",rsrp),listOf("SINR",sinr),listOf("Source",origin))
        val raw = listOf(listOf("Event","StartMs","EndMs","DurationMs","DurationSec")) + events.mapIndexed { i,e -> listOf((i+1).toString(),e.startMs.toString(),e.endMs.toString(),e.durationMs.toString(),String.format(Locale.US,"%.3f",e.durationMs/1000.0)) }
        val xlsx = workbook(listOf("Summary" to summary, "Stall Events" to raw))
        val content = listOf(Triple("$base.csv","text/csv",csv), Triple("$base.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",xlsx))
        val result = mutableListOf<Uri>()
        try {
            for ((name,mime,bytes) in content) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name); put(MediaStore.MediaColumns.MIME_TYPE,mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH,"${android.os.Environment.DIRECTORY_DOWNLOADS}/CellTracker/$date/ManualStall")
                    if(Build.VERSION.SDK_INT>=29) put(MediaStore.MediaColumns.IS_PENDING,1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("MediaStore insert failed")
                result += uri
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write $name")
                if(Build.VERSION.SDK_INT>=29) context.contentResolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            }
        } catch(e:Exception) { result.forEach { runCatching { context.contentResolver.delete(it,null,null) } };throw e }
        return result
    }
    fun history(context: Context, kind: ManualStallKindV130? = null): List<Stored> {
        val found = mutableListOf<Stored>()
        val projection = arrayOf(MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.DATE_ADDED,MediaStore.Downloads.RELATIVE_PATH)
        runCatching { context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,null,null,"${MediaStore.Downloads.DATE_ADDED} DESC")?.use { cur ->
            while(cur.moveToNext()) {
                val name = cur.getString(1).orEmpty();val path = cur.getString(3).orEmpty()
                if(!name.startsWith("Stall_") || !name.endsWith(".xlsx") || !path.contains("/ManualStall") || kind?.let { !name.startsWith("Stall_${it.name}_") } == true) continue
                found += Stored(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cur.getLong(0)),name,cur.getLong(2)*1000L)
            }
        } }; return found
    }
}

/** A foreground service is required so the floating controls survive launching the target app. */
class ManualStallServiceV130 : Service() {
    companion object {
        const val ACTION_START="com.example.celltracker.stall.START"
        const val ACTION_TOGGLE="com.example.celltracker.stall.TOGGLE"
        const val ACTION_FINISH="com.example.celltracker.stall.FINISH"
        const val ACTION_CANCEL="com.example.celltracker.stall.CANCEL"
        const val EXTRA_KIND="kind";const val EXTRA_ROLE="role";const val EXTRA_TASK="task"
        const val EXTRA_OPERATOR="operator";const val EXTRA_RAT="rat";const val EXTRA_BAND="band";const val EXTRA_PCI="pci";const val EXTRA_RSRP="rsrp";const val EXTRA_SINR="sinr";const val EXTRA_VERSION="version"
        fun command(context:Context,action:String) { context.startService(Intent(context,ManualStallServiceV130::class.java).setAction(action)) }
    }
    private var kind=ManualStallKindV130.LIVE;private var role="DUT1";private var task=""
    private var operator="";private var rat="";private var band="";private var pci="";private var rsrp="";private var sinr="";private var version=""
    private var startMs=0L;private var startedElapsed=0L;private var openAtMs:Long?=null;private var openElapsed:Long?=null
    private val events=mutableListOf<ManualStallEventV130>()
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var saving=false
    private var wm:WindowManager?=null;private var overlay:View?=null;private var params:WindowManager.LayoutParams?=null
    private var statusText:TextView?=null;private var toggleButton:Button?=null
    private val handler=Handler(Looper.getMainLooper())
    private val ticker=object:Runnable {override fun run(){ drawStatus(); if(startMs>0)handler.postDelayed(this,1000L) }}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        try {
            when(intent?.action) {
                ACTION_START -> if(startMs==0L) {
                    if(!android.provider.Settings.canDrawOverlays(this)) { ManualStallStatusV130.state.value=ManualStallProgressV130(message="Overlay permission required"); stopSelf();return START_NOT_STICKY }
                    kind=runCatching { ManualStallKindV130.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrDefault(ManualStallKindV130.LIVE)
                    role=intent.getStringExtra(EXTRA_ROLE).orEmpty().ifBlank { "DUT1" }; task=intent.getStringExtra(EXTRA_TASK).orEmpty().ifBlank{kind.label}
                    operator=intent.getStringExtra(EXTRA_OPERATOR).orEmpty();rat=intent.getStringExtra(EXTRA_RAT).orEmpty();band=intent.getStringExtra(EXTRA_BAND).orEmpty();pci=intent.getStringExtra(EXTRA_PCI).orEmpty();rsrp=intent.getStringExtra(EXTRA_RSRP).orEmpty();sinr=intent.getStringExtra(EXTRA_SINR).orEmpty();version=intent.getStringExtra(EXTRA_VERSION).orEmpty()
                    startMs=System.currentTimeMillis();startedElapsed=SystemClock.elapsedRealtime()
                    showNotification();addOverlay();publish("Recording · use START STALL when buffering begins")
                    handler.post(ticker)
                }
                ACTION_TOGGLE -> if(startMs>0L && !saving) {
                    val now = System.currentTimeMillis(); val elapsed=SystemClock.elapsedRealtime()
                    if(openAtMs==null){openAtMs=now;openElapsed=elapsed;publish("Stall in progress")}
                    else{ val duration=(elapsed-(openElapsed?:elapsed)).coerceAtLeast(0L);events+=ManualStallEventV130(openAtMs!!,openAtMs!!+duration);openAtMs=null;openElapsed=null;publish("Stall #${events.size} saved") }
                    drawStatus()
                }
                ACTION_FINISH -> if(startMs>0L && !saving) {
                    saving=true
                    // An unfinished interval is never silently counted as a valid stall.
                    val discarded=openAtMs!=null;openAtMs=null;openElapsed=null
                    val actualEnd=System.currentTimeMillis()
                    val finalized=events.toList()
                    publish("Saving CSV + Excel…")
                    work.launch {
                        val result=withContext(Dispatchers.IO) {
                            runCatching { ManualStallReportV130.save(this@ManualStallServiceV130,kind,task,role,startMs,actualEnd,
                                finalized,operator,rat,band,pci,rsrp,sinr,version) }
                        }
                        if(result.isSuccess) {
                            publish(if(discarded)"Report saved · unfinished stall excluded" else "Report saved",
                                running=false,lastReport=result.getOrNull()?.lastOrNull()?.toString().orEmpty())
                            close()
                        } else {
                            saving=false
                            publish("Report save failed: ${result.exceptionOrNull()?.message}")
                        }
                    }
                }
                ACTION_CANCEL -> if(!saving) { publish("Test cancelled; no report saved",running=false);close() }
            }
        } catch (e:Exception) { publish("Error: ${e.message}"); if(startMs>0L)drawStatus() }
        return START_NOT_STICKY
    }
    private fun showNotification() {
        val channel="celltracker.manualstall.v130"
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel,"Manual stall tests",NotificationManager.IMPORTANCE_LOW))
        val pending = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("CellTracker · ${kind.label}").setContentText("Manual stall recording active")
            .setOngoing(true).setContentIntent(pending).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(39201,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(39201,notification)
    }
    private fun publish(message:String,running:Boolean=true,lastReport:String="") {
        ManualStallStatusV130.state.value=ManualStallProgressV130(running,kind,events.size,events.sumOf{it.durationMs},openAtMs,message,lastReport)
    }
    private fun drawStatus() {
        val elapsed=(SystemClock.elapsedRealtime()-startedElapsed).coerceAtLeast(0L)/1000L
        val sum=events.sumOf{it.durationMs} + if(openElapsed!=null)(SystemClock.elapsedRealtime()-openElapsed!!).coerceAtLeast(0L) else 0L
        statusText?.text="${kind.application}  ${elapsed/60}:${(elapsed%60).toString().padStart(2,'0')}\nStalls ${events.size} · ${String.format(Locale.US,"%.1f",sum/1000.0)}s"
        toggleButton?.text=if(openAtMs!=null)"END" else "START"
    }
    private fun addOverlay() {
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val density=resources.displayMetrics.density
        fun px(value:Int)=(value*density+0.5f).toInt()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(px(10),px(8),px(10),px(8));background=OverlayStyleV130.card(this@ManualStallServiceV130,.94f) }
        val header=TextView(this).apply {text="⋮⋮  CellTracker · Move";textSize=12f;setTextColor(OverlayStyleV130.secondaryTextColor(this@ManualStallServiceV130));setPadding(px(3),0,px(3),px(5))}
        val text=TextView(this).apply {textSize=13f;setTextColor(OverlayStyleV130.textColor(this@ManualStallServiceV130));setPadding(px(3),0,px(3),px(7))};statusText=text
        val toggle=Button(this).apply {this.text="START";this.isAllCaps=false;this.textSize=12f;this.setSingleLine(true);OverlayStyleV130.button(this@ManualStallServiceV130,this);setOnClickListener { command(this@ManualStallServiceV130,ACTION_TOGGLE) }};toggleButton=toggle
        val finish=Button(this).apply{this.text="FINISH";this.isAllCaps=false;this.textSize=12f;this.setSingleLine(true);OverlayStyleV130.button(this@ManualStallServiceV130,this,true);setOnClickListener{command(this@ManualStallServiceV130,ACTION_FINISH)}}
        val buttons=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;addView(toggle,LinearLayout.LayoutParams(0,px(44),1f));addView(finish,LinearLayout.LayoutParams(0,px(44),1f))}
        root.addView(header);root.addView(text);root.addView(buttons)
        val lp=WindowManager.LayoutParams(px(285),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {gravity=Gravity.TOP or Gravity.START;x=px(15);y=px(110)}
        var initialX=0;var initialY=0;var downX=0f;var downY=0f
        header.setOnTouchListener { _,event -> when(event.actionMasked){
            MotionEvent.ACTION_DOWN -> {initialX=lp.x;initialY=lp.y;downX=event.rawX;downY=event.rawY;true}
            MotionEvent.ACTION_MOVE -> {lp.x=(initialX+(event.rawX-downX)).toInt().coerceAtLeast(0);lp.y=(initialY+(event.rawY-downY)).toInt().coerceAtLeast(0);runCatching{wm?.updateViewLayout(root,lp)};true}
            MotionEvent.ACTION_UP -> true
            else -> false
        } }
        wm?.addView(root,lp);overlay=root;params=lp;drawStatus()
    }
    private fun close() {
        handler.removeCallbacks(ticker); overlay?.let{runCatching{wm?.removeView(it)}};overlay=null;startMs=0L
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
    }
    override fun onDestroy() {
        handler.removeCallbacks(ticker);overlay?.let{runCatching{wm?.removeView(it)}};overlay=null
        if(startMs>0L)publish("Recording interrupted: no report finalized",running=false)
        work.cancel()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualStallScreenV130(kind: ManualStallKindV130, liveState: AppState, onBack: () -> Unit) {
    val context=LocalContext.current
    var task by rememberSaveable(kind){mutableStateOf(if(kind==ManualStallKindV130.GAME)"PUBG Gameplay" else "Tamasha Live")}
    var role by rememberSaveable(kind){mutableStateOf("DUT1")}
    var version by remember(kind){mutableStateOf(ManualStallReportV130.installed(context,kind)?.second)}
    var history by remember(kind){mutableStateOf(ManualStallReportV130.history(context,kind))}
    val progress by ManualStallStatusV130.state.collectAsState()
    val active=progress.running && progress.kind==kind
    val scopeReady=android.provider.Settings.canDrawOverlays(context)
    var permissionReady by remember {mutableStateOf(scopeReady)}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
        permissionReady=android.provider.Settings.canDrawOverlays(context)
    }
    var recordingOnly by rememberSaveable(kind) { mutableStateOf(false) }
    var videoActive by remember { mutableStateOf(TestScreenRecordingService.isRecording) }
    var lastVideoAvailable by remember { mutableStateOf(RecordingTimelineV130.last(context) != null) }
    LaunchedEffect(Unit) {
        while (true) {
            videoActive = TestScreenRecordingService.isRecording
            lastVideoAvailable = RecordingTimelineV130.last(context) != null
            delay(900L)
        }
    }
    val videoConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val captureCell=liveState.sims.firstOrNull { it.subscriptionId==liveState.selectedSubscriptionId }?.servingCell
                ?: liveState.sims.firstOrNull()?.servingCell
            ContextCompat.startForegroundService(context, Intent(context,TestScreenRecordingService::class.java).apply {
                action=TestScreenRecordingService.START
                putExtra(TestScreenRecordingService.CODE,result.resultCode)
                putExtra(TestScreenRecordingService.DATA,data)
                putExtra(TestScreenRecordingService.NAME,"${kind.name}_${role}_${task.trim()}")
                putExtra(TestScreenRecordingService.REVIEW_KIND,kind.name)
                putExtra(TestScreenRecordingService.REVIEW_ROLE,role)
                putExtra(TestScreenRecordingService.REVIEW_TASK,task.trim())
                putExtra(TestScreenRecordingService.REVIEW_VERSION,version.orEmpty())
                putExtra(TestScreenRecordingService.REVIEW_OPERATOR,captureCell?.operator.orEmpty())
                putExtra(TestScreenRecordingService.REVIEW_RAT,captureCell?.displayRat.orEmpty())
                putExtra(TestScreenRecordingService.REVIEW_BAND,captureCell?.band.orEmpty())
                putExtra(TestScreenRecordingService.REVIEW_PCI,captureCell?.pci.orEmpty())
                putExtra(TestScreenRecordingService.REVIEW_RSRP,captureCell?.rsrp.orEmpty())
                putExtra(TestScreenRecordingService.REVIEW_SINR,captureCell?.sinr.orEmpty())
            })
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ManualStallReportV130.openOrInstall(context, kind)
            }, 850L)
        } else Toast.makeText(context,"Recording permission cancelled",Toast.LENGTH_SHORT).show()
    }
    val cell=liveState.sims.firstOrNull { it.subscriptionId==liveState.selectedSubscriptionId }?.servingCell ?: liveState.sims.firstOrNull()?.servingCell
    fun start() {
        if(!permissionReady) {
            permissionLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")))
            return
        }
        val intent=Intent(context,ManualStallServiceV130::class.java).apply {
            action=ManualStallServiceV130.ACTION_START
            putExtra(ManualStallServiceV130.EXTRA_KIND,kind.name);putExtra(ManualStallServiceV130.EXTRA_ROLE,role);putExtra(ManualStallServiceV130.EXTRA_TASK,task.trim())
            putExtra(ManualStallServiceV130.EXTRA_OPERATOR,cell?.operator.orEmpty());putExtra(ManualStallServiceV130.EXTRA_RAT,cell?.displayRat.orEmpty());putExtra(ManualStallServiceV130.EXTRA_BAND,cell?.band.orEmpty());putExtra(ManualStallServiceV130.EXTRA_PCI,cell?.pci.orEmpty());putExtra(ManualStallServiceV130.EXTRA_RSRP,cell?.rsrp.orEmpty());putExtra(ManualStallServiceV130.EXTRA_SINR,cell?.sinr.orEmpty())
            putExtra(ManualStallServiceV130.EXTRA_VERSION,version.orEmpty())
        }
        ContextCompat.startForegroundService(context,intent)
    }
    Scaffold(topBar={TopAppBar(title={Text(kind.label)},navigationIcon={TextButton(onClick=onBack){Text("‹ Back")}})}) { pad ->
        Column(Modifier.fillMaxSize().background(bunnyScreenBrush()).padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Manual Data Experience Test",style=MaterialTheme.typography.titleLarge)
            Text("Choose manual stall markers, or recording-only mode without an overlay. Video-only mode creates an MP4 + per-second wall-clock timeline; it does not claim to detect stalls automatically.",style=MaterialTheme.typography.bodySmall)
            Card(shape=RoundedCornerShape(25.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(task,{task=it},label={Text("Task name")},singleLine=true,enabled=!active,modifier=Modifier.fillMaxWidth())
                Text("Device role",style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("DUT1","DUT2","REF1","REF2").forEach { item -> FilterChip(selected=role==item,onClick={role=item},enabled=!active,label={Text(item)}) }
                }
                Text("Target app: ${kind.application} · ${version?.let{"v$it"}?:"Not installed"}",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={ManualStallReportV130.openOrInstall(context,kind);version=ManualStallReportV130.installed(context,kind)?.second},modifier=Modifier.fillMaxWidth()) {Text(if(version==null)"Install ${kind.application}" else "Open ${kind.application}")}
                Text("Capture mode",style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=!recordingOnly,onClick={recordingOnly=false},enabled=!active && !videoActive,label={Text("Manual overlay")})
                    FilterChip(selected=recordingOnly,onClick={recordingOnly=true},enabled=!active && !videoActive,label={Text("Video only · no overlay")})
                }
                if (recordingOnly) {
                    Text("A system consent screen is required for each recording. No floating window is shown; stop from the ongoing notification or by returning here. Timeline is an estimate, not frame-exact.",style=MaterialTheme.typography.bodySmall)
                    if (videoActive) {
                        Button(onClick={context.startService(Intent(context,TestScreenRecordingService::class.java).apply{action=TestScreenRecordingService.STOP})},modifier=Modifier.fillMaxWidth()) { Text("STOP RECORDING & SAVE TIME MAP") }
                    } else {
                        Button(onClick={
                            val mgr=context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                            videoConsent.launch(mgr.createScreenCaptureIntent())
                        },enabled=!active && task.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("START VIDEO RECORDING (NO OVERLAY)") }
                    }
                    if(lastVideoAvailable) OutlinedButton(onClick={RecordingTimelinePlayerV130.open(context)},modifier=Modifier.fillMaxWidth()) {Text("REVIEW LAST VIDEO WITH REAL-TIME CLOCK")}
                    Text("After recording, open Review to mark stalls on the video timeline. Export creates a manual-reviewed CSV + XLSX without displaying an overlay during gameplay. CSV and SRT remain alongside the MP4.",style=MaterialTheme.typography.bodySmall)
                } else {
                    if(!permissionReady) Text("Overlay permission required to mark stalls while using ${kind.application}.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    Button(onClick={start()},enabled=!active && task.isNotBlank() && !videoActive && !ManualStallStatusV130.state.value.running,modifier=Modifier.fillMaxWidth()) {Text(if(permissionReady)"START TEST & SHOW FLOATING CONTROLS" else "GRANT OVERLAY PERMISSION")}
                }
            }}
            if(active) Card(shape=RoundedCornerShape(25.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Current test · ${kind.application}",style=MaterialTheme.typography.titleMedium)
                Text("Completed stalls: ${progress.count} · Total ${String.format(Locale.US,"%.2f",progress.totalMs/1000.0)} s")
                Text(if(progress.stallStartedMs!=null)"STALL IN PROGRESS" else "Waiting for a stall",color=MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Button(onClick={ManualStallServiceV130.command(context,ManualStallServiceV130.ACTION_TOGGLE)},modifier=Modifier.weight(1f)){Text(if(progress.stallStartedMs==null)"START STALL" else "END STALL")}
                    OutlinedButton(onClick={ManualStallServiceV130.command(context,ManualStallServiceV130.ACTION_FINISH)},modifier=Modifier.weight(1f)){Text("FINISH")}
                }
                Text("Use the floating controls during playback; avoid keeping the test screen foreground.",style=MaterialTheme.typography.bodySmall)
            }}
            Text(progress.message,style=MaterialTheme.typography.bodySmall)
            if(progress.lastReport.isNotBlank() && progress.kind==kind) Text("Saved to Downloads/CellTracker · CSV + XLSX",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                Text("Saved reports",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                TextButton(onClick={history=ManualStallReportV130.history(context,kind)}){Text("Refresh")}
            }
            history.take(20).forEach { row ->
                OutlinedButton(onClick={val intent=Intent(Intent.ACTION_VIEW).apply{setDataAndType(row.uri,"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)};runCatching{context.startActivity(intent)}.onFailure{Toast.makeText(context,"No XLSX viewer installed",Toast.LENGTH_SHORT).show()}},modifier=Modifier.fillMaxWidth()){
                    Text(row.name,maxLines=2)
                }
            }
        }
    }
}
