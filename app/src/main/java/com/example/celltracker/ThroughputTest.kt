package com.example.celltracker

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.content.ComponentName
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Speedtest by Ookla is a third-party application. Values are user-confirmed,
 * never synthesized or extracted from inaccessible private third-party data.
 */
data class SpeedtestRoundV130(
    val timeMs: Long, val attempt: Int, val dlMbps: Double, val ulMbps: Double,
    val operator: String, val rat: String, val band: String, val pci: String,
    val rsrp: String, val sinr: String, val transport: String, val recordingUri: String = ""
)

object SpeedtestReportV130 {
    val packages = listOf("org.zwanoo.android.speedtest", "org.zwanoo.android.speedtest.gworld")
    fun installed(context: Context): Pair<String, String>? = packages.firstNotNullOfOrNull { pkg ->
        runCatching {
            val info = context.packageManager.getPackageInfo(pkg, 0)
            pkg to (info.versionName ?: "unknown")
        }.getOrNull()
    }
    fun launch(context: Context): Boolean {
        val installed = installed(context) ?: return false
        val launch = context.packageManager.getLaunchIntentForPackage(installed.first) ?: return false
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }
    fun install(context: Context) {
        val pkg = packages.first()
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
        runCatching { context.startActivity(market) }.onFailure { context.startActivity(web) }
    }
    private fun safeText(value: String) = value.replace(Regex("[,\\r\\n]"), " ").trim().take(100)
    private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun sheet(rows: List<List<String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        rows.forEachIndexed { r, cols ->
            append("<row r=\"${r + 1}\">")
            cols.forEachIndexed { c, raw ->
                var col = c + 1; var key = ""
                while (col > 0) { key = ('A'.code + (col - 1) % 26).toChar() + key; col = (col - 1) / 26 }
                val addr = "$key${r + 1}"
                val value = xml(raw)
                if (r != 0 && c in setOf(0,4,5,6) && raw.toDoubleOrNull() != null)
                    append("<c r=\"$addr\"><v>$value</v></c>")
                else append("<c r=\"$addr\" t=\"inlineStr\"><is><t xml:space=\"preserve\">$value</t></is></c>")
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    private fun xlsx(rows: List<List<String>>): ByteArray {
        val contentTypes = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
        val rels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
        val book = """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Speedtest Results" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        val bookRels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
        val files = listOf("[Content_Types].xml" to contentTypes, "_rels/.rels" to rels,
            "xl/workbook.xml" to book, "xl/_rels/workbook.xml.rels" to bookRels, "xl/worksheets/sheet1.xml" to sheet(rows))
        return ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            for ((path, data) in files) { zip.putNextEntry(ZipEntry(path)); zip.write(data.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        } }.toByteArray()
    }
    fun save(context: Context, task: String, role: String, rounds: List<SpeedtestRoundV130>, version: String): List<Uri> {
        require(rounds.isNotEmpty()) { "No completed rounds" }
        val safeTask = safeText(task).replace(' ', '_').replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifBlank { "Speedtest" }
        val whenMs = rounds.first().timeMs
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(whenMs))
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(whenMs))
        val headers = listOf("TimestampMs","Date","Task","Role","Attempt","DownloadMbps","UploadMbps","Operator","RAT","Band","PCI","RSRP","SINR","Transport","SpeedtestVersion","Origin","EvidenceUri")
        val rows = listOf(headers) + rounds.map { value -> listOf(value.timeMs.toString(),date,safeTask,role,value.attempt.toString(),
            String.format(Locale.US,"%.3f",value.dlMbps),String.format(Locale.US,"%.3f",value.ulMbps),
            safeText(value.operator),safeText(value.rat),safeText(value.band),safeText(value.pci),safeText(value.rsrp),safeText(value.sinr),safeText(value.transport),safeText(version),"MANUAL_CONFIRMED_SPEEDTEST",safeText(value.recordingUri)) }
        val fileName = "Speedtest_${safeTask}_${role}_$stamp"
        val outputs = listOf(Triple("$fileName.csv", "text/csv", rows.joinToString("\n") { it.joinToString(",") }.toByteArray(Charsets.UTF_8)),
            Triple("$fileName.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(rows)))
        val result = mutableListOf<Uri>()
        try {
            for ((name, mime, bytes) in outputs) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CellTracker/$date/Speedtest")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not save Speedtest report")
                result += uri
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write report")
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return result
        } catch (e: Exception) { result.forEach { context.contentResolver.delete(it, null, null) }; throw e }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThroughputScreen(onBack: () -> Unit, liveState: AppState,
    onStartNetworkRecording: (String) -> Unit, onStopNetworkRecording: () -> Unit,
    setupOnly: Boolean = false, onSetupFinished: () -> Unit = {}) {
    val context = LocalContext.current
    var showSetup by remember { mutableStateOf(true) }
    var task by rememberSaveable { mutableStateOf("Speedtest") }
    var role by rememberSaveable { mutableStateOf("DUT1") }
    var countText by rememberSaveable { mutableStateOf("3") }
    var recordScreen by rememberSaveable { mutableStateOf(true) }
    var autoReadUi by rememberSaveable { mutableStateOf(true) }
    val candidate by SpeedtestCaptureStoreV130.result.collectAsStateWithLifecycle()
    var dl by remember { mutableStateOf("") }
    var ul by remember { mutableStateOf("") }
    val rounds = remember { mutableStateListOf<SpeedtestRoundV130>() }
    var status by remember { mutableStateOf("Ready") }
    var output by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var version by remember { mutableStateOf(SpeedtestReportV130.installed(context)?.second) }
    var captureStarted by remember { mutableStateOf(false) }
    var ownsNetworkRecording by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ContextCompat.startForegroundService(context, Intent(context, TestScreenRecordingService::class.java).apply {
                action = TestScreenRecordingService.START
                putExtra(TestScreenRecordingService.CODE, result.resultCode)
                putExtra(TestScreenRecordingService.DATA, result.data)
                putExtra(TestScreenRecordingService.NAME, "${task}_${role}_Speedtest")
            })
            captureStarted = true
        } else status = "Screen recording declined. Results can still be saved without video."
        if (!SpeedtestReportV130.launch(context)) status = "Open Speedtest or install it before the first round"
    }
    val projectionManager = remember { context.getSystemService(MediaProjectionManager::class.java) }
    val activeCaptureState = rememberUpdatedState(autoReadUi)
    val activeRecording = rememberUpdatedState(captureStarted)
    val activeNetworkRecording = rememberUpdatedState(ownsNetworkRecording)
    val stopNetworkCallback = rememberUpdatedState(onStopNetworkRecording)
    DisposableEffect(Unit) {
        onDispose {
            if (activeCaptureState.value) SpeedtestCaptureStoreV130.stop()
            if (activeRecording.value) runCatching { context.startService(Intent(context, TestScreenRecordingService::class.java).apply { action = TestScreenRecordingService.STOP }) }
            if (activeNetworkRecording.value) runCatching { stopNetworkCallback.value() }
        }
    }
    if (!setupOnly) {
        Scaffold(topBar = { TopAppBar(title = { Text("Speedtest · DL / UL") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val installed = SpeedtestReportV130.installed(context)
                Text("Speedtest by Ookla · ${installed?.second?.let { "v$it" } ?: "Not installed"}")
                Text("Run Speedtest in the third-party app, then return to confirm its displayed DL/UL result. No unverified readings are invented.", style = MaterialTheme.typography.bodySmall)
                if (installed == null) Button(onClick = { SpeedtestReportV130.install(context) }, modifier = Modifier.fillMaxWidth()) { Text("INSTALL SPEEDTEST") }
                else OutlinedButton(onClick = {
                    if (autoReadUi) SpeedtestCaptureStoreV130.nextRound()
                    SpeedtestReportV130.launch(context)
                }, modifier = Modifier.fillMaxWidth()) { Text("OPEN SPEEDTEST") }
                if (autoReadUi) {
                    val auto = candidate?.takeIf { System.currentTimeMillis() - it.at < 300_000L }
                    if (auto != null && rounds.size < (countText.toIntOrNull() ?: 3)) {
                        Text("Speedtest UI candidate: DL ${auto.download} / UL ${auto.upload} Mbps. Check against the final result before using.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            dl = String.format(Locale.US, "%.3f", auto.download)
                            ul = String.format(Locale.US, "%.3f", auto.upload)
                        }) { Text("USE DETECTED VALUES (VERIFY)") }
                    }
                }
                val maxRounds = (countText.toIntOrNull() ?: 3).coerceIn(1,10)
                Text("Task: $task · Role: $role · Completed ${rounds.size}/$maxRounds", style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (rounds.size < maxRounds) {
                    OutlinedTextField(dl, { dl = it.filter { c -> c.isDigit() || c == '.' }.take(14) }, label = { Text("Download (Mbps) · round ${rounds.size + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(ul, { ul = it.filter { c -> c.isDigit() || c == '.' }.take(14) }, label = { Text("Upload (Mbps) · round ${rounds.size + 1}") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = dl.toDoubleOrNull()?.let { it.isFinite() && it >= 0 && it <= 100000 } == true &&
                        ul.toDoubleOrNull()?.let { it.isFinite() && it >= 0 && it <= 100000 } == true,
                        modifier = Modifier.fillMaxWidth(), onClick = {
                            val sim = liveState.sims.firstOrNull { it.subscriptionId == liveState.dataSimSubscriptionId } ?: liveState.sims.firstOrNull()
                            val cell = sim?.servingCell
                            rounds += SpeedtestRoundV130(System.currentTimeMillis(), rounds.size + 1, dl.toDouble(), ul.toDouble(),
                                cell?.operator.orEmpty(),cell?.displayRat.orEmpty(),cell?.band.orEmpty(),cell?.pci.orEmpty(),
                                cell?.rsrp.orEmpty(),cell?.sinr.orEmpty(),liveState.dataNetwork,TestScreenRecordingService.currentUri)
                            dl = ""; ul = ""
                            SpeedtestCaptureStoreV130.nextRound()
                            status = "Round ${rounds.size} saved. ${if (rounds.size < maxRounds) "Run the next Speedtest." else "All rounds complete; export the results."}"
                        }) { Text("COMPLETE ROUND ${rounds.size + 1}") }
                }
                rounds.forEach { row -> Text("#${row.attempt}  DL ${row.dlMbps} Mbps · UL ${row.ulMbps} Mbps · ${row.rat} · RSRP ${row.rsrp}") }
                if (rounds.isNotEmpty()) Text("Average: DL ${String.format(Locale.US, "%.2f",rounds.map { it.dlMbps }.average())} Mbps · UL ${String.format(Locale.US, "%.2f",rounds.map { it.ulMbps }.average())} Mbps")
                Button(modifier = Modifier.fillMaxWidth(), enabled = rounds.isNotEmpty(), onClick = {
                    runCatching {
                        val uris = SpeedtestReportV130.save(context,task,role,rounds,SpeedtestReportV130.installed(context)?.second ?: "unknown")
                        output = uris
                        status = "Saved CSV + XLSX to Downloads/CellTracker/.../Speedtest"
                        if (captureStarted) context.startService(Intent(context, TestScreenRecordingService::class.java).apply { action = TestScreenRecordingService.STOP })
                        captureStarted = false
                        if (ownsNetworkRecording) { onStopNetworkRecording(); ownsNetworkRecording = false }
                        SpeedtestCaptureStoreV130.stop()
                    }.onFailure { status = "Export failed: ${it.message}" }
                }) { Text("EXPORT RESULTS TO EXCEL") }
                if (output.isNotEmpty()) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, output.last())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share Speedtest Excel")) }
                }) { Text("SHARE EXCEL") }
            }
        }
    }
    if (showSetup) {
        UnifiedTestSetupDialogV1270("Speedtest Test Setup", onDismiss = onBack) {
            Text("Speedtest by Ookla: ${version?.let { "v$it" } ?: "Not installed"}", style = MaterialTheme.typography.bodyMedium)
            if (version == null) OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { SpeedtestReportV130.install(context) }) { Text("INSTALL SPEEDTEST") }
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { version = SpeedtestReportV130.installed(context)?.second }) { Text("REFRESH APP STATUS") }
            OutlinedTextField(task, { task=it.take(64) }, label={ Text("Task name") }, singleLine=true, modifier=Modifier.fillMaxWidth())
            Text("Device role", style=MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("DUT1","DUT2","REF1","REF2").forEach { candidate ->
                    FilterChip(selected=role==candidate, onClick={ role=candidate }, label={ Text(candidate,style=MaterialTheme.typography.labelSmall) })
                }
            }
            OutlinedTextField(countText,{countText=it.filter(Char::isDigit).take(2)},label={Text("Speedtest rounds (1–10)")},singleLine=true)
            Row { Checkbox(checked=recordScreen,onCheckedChange={recordScreen=it}); Text("Record screen (requires user approval)") }
            Row { Checkbox(checked=autoReadUi,onCheckedChange={autoReadUi=it}); Text("Try Speedtest UI result detection (optional)") }
            if (autoReadUi) {
                val enabledServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                val serviceId = ComponentName(context, SpeedtestResultsAccessibilityV130::class.java).flattenToString()
                val enabled = enabledServices.split(':').any { it.equals(serviceId, ignoreCase=true) }
                Text(if(enabled) "Speedtest Accessibility: enabled" else "Speedtest Accessibility: needs permission (manual entry always works)",style=MaterialTheme.typography.bodySmall)
                if (!enabled) OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },modifier=Modifier.fillMaxWidth()) { Text("ENABLE SPEEDTEST ACCESSIBILITY") }
            }
            Text("Each finished round: return here, enter confirmed DL and UL, then tap Complete Round. Original screen recording is stored separately. Ensure the Speedtest app uses the cellular connection under test.",style=MaterialTheme.typography.bodySmall)
            Button(modifier=Modifier.fillMaxWidth(), enabled=(countText.toIntOrNull() ?: 0) in 1..10 && version != null, onClick={
                rounds.clear(); output=emptyList(); showSetup=false; onSetupFinished()
                if (autoReadUi) SpeedtestCaptureStoreV130.arm()
                if (!liveState.isRecording && liveState.sims.isNotEmpty()) {
                    onStartNetworkRecording("Speedtest_${task}_${role}")
                    ownsNetworkRecording = true
                } else if (!liveState.isRecording) {
                    status = "Network recording unavailable (no SIM info); per-round snapshots will still be saved."
                }
                if (recordScreen) launcher.launch(projectionManager.createScreenCaptureIntent())
                else if (!SpeedtestReportV130.launch(context)) status="Unable to launch Speedtest"
            }) { Text("START SPEEDTEST SESSION") }
        }
    }
}
