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
                append("""<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="1" width="25" customWidth="1"/><col min="2" max="2" width="34" customWidth="1"/><col min="3" max="5" width="26" customWidth="1"/>""")
                // Raw epoch values are retained for machine analysis, but hidden in human-facing Excel views.
                if (sheet.first == "Lag Events") append("""<col min="6" max="7" hidden="1"/>""")
                if (sheet.first == "Review Audit") append("""<col min="6" max="6" width="20" customWidth="1"/><col min="7" max="10" hidden="1"/>""")
                append("</cols><sheetData>")
                sheet.second.forEachIndexed { rowIndex, row ->
                    val hiddenEpochRow = sheet.first == "Summary" && row.firstOrNull() in listOf("Test Start (ms)", "Test End (ms)")
                    append("<row r=\"${rowIndex+1}\"${if (hiddenEpochRow) " hidden=\"1\"" else ""}>")
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
             pci: String, rsrp: String, sinr: String, appVersion: String, origin: String = "MANUAL_STALL_TAP", reviewAudit: List<List<String>> = emptyList()): List<Uri> {
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
        val readableClock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        fun readable(ms: Long) = readableClock.format(Date(ms))
        val summary = listOf(listOf("Field","Value"), listOf("Scenario",kind.label), listOf("Task",task),listOf("Device Role",role), listOf("Device Model",device), listOf("App Version",appVersion),listOf("Test Start (ms)",startMs.toString()),listOf("Test Start",readable(startMs)),listOf("Test End (ms)",endMs.toString()),listOf("Test End",readable(endMs)),listOf("Test Duration (s)",String.format(Locale.US,"%.3f",durationMs/1000.0)),listOf("Stall Count",events.size.toString()),listOf("Lag Count",events.size.toString()),listOf("Total Stall Duration (s)",String.format(Locale.US,"%.3f",totalMs/1000.0)),listOf("Stall Ratio (%)",String.format(Locale.US,"%.3f",totalMs*100.0/durationMs)),listOf("Lag Ratio (%)",String.format(Locale.US,"%.3f",totalMs*100.0/durationMs)),listOf("Operator",operator),listOf("RAT",rat), listOf("Band",band),listOf("PCI",pci),listOf("RSRP",rsrp),listOf("SINR",sinr),listOf("Source",origin))
        val raw = listOf(listOf("Event", "T0", "T1", "Duration (s)", "Duration (ms)", "StartMs", "EndMs")) + events.mapIndexed { i,e -> listOf((i+1).toString(),readable(e.startMs),readable(e.endMs),String.format(Locale.US,"%.3f",e.durationMs/1000.0),e.durationMs.toString(),e.startMs.toString(),e.endMs.toString()) }
        val auditSheets = if (reviewAudit.isEmpty()) emptyList() else listOf("Review Audit" to
            (listOf(listOf("Event", "Original T0", "Original T1", "Reviewed T0", "Reviewed T1", "Status", "Original T0 ms", "Original T1 ms", "Reviewed T0 ms", "Reviewed T1 ms")) + reviewAudit.map { cells ->
                listOf(cells.getOrNull(0).orEmpty()) + listOf(1,2,3,4).map { i -> cells.getOrNull(i)?.toLongOrNull()?.let(::readable).orEmpty() } + listOf(cells.getOrNull(5).orEmpty()) + listOf(1,2,3,4).map { i -> cells.getOrNull(i).orEmpty() }
            }))
        val xlsx = workbook(listOf("Summary" to summary, "Lag Events" to raw) + auditSheets)
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
    /** Delete only the selected report pair, not the linked MP4 or unrelated test data. */
    fun delete(context: Context, stored: Stored): Boolean {
        val base = stored.name.removeSuffix(".xlsx")
        val parent = runCatching {
            context.contentResolver.query(stored.uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: return false
        if (!base.startsWith("Stall_") || !parent.contains("/ManualStall")) return false
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH)
        val targets = mutableListOf<Uri>()
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1).orEmpty()
                val folder = cursor.getString(2).orEmpty()
                if (folder == parent && (name == "$base.xlsx" || name == "$base.csv")) {
                    targets += android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
            }
        }
        if (targets.none { it == stored.uri }) return false
        return targets.all { runCatching { context.contentResolver.delete(it, null, null) > 0 }.getOrDefault(false) }
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
        const val EXTRA_OWN_RECORDING="ownRecording"
        const val EXTRA_OPERATOR="operator";const val EXTRA_RAT="rat";const val EXTRA_BAND="band";const val EXTRA_PCI="pci";const val EXTRA_RSRP="rsrp";const val EXTRA_SINR="sinr";const val EXTRA_VERSION="version"
        fun command(context:Context,action:String) { context.startService(Intent(context,ManualStallServiceV130::class.java).setAction(action)) }
    }
    private var kind=ManualStallKindV130.LIVE;private var role="DUT1";private var task=""
    private var operator="";private var rat="";private var band="";private var pci="";private var rsrp="";private var sinr="";private var version=""
    private var startMs=0L;private var startedElapsed=0L;private var openAtMs:Long?=null;private var openElapsed:Long?=null
    private val events=mutableListOf<ManualStallEventV130>()
    private val work=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var saving=false
    private var ownsScreenRecording=false
    private var wm:WindowManager?=null;private var overlay:View?=null;private var params:WindowManager.LayoutParams?=null
    private var statusText:TextView?=null;private var clockText:TextView?=null;private var toggleButton:Button?=null
    private val handler=Handler(Looper.getMainLooper())
    private val ticker=object:Runnable {override fun run(){ drawStatus(); if(startMs>0)handler.postDelayed(this,40L) }}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        try {
            when(intent?.action) {
                ACTION_START -> if(startMs==0L) {
                    if(!android.provider.Settings.canDrawOverlays(this)) { ManualStallStatusV130.state.value=ManualStallProgressV130(message="Overlay permission required"); stopSelf();return START_NOT_STICKY }
                    kind=runCatching { ManualStallKindV130.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }.getOrDefault(ManualStallKindV130.LIVE)
                    role=intent.getStringExtra(EXTRA_ROLE).orEmpty().ifBlank { "DUT1" }; task=intent.getStringExtra(EXTRA_TASK).orEmpty().ifBlank{kind.label}
                    operator=intent.getStringExtra(EXTRA_OPERATOR).orEmpty();rat=intent.getStringExtra(EXTRA_RAT).orEmpty();band=intent.getStringExtra(EXTRA_BAND).orEmpty();pci=intent.getStringExtra(EXTRA_PCI).orEmpty();rsrp=intent.getStringExtra(EXTRA_RSRP).orEmpty();sinr=intent.getStringExtra(EXTRA_SINR).orEmpty();version=intent.getStringExtra(EXTRA_VERSION).orEmpty()
                    ownsScreenRecording=intent.getBooleanExtra(EXTRA_OWN_RECORDING,false)
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
                    if (ownsScreenRecording) {
                        startService(Intent(this,TestScreenRecordingService::class.java).apply { action=TestScreenRecordingService.STOP })
                        ownsScreenRecording=false
                    }
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
                ACTION_CANCEL -> if(!saving) {
                    if (ownsScreenRecording) {
                        startService(Intent(this,TestScreenRecordingService::class.java).apply { action=TestScreenRecordingService.STOP })
                        ownsScreenRecording=false
                    }
                    publish("Test cancelled; no report saved",running=false);close() }
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
        val elapsed=(SystemClock.elapsedRealtime()-startedElapsed).coerceAtLeast(0L)
        val sum=events.sumOf{it.durationMs} + if(openElapsed!=null)(SystemClock.elapsedRealtime()-openElapsed!!).coerceAtLeast(0L) else 0L
        // Show the actual local wall clock separately from monotonic recording duration.
        // The elapsed clock is monotonic so a network time correction cannot change test duration.
        clockText?.text=SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(Date(System.currentTimeMillis()))
        statusText?.text="REC ${elapsed/60000}:${((elapsed/1000)%60).toString().padStart(2,'0')}.${(elapsed%1000).toString().padStart(3,'0')}  ·  Lag ${events.size} · ${String.format(Locale.US,"%.3f",sum/1000.0)} s"
        toggleButton?.text=if(openAtMs!=null)"END" else "START"
    }
    private fun addOverlay() {
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val density=resources.displayMetrics.density
        fun px(value:Int)=(value*density+0.5f).toInt()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(px(10),px(8),px(10),px(8));background=OverlayStyleV130.card(this@ManualStallServiceV130,.94f) }
        val header=TextView(this).apply {text="⋮⋮  CellTracker · Move";textSize=12f;setTextColor(OverlayStyleV130.secondaryTextColor(this@ManualStallServiceV130));setPadding(px(3),0,px(3),px(5))}
        val clock=TextView(this).apply {textSize=22f;setSingleLine(true);typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL);setTextColor(OverlayStyleV130.textColor(this@ManualStallServiceV130));setPadding(px(3),0,px(3),px(3))};clockText=clock
        val text=TextView(this).apply {textSize=12f;setSingleLine(true);setTextColor(OverlayStyleV130.textColor(this@ManualStallServiceV130));setPadding(px(3),0,px(3),px(7))};statusText=text
        val toggle=Button(this).apply {this.text="START";this.isAllCaps=false;this.textSize=12f;this.setSingleLine(true);OverlayStyleV130.button(this@ManualStallServiceV130,this);setOnClickListener { command(this@ManualStallServiceV130,ACTION_TOGGLE) }};toggleButton=toggle
        val finish=Button(this).apply{this.text="FINISH";this.isAllCaps=false;this.textSize=12f;this.setSingleLine(true);OverlayStyleV130.button(this@ManualStallServiceV130,this,true)}
        val confirmPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;visibility=View.GONE }
        val confirmText=TextView(this).apply {this.text="Finish this test? Recording will stop.";textSize=12f;setTextColor(OverlayStyleV130.textColor(this@ManualStallServiceV130))}
        val cancel=Button(this).apply {this.text="CANCEL";isAllCaps=false;setSingleLine(true);OverlayStyleV130.button(this@ManualStallServiceV130,this);setOnClickListener{confirmPanel.visibility=View.GONE}}
        val confirm=Button(this).apply {this.text="CONFIRM";isAllCaps=false;setSingleLine(true);OverlayStyleV130.button(this@ManualStallServiceV130,this,true);setOnClickListener{confirmPanel.visibility=View.GONE;command(this@ManualStallServiceV130,ACTION_FINISH)}}
        val confirmButtons=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;addView(cancel,LinearLayout.LayoutParams(0,px(44),1f));addView(confirm,LinearLayout.LayoutParams(0,px(44),1f))}
        confirmPanel.addView(confirmText);confirmPanel.addView(confirmButtons)
        finish.setOnClickListener{confirmText.text=if(openAtMs!=null)"A stall is still open and will be excluded. Finish test?" else "Finish this test? Recording will stop.";confirmPanel.visibility=View.VISIBLE}
        val buttons=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;addView(toggle,LinearLayout.LayoutParams(0,px(44),1f));addView(View(this@ManualStallServiceV130),LinearLayout.LayoutParams(px(12),px(1)));addView(finish,LinearLayout.LayoutParams(0,px(44),1f))}
        root.addView(header);root.addView(clock);root.addView(text);root.addView(buttons);root.addView(confirmPanel)
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
    var showFinishConfirmation by remember { mutableStateOf(false) }
    val active=progress.running && progress.kind==kind
    val scopeReady=android.provider.Settings.canDrawOverlays(context)
    var permissionReady by remember {mutableStateOf(scopeReady)}
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){
        permissionReady=android.provider.Settings.canDrawOverlays(context)
    }
    var recordingOnly by rememberSaveable(kind) { mutableStateOf(false) }
    var recordWithOverlay by rememberSaveable(kind) { mutableStateOf(true) }
    var pendingOverlayStart by remember { mutableStateOf(false) }
    var videoActive by remember { mutableStateOf(TestScreenRecordingService.isRecording) }
    var lastVideoAvailable by remember { mutableStateOf(RecordingTimelineV130.last(context) != null) }
    val sessionPrefs = remember(context) { context.getSharedPreferences("ct_external_recording_session", Context.MODE_PRIVATE) }
    var externalStarted by remember { mutableLongStateOf(sessionPrefs.getLong("start_${kind.name}", 0L)) }
    var externalEnded by remember { mutableLongStateOf(sessionPrefs.getLong("end_${kind.name}", 0L)) }
    var externalCandidate by remember { mutableStateOf<ExternalRecordingV130.Candidate?>(null) }
    var externalTime by remember { mutableStateOf("") }
    var externalStatus by remember { mutableStateOf("") }
    var nearbyVideos by remember { mutableStateOf<List<ExternalRecordingV130.NearbyVideo>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }
    val mainScope=rememberCoroutineScope()
    fun searchVideos() {
        if(externalStarted<=0L || externalEnded<=externalStarted) {
            externalStatus="Start and finish a test window to find matching videos."
        } else {
            searchBusy=true
            mainScope.launch {
                val found=withContext(Dispatchers.IO) {runCatching {ExternalRecordingV130.nearbyVideos(context,externalStarted,externalEnded)} }
                searchBusy=false
                found.onSuccess { nearbyVideos=it;externalStatus="${it.size} possible videos; verify file and timestamp before import." }
                    .onFailure {externalStatus="Video search failed: ${it.message}. Use SELECT SYSTEM VIDEO instead."}
            }
        }
    }
    val videoSearchPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted)searchVideos() else externalStatus="Media permission denied; use SELECT SYSTEM VIDEO instead."
    }
    val externalPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            mainScope.launch {
                externalStatus="Reading video metadata…"
                val found=withContext(Dispatchers.IO) {runCatching {
                    context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ExternalRecordingV130.read(context,uri,externalStarted,externalEnded)
                }}
                found.onSuccess { candidate ->
                    externalCandidate=candidate
                    externalTime=candidate.suggestedStartMs?.let(ExternalRecordingV130::formatLocalTime).orEmpty()
                    externalStatus="${candidate.source} · ${candidate.durationMs/1000}s · verify start before saving"
                }.onFailure { externalStatus="Cannot read selected video: ${it.message}" }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            videoActive = TestScreenRecordingService.isRecording
            lastVideoAvailable = RecordingTimelineV130.last(context) != null
            delay(900L)
        }
    }
    val startOverlayHolder = remember { arrayOf<(Boolean)->Unit>({}) }
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
            if (pendingOverlayStart) {
                pendingOverlayStart=false
                // The overlay test owns this recording and stops it on FINISH/CANCEL.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startOverlayHolder[0](true)
                    ManualStallReportV130.openOrInstall(context,kind)
                }, 650L)
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    ManualStallReportV130.openOrInstall(context,kind)
                }, 850L)
            }
        } else {
            if (pendingOverlayStart) {
                pendingOverlayStart=false
                Toast.makeText(context,"Screen recording not authorized; manual overlay not started",Toast.LENGTH_LONG).show()
            } else Toast.makeText(context,"Recording permission cancelled",Toast.LENGTH_SHORT).show()
        }
    }
    val cell=liveState.sims.firstOrNull { it.subscriptionId==liveState.selectedSubscriptionId }?.servingCell ?: liveState.sims.firstOrNull()?.servingCell
    startOverlayHolder[0] = overlayStart@{ ownsRecording ->
        if(!permissionReady) {
            permissionLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")))
            return@overlayStart
        }
        val intent=Intent(context,ManualStallServiceV130::class.java).apply {
            action=ManualStallServiceV130.ACTION_START
            putExtra(ManualStallServiceV130.EXTRA_KIND,kind.name);putExtra(ManualStallServiceV130.EXTRA_ROLE,role);putExtra(ManualStallServiceV130.EXTRA_TASK,task.trim())
            putExtra(ManualStallServiceV130.EXTRA_OPERATOR,cell?.operator.orEmpty());putExtra(ManualStallServiceV130.EXTRA_RAT,cell?.displayRat.orEmpty());putExtra(ManualStallServiceV130.EXTRA_BAND,cell?.band.orEmpty());putExtra(ManualStallServiceV130.EXTRA_PCI,cell?.pci.orEmpty());putExtra(ManualStallServiceV130.EXTRA_RSRP,cell?.rsrp.orEmpty());putExtra(ManualStallServiceV130.EXTRA_SINR,cell?.sinr.orEmpty())
            putExtra(ManualStallServiceV130.EXTRA_VERSION,version.orEmpty())
            putExtra(ManualStallServiceV130.EXTRA_OWN_RECORDING,ownsRecording)
        }
        ContextCompat.startForegroundService(context,intent)
    }
    fun startManualTest() {
        if (!permissionReady) {
            permissionLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}")))
            return
        }
        if (recordWithOverlay) {
            pendingOverlayStart=true
            val mgr=context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
            videoConsent.launch(mgr.createScreenCaptureIntent())
        } else {
            startOverlayHolder[0](false)
            ManualStallReportV130.openOrInstall(context,kind)
        }
    }
    if(showFinishConfirmation) AlertDialog(
        onDismissRequest={showFinishConfirmation=false},
        title={Text("Finish ${kind.application} test?")},
        text={Text(if(progress.stallStartedMs!=null) "The current stall has not ended and will be excluded. Stop and save?" else "Stop the test and save its report?")},
        confirmButton={TextButton(onClick={showFinishConfirmation=false;ManualStallServiceV130.command(context,ManualStallServiceV130.ACTION_FINISH)}){Text("FINISH TEST")}},
        dismissButton={TextButton(onClick={showFinishConfirmation=false}){Text("CANCEL")}}
    )
    Scaffold(topBar={TopAppBar(title={Text(kind.label)},navigationIcon={TextButton(onClick=onBack){Text("‹ Back")}})}) { pad ->
        Column(Modifier.fillMaxSize().background(bunnyScreenBrush()).padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Manual Data Experience Test",style=MaterialTheme.typography.titleLarge)
            Text("Manual overlay can record the screen and mark stalls at the same time. Video-only mode allows post-test review without a floating window.",style=MaterialTheme.typography.bodySmall)
            Card(shape=RoundedCornerShape(25.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {Column(Modifier.background(gradientCardBrushV130()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(task,{task=it},label={Text("Task name")},singleLine=true,enabled=!active,modifier=Modifier.fillMaxWidth())
                Text("Device role",style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf("DUT1","DUT2","REF1","REF2").forEach { item -> FilterChip(selected=role==item,onClick={role=item},enabled=!active,label={Text(item)}) }
                }
                Text("Target app: ${kind.application} · ${version?.let{"v$it"}?:"Not installed"}",style=MaterialTheme.typography.bodySmall)
                GradientActionV130(if(version==null)"INSTALL ${kind.application}" else "OPEN ${kind.application}", {ManualStallReportV130.openOrInstall(context,kind);version=ManualStallReportV130.installed(context,kind)?.second}, Modifier.fillMaxWidth(),primary=false)
                Text("Capture mode",style=MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=!recordingOnly,onClick={recordingOnly=false},enabled=!active && !videoActive,label={Text("Manual overlay")})
                    FilterChip(selected=recordingOnly,onClick={recordingOnly=true},enabled=!active && !videoActive,label={Text("Video only · no overlay")})
                }
                if (recordingOnly) {
                    Text("A system consent screen is required for each recording. No floating window is shown; stop from the ongoing notification or by returning here. Timeline is an estimate, not frame-exact.",style=MaterialTheme.typography.bodySmall)
                    if (videoActive) {
                        GradientActionV130("STOP RECORDING & SAVE", {context.startService(Intent(context,TestScreenRecordingService::class.java).apply{action=TestScreenRecordingService.STOP})},Modifier.fillMaxWidth())
                    } else {
                        GradientActionV130("START RECORDING", {
                            val mgr=context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
                            videoConsent.launch(mgr.createScreenCaptureIntent())
                        },Modifier.fillMaxWidth(),enabled=!active && task.isNotBlank())
                    }
                    HorizontalDivider()
                    Text("System / OEM screen recorder", style=MaterialTheme.typography.titleMedium)
                    Text("Use this if the phone cannot record through CellTracker. Record the test window, then select its MP4 and confirm the video start time.",style=MaterialTheme.typography.bodySmall)
                    if(externalStarted==0L || externalEnded>0L) {
                        GradientActionV130("START EXTERNAL TEST WINDOW", {
                            externalStarted=System.currentTimeMillis();externalEnded=0L
                            sessionPrefs.edit().putLong("start_${kind.name}",externalStarted).putLong("end_${kind.name}",0L).apply()
                            externalStatus="Session clock started. Start system screen recording and the target app."
                            ManualStallReportV130.openOrInstall(context,kind)
                        },Modifier.fillMaxWidth(),primary=false)
                    } else {
                        GradientActionV130("END EXTERNAL TEST WINDOW", {
                            externalEnded=System.currentTimeMillis()
                            sessionPrefs.edit().putLong("end_${kind.name}",externalEnded).apply()
                            externalStatus="Test window saved; choose the recorded video."
                        },Modifier.fillMaxWidth(),primary=false)
                    }
                    GradientActionV130(if(searchBusy)"SEARCHING VIDEOS…" else "FIND MATCHING RECORDINGS", {
                        val permission=if(Build.VERSION.SDK_INT>=33) android.Manifest.permission.READ_MEDIA_VIDEO
                            else android.Manifest.permission.READ_EXTERNAL_STORAGE
                        if(ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED) searchVideos()
                        else videoSearchPermission.launch(permission)
                    },Modifier.fillMaxWidth(),primary=false,enabled=!searchBusy)
                    nearbyVideos.forEach { item ->
                        GradientActionV130("${item.name.take(23)} · ${item.durationMs/1000}s", {
                            mainScope.launch {
                                val found=withContext(Dispatchers.IO) {runCatching {ExternalRecordingV130.read(context,item.uri,externalStarted,externalEnded)}}
                                found.onSuccess {candidate->
                                    externalCandidate=candidate
                                    externalTime=ExternalRecordingV130.formatLocalTime(item.approximateStartMs)
                                    externalStatus="${item.reason} · confirm video start manually before saving"
                                }.onFailure{externalStatus="Cannot read video: ${it.message}"}
                            }
                        },Modifier.fillMaxWidth(),primary=false)
                    }
                    GradientActionV130("SELECT SYSTEM VIDEO", { externalPicker.launch(arrayOf("video/*")) },Modifier.fillMaxWidth(),primary=false)
                    externalCandidate?.let { candidate ->
                        Text("Video: ${candidate.durationMs/1000}s · ${candidate.source}",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value=externalTime,onValueChange={externalTime=it},
                            label={Text("Video first-frame time · yyyy-MM-dd HH:mm:ss")},
                            supportingText={Text("Estimated. Metadata may refer to file modification; verify manually.")},
                            singleLine=true,modifier=Modifier.fillMaxWidth())
                        GradientActionV130("CONFIRM TIME & CREATE TIMELINE", {
                            val startMs=ExternalRecordingV130.parseLocalTime(externalTime)
                            if(startMs==null) externalStatus="Enter a valid start time (yyyy-MM-dd HH:mm:ss)"
                            else {
                                val snapshot=cell
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                    val result=runCatching {ExternalRecordingV130.save(context,candidate,startMs,kind,role,task,version.orEmpty(),
                                        snapshot?.operator.orEmpty(), snapshot?.displayRat.orEmpty(), snapshot?.band.orEmpty(),
                                        snapshot?.pci.orEmpty(), snapshot?.rsrp.orEmpty(), snapshot?.sinr.orEmpty())}
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        result.onSuccess {lastVideoAvailable=true;externalStatus="External time map created · ESTIMATED. Open Review to mark stalls." }
                                            .onFailure {externalStatus="Video import failed: ${it.message}"}
                                    }
                                }
                            }
                        },Modifier.fillMaxWidth(),primary=true)
                    }
                    if(externalStatus.isNotBlank()) Text(externalStatus,style=MaterialTheme.typography.bodySmall)
                    Text("After recording, open Reports → ${kind.label} → Latest recording to review the video and mark stalls. The original MP4, CSV and SRT remain unchanged.",style=MaterialTheme.typography.bodySmall)
                } else {
                    Row(modifier=Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        androidx.compose.material3.Checkbox(checked=recordWithOverlay,onCheckedChange={recordWithOverlay=it},enabled=!active && !videoActive)
                        Column(modifier=Modifier.weight(1f)) {
                            Text("Auto Screen Recording",style=MaterialTheme.typography.titleSmall)
                            Text(if(recordWithOverlay) "ON · MP4 and manual Lag markers recorded together" else "OFF · manual Lag markers only",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("FINISH stops the test and any CellTracker recording after confirmation.",style=MaterialTheme.typography.bodySmall)
                    if(!permissionReady) Text("Overlay permission required to mark stalls while using ${kind.application}.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                    GradientActionV130(if(permissionReady)"START MANUAL STALL TEST" else "GRANT OVERLAY PERMISSION", {startManualTest()},Modifier.fillMaxWidth(),enabled=!active && task.isNotBlank() && !videoActive && !ManualStallStatusV130.state.value.running)
                }
            }}
            if(active) Card(shape=RoundedCornerShape(25.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {Column(Modifier.background(gradientCardBrushV130()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Current test · ${kind.application}",style=MaterialTheme.typography.titleMedium)
                Text("Completed stalls: ${progress.count} · Total ${String.format(Locale.US,"%.2f",progress.totalMs/1000.0)} s")
                Text(if(progress.stallStartedMs!=null)"STALL IN PROGRESS" else "Waiting for a stall",color=MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Button(onClick={ManualStallServiceV130.command(context,ManualStallServiceV130.ACTION_TOGGLE)},modifier=Modifier.weight(1f)){Text(if(progress.stallStartedMs==null)"START STALL" else "END STALL")}
                    OutlinedButton(onClick={showFinishConfirmation=true},modifier=Modifier.weight(1f)){Text("FINISH")}
                }
                Text("Use the floating controls during playback; avoid keeping the test screen foreground.",style=MaterialTheme.typography.bodySmall)
            }}
            Text(progress.message,style=MaterialTheme.typography.bodySmall)
            if(progress.lastReport.isNotBlank() && progress.kind==kind) Text("Saved to Downloads/CellTracker · CSV + XLSX",style=MaterialTheme.typography.bodySmall)
            Text("Saved test summaries and recording review are available under Reports → ${kind.label}.",
                style=MaterialTheme.typography.bodySmall)

        }
    }
}
