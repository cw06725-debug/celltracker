package com.example.celltracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Read the actual exported XLSX, rather than inventing a summary from a filename. */
internal object ManualStallSummaryReaderV130 {
    data class Detail(val fields: Map<String, String>, val events: List<List<String>>)

    fun read(context: Context, uri: Uri): Detail {
        val documents = mutableMapOf<String, ByteArray>()
        val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open saved report")
        stream.use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "xl/worksheets/sheet1.xml" || entry.name == "xl/worksheets/sheet2.xml") {
                        val bytes = zip.readBytes()
                        require(bytes.size <= 2_000_000) { "Report worksheet is too large" }
                        documents[entry.name] = bytes
                    }
                    zip.closeEntry()
                }
            }
        }
        fun rows(bytes: ByteArray?): List<List<String>> {
            if (bytes == null) return emptyList()
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { setXIncludeAware(false) }
                setExpandEntityReferences(false)
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            }
            val document = factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes)))
            val found = mutableListOf<List<String>>()
            val rowNodes = document.getElementsByTagName("row")
            for (i in 0 until rowNodes.length) {
                val row = rowNodes.item(i) as? Element ?: continue
                val cells = row.getElementsByTagName("c")
                val values = mutableListOf<String>()
                for (j in 0 until cells.length) {
                    val cell = cells.item(j) as? Element ?: continue
                    val text = cell.getElementsByTagName("t").item(0)?.textContent
                        ?: cell.getElementsByTagName("v").item(0)?.textContent ?: ""
                    values.add(text)
                }
                found.add(values)
            }
            return found
        }
        val summary = rows(documents["xl/worksheets/sheet1.xml"])
        require(summary.size > 1 && summary.first().take(2) == listOf("Field", "Value")) { "Unknown report summary format" }
        return Detail(summary.drop(1).filter { it.size >= 2 }.associate { it[0] to it[1] },
            rows(documents["xl/worksheets/sheet2.xml"]).drop(1))
    }
}

/** A pending recording is shown in Reports before any XLSX exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualStallReportDetailScreenV130(
    kind: ManualStallKindV130,
    report: ManualStallReportV130.Stored?,
    pendingRecording: Pair<Uri, RecordingTimelineV130.Session>?,
    canReviewVideo: Boolean,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val uri = report?.uri
    val result by produceState<Result<ManualStallSummaryReaderV130.Detail>?>(initialValue = null, key1 = uri) {
        value = if(uri == null) null else withContext(Dispatchers.IO) {
            runCatching { ManualStallSummaryReaderV130.read(context, uri) }
        }
    }
    var linkedVideo by remember(uri) { mutableStateOf(canReviewVideo) }
    var pickedVideo by remember(uri) { mutableStateOf<ExternalRecordingV130.Candidate?>(null) }
    var confirmedStart by remember(uri) { mutableStateOf("") }
    var videoMessage by remember(uri) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(selected, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ExternalRecordingV130.read(context, selected)
            }.onSuccess { candidate ->
                pickedVideo = candidate
                confirmedStart = candidate.suggestedStartMs?.let(ExternalRecordingV130::formatLocalTime).orEmpty()
                videoMessage = "Confirm the actual recording start time before linking."
            }.onFailure { videoMessage = "Unable to read video: ${it.message}" }
        }
    }
    // Per-report review is stored separately: never rewrite the original XLSX.
    val reviewPrefs = remember(context) { context.getSharedPreferences("ct_manual_stall_reviews_v130", Context.MODE_PRIVATE) }
    val reviewKey = remember(uri) { "stall_" + MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) } }
    data class EventEdit(val originalStart: Long, val originalEnd: Long, val start: Long, val end: Long, val valid: Boolean)
    val edits = remember(uri) { mutableStateMapOf<Int, EventEdit>() }
    var reviewConfirmed by remember(uri) { mutableStateOf(false) }
    var reviewMessage by remember(uri) { mutableStateOf("") }
    var editingIndex by remember(uri) { mutableIntStateOf(-1) }
    var editingStart by remember(uri) { mutableStateOf("") }
    var editingEnd by remember(uri) { mutableStateOf("") }
    var exportBusy by remember(uri) { mutableStateOf(false) }
    val clockFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    fun parseEventTime(text: String): Long? = runCatching { clockFormat.parse(text.trim())?.time }.getOrNull()
    fun displayEventTime(ms: Long): String = clockFormat.format(Date(ms))
    val lifecycleOwner=LocalLifecycleOwner.current
    var resumeCounter by remember(uri) { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME)resumeCounter++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(uri, result, resumeCounter) {
        val detail = result?.getOrNull() ?: return@LaunchedEffect
        edits.clear()
        val saved = runCatching { JSONObject(reviewPrefs.getString(reviewKey, "{}") ?: "{}") }.getOrDefault(JSONObject())
        val savedItems = saved.optJSONArray("events")
        detail.events.forEachIndexed { index, cells ->
            val a = cells.getOrNull(1)?.toLongOrNull() ?: return@forEachIndexed
            val b = cells.getOrNull(2)?.toLongOrNull() ?: return@forEachIndexed
            val stored = savedItems?.optJSONObject(index)
            edits[index] = if (stored != null && stored.optLong("originalStart") == a && stored.optLong("originalEnd") == b) {
                EventEdit(a, b, stored.optLong("start", a), stored.optLong("end", b), stored.optBoolean("valid", true))
            } else EventEdit(a, b, a, b, true)
        }
        reviewConfirmed = saved.optBoolean("confirmed", false) && edits.isNotEmpty()
    }
    fun persistReview(confirmed: Boolean) {
        val arr = JSONArray()
        edits.toSortedMap().forEach { (_, e) -> arr.put(JSONObject().apply {
            put("originalStart",e.originalStart);put("originalEnd",e.originalEnd)
            put("start",e.start);put("end",e.end);put("valid",e.valid)
        }) }
        reviewPrefs.edit().putString(reviewKey,JSONObject().put("events",arr).put("confirmed",confirmed).toString()).apply()
        reviewConfirmed=confirmed
    }
    val fields = result?.getOrNull()?.fields.orEmpty()
    val isPending = report == null
    val prefs = remember(context) { context.getSharedPreferences("ct_recording_timeline", Context.MODE_PRIVATE) }
    val recordedStart = pendingRecording?.second?.wallAt(0L) ?: 0L
    val recordingDuration = pendingRecording?.second?.videoDurationMs ?: 0L
    val startText = if (isPending && recordedStart > 0L) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(recordedStart))
    } else fields["Test Start (ms)"]?.toLongOrNull()?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(it))
    }.orEmpty()
    val duration = if(isPending) "%.2f".format(Locale.US,recordingDuration/1000.0)
        else fields["Test Duration (s)"].orEmpty()
    fun viewXlsx() {
        val selected = uri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(selected, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context,"No Excel viewer installed",Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxSize().background(bunnyScreenBrush())) {
        TopAppBar(
            title = { Text(if(isPending) "${kind.label} · Recording" else "${kind.label} · Summary", maxLines = 1) },
            navigationIcon = { TextButton(onClick=onBack) { Text("‹ Back") } }
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Card(shape=RoundedCornerShape(25.dp), colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {
                Column(Modifier.fillMaxWidth().background(gradientCardBrushV130()).padding(18.dp),
                    verticalArrangement=Arrangement.spacedBy(9.dp)) {
                    Text(if(isPending) "Recording ready for review" else "Test Summary",style=MaterialTheme.typography.titleLarge)
                    if(isPending) {
                        Text("The recording has not been reviewed/exported as a stall report. Review it, mark START/END and then export CSV + XLSX.",style=MaterialTheme.typography.bodySmall)
                    } else if (result == null) {
                        CircularProgressIndicator()
                    } else if (result?.isFailure == true) {
                        Text("Summary unavailable: ${result?.exceptionOrNull()?.message.orEmpty()}",color=MaterialTheme.colorScheme.error)
                        Text("The original XLSX is preserved; open it with an Excel viewer.",style=MaterialTheme.typography.bodySmall)
                    }
                    val currentValid = edits.values.filter { it.valid && it.end > it.start }
                    val currentTotalMs = currentValid.sumOf { it.end - it.start }
                    val currentDurationMs = ((fields["Test End (ms)"]?.toLongOrNull() ?: 0L) - (fields["Test Start (ms)"]?.toLongOrNull() ?: 0L)).coerceAtLeast(1L)
                    val hasReview = !isPending && edits.isNotEmpty()
                    val overview = listOf(
                        "Task" to (if(isPending) prefs.getString("reviewTask",kind.label).orEmpty() else fields["Task"].orEmpty()),
                        "Device Role" to (if(isPending) prefs.getString("reviewRole","").orEmpty() else fields["Device Role"].orEmpty()),
                        "Start Time" to startText,
                        "Duration" to duration.takeIf { it.isNotBlank() }?.let { "$it s" }.orEmpty(),
                        "Stall Count" to (if(isPending) "Not reviewed" else if(hasReview) currentValid.size.toString() else fields["Stall Count"].orEmpty()),
                        "Total Stall Duration" to (if(isPending) "—" else if(hasReview) "%.3f s".format(Locale.US,currentTotalMs/1000.0) else fields["Total Stall Duration (s)"]?.let { "$it s" }.orEmpty()),
                        "Stall Ratio" to (if(isPending) "—" else if(hasReview) "%.3f%%".format(Locale.US,100.0*currentTotalMs/currentDurationMs) else fields["Stall Ratio (%)"]?.let { "$it%" }.orEmpty()),
                        "Operator" to (if(isPending) prefs.getString("reviewOperator","").orEmpty() else fields["Operator"].orEmpty()),
                        "RAT / Band" to (if(isPending) listOf(prefs.getString("reviewRat","").orEmpty(),prefs.getString("reviewBand","").orEmpty()).joinToString(" / ") else listOf(fields["RAT"].orEmpty(),fields["Band"].orEmpty()).joinToString(" / ")),
                        "Source" to (if(isPending) "VIDEO_REVIEW_PENDING · ESTIMATED" else fields["Source"].orEmpty())
                    )
                    overview.filter { it.second.isNotBlank() }.forEach { (key,value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                            Text(key,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(.42f))
                            Text(value,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(.58f))
                        }
                    }
                }
            }
            if (!isPending && result?.isSuccess == true) {
                val originalEvents = result?.getOrNull()?.events.orEmpty()
                val validEvents = edits.values.filter { it.valid && it.end > it.start }
                val total = validEvents.sumOf { it.end - it.start }
                val testDuration = ((fields["Test End (ms)"]?.toLongOrNull() ?: 0L) - (fields["Test Start (ms)"]?.toLongOrNull() ?: 0L)).coerceAtLeast(1L)
                Card(shape=RoundedCornerShape(25.dp),colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {
                    Column(Modifier.fillMaxWidth().background(gradientCardBrushV130()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Stall Event Review",style=MaterialTheme.typography.titleMedium)
                        Text("Original: ${originalEvents.size} stalls · Reviewed: ${validEvents.size} stalls · ${"%.3f".format(Locale.US,total/1000.0)} s · ${"%.3f".format(Locale.US,100.0*total/testDuration)}%",style=MaterialTheme.typography.bodySmall)
                        originalEvents.forEachIndexed { index, _ ->
                            val e = edits[index] ?: return@forEachIndexed
                            Column(Modifier.fillMaxWidth().background(gradientCardBrushV130(),RoundedCornerShape(18.dp)).padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                Text("Stall #${index+1} · ${if(e.valid) "Valid" else "Invalid"}",style=MaterialTheme.typography.titleSmall)
                                Text("Original T0 → T1",style=MaterialTheme.typography.labelSmall)
                                Text("${displayEventTime(e.originalStart)} → ${displayEventTime(e.originalEnd)}",style=MaterialTheme.typography.bodySmall)
                                Text("Reviewed T0 → T1",style=MaterialTheme.typography.labelSmall)
                                Text("${displayEventTime(e.start)} → ${displayEventTime(e.end)} · ${"%.3f".format(Locale.US,(e.end-e.start)/1000.0)} s",style=MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Box(Modifier.weight(1f)) { GradientActionV130("REVIEW VIDEO",{
                                        // The player uses the recording currently linked to this report.
                                        persistReview(reviewConfirmed);RecordingTimelinePlayerV130.open(context,e.start,reviewKey,index)
                                    },Modifier.fillMaxWidth(),primary=false) }
                                    Box(Modifier.weight(1f)) { GradientActionV130("EDIT T0/T1",{
                                        editingIndex=index;editingStart=displayEventTime(e.start);editingEnd=displayEventTime(e.end)
                                    },Modifier.fillMaxWidth(),primary=false) }
                                }
                                GradientActionV130(if(e.valid) "MARK INVALID" else "RESTORE VALID",{
                                    edits[index]=e.copy(valid=!e.valid);persistReview(false)
                                },Modifier.fillMaxWidth(),primary=false)
                            }
                        }
                        GradientActionV130(if(reviewConfirmed) "REVIEW CONFIRMED" else "CONFIRM REVIEW",{
                            if(edits.size==originalEvents.size && edits.values.all { it.end>it.start }) {
                                persistReview(true);reviewMessage="Review confirmed. Export will create new reviewed files; originals remain unchanged."
                            } else reviewMessage="Some events have invalid time ranges."
                        },Modifier.fillMaxWidth())
                        GradientActionV130(if(exportBusy) "EXPORTING..." else "EXPORT REVIEWED REPORT",{
                            if(!reviewConfirmed || exportBusy) { reviewMessage="Confirm review before exporting." }
                            else {
                                val from=fields["Test Start (ms)"]?.toLongOrNull()
                                val to=fields["Test End (ms)"]?.toLongOrNull()
                                if(from==null || to==null || to<=from) reviewMessage="Invalid test time range."
                                else {
                                    exportBusy=true
                                    runCatching {
                                        val valid = edits.toSortedMap().values.filter { it.valid }.map { ManualStallEventV130(it.start,it.end) }
                                        ManualStallReportV130.save(context,kind,fields["Task"].orEmpty(),fields["Device Role"].orEmpty(),from,to,valid,
                                            fields["Operator"].orEmpty(),fields["RAT"].orEmpty(),fields["Band"].orEmpty(),fields["PCI"].orEmpty(),
                                            fields["RSRP"].orEmpty(),fields["SINR"].orEmpty(),fields["App Version"].orEmpty(),"MANUAL_REVIEW_CORRECTED",
                                            edits.toSortedMap().map { (i,e) -> listOf((i+1).toString(),e.originalStart.toString(),e.originalEnd.toString(),e.start.toString(),e.end.toString(),if(e.valid) "VALID" else "INVALID") })
                                    }.onSuccess { reviewMessage="Reviewed CSV + XLSX exported. Original files preserved." }
                                        .onFailure { reviewMessage="Export failed: ${it.message}" }
                                    exportBusy=false
                                }
                            }
                        },Modifier.fillMaxWidth())
                        if(reviewMessage.isNotBlank()) Text(reviewMessage,style=MaterialTheme.typography.bodySmall)
                    }
                }
                if(editingIndex>=0) {
                    AlertDialog(onDismissRequest={editingIndex=-1},title={Text("Edit Stall #${editingIndex+1}")},
                        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text("Format: yyyy-MM-dd HH:mm:ss.SSS",style=MaterialTheme.typography.bodySmall)
                            OutlinedTextField(editingStart,{editingStart=it},label={Text("T0 · Start")},singleLine=true)
                            OutlinedTextField(editingEnd,{editingEnd=it},label={Text("T1 · End")},singleLine=true)
                        }},confirmButton={TextButton(onClick={
                            val a=parseEventTime(editingStart);val b=parseEventTime(editingEnd)
                            val old=edits[editingIndex]
                            if(a!=null && b!=null && b>a && old!=null) {
                                edits[editingIndex]=old.copy(start=a,end=b);persistReview(false);editingIndex=-1
                            } else reviewMessage="Invalid T0/T1. End must be later than start."
                        }) { Text("SAVE") }},dismissButton={TextButton(onClick={editingIndex=-1}) { Text("CANCEL") }})
                }
            }
            Card(shape=RoundedCornerShape(25.dp), colors=CardDefaults.cardColors(containerColor=androidx.compose.ui.graphics.Color.Transparent)) {
                Column(Modifier.fillMaxWidth().background(gradientCardBrushV130()).padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Recording",style=MaterialTheme.typography.titleMedium)
                    if(linkedVideo) {
                        GradientActionV130("PLAY / REVIEW RECORDING",{RecordingTimelinePlayerV130.open(context)},Modifier.fillMaxWidth())
                        Text("Review stalls and export an updated report. Video time remains ESTIMATED.",style=MaterialTheme.typography.bodySmall)
                    } else {
                        Text("No verified recording linked to this report. Select the correct MP4 to review it; existing reports are not automatically linked to the latest recording.",style=MaterialTheme.typography.bodySmall)
                    }
                    GradientActionV130(if(linkedVideo) "CHANGE LINKED VIDEO" else "SELECT RECORDING",
                        {picker.launch(arrayOf("video/mp4","video/*"))},Modifier.fillMaxWidth(),primary=false)
                    if(pickedVideo!=null) {
                        Text("Selected video: ${pickedVideo!!.durationMs/1000.0} s · ${pickedVideo!!.source}",style=MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value=confirmedStart,onValueChange={confirmedStart=it},
                            label={Text("Recording start · yyyy-MM-dd HH:mm:ss")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        GradientActionV130("CONFIRM & LINK VIDEO",{
                            val candidate=pickedVideo
                            val startMs=ExternalRecordingV130.parseLocalTime(confirmedStart)
                            if(candidate==null || startMs==null) {
                                videoMessage="Enter a valid start time."
                            } else {
                                runCatching {
                                    ExternalRecordingV130.save(context,candidate,startMs,kind,
                                        fields["Device Role"].orEmpty(),fields["Task"].orEmpty(),"",
                                        fields["Operator"].orEmpty(),fields["RAT"].orEmpty(),
                                        fields["Band"].orEmpty(),"","","")
                                }.onSuccess {
                                    linkedVideo=true; pickedVideo=null
                                    videoMessage="Recording linked for review. Original XLSX remains unchanged until you export."
                                }.onFailure { videoMessage="Unable to link recording: ${it.message}" }
                            }
                        },Modifier.fillMaxWidth())
                    }
                    if(videoMessage.isNotBlank()) Text(videoMessage,style=MaterialTheme.typography.bodySmall)
                }
            }
            if (report != null) {
                GradientActionV130("OPEN EXCEL REPORT",{viewXlsx()},Modifier.fillMaxWidth(),primary=false)
            }
        }
    }
}
