package com.example.celltracker

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var detailPath by remember { mutableStateOf<String?>(null) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.start() }

                LaunchedEffect(Unit) {
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                    launcher.launch(permissions.toTypedArray())
                }

                BackHandler(enabled = showSettings || detailPath != null) {
                    when {
                        detailPath != null -> detailPath = null
                        showSettings -> showSettings = false
                    }
                }

                when {
                    detailPath != null -> RecordingDetailScreen(
                        path = detailPath!!,
                        fallbackItem = state.recordings.firstOrNull { it.path == detailPath },
                        onBack = { detailPath = null },
                        onExport = vm::exportRecording,
                        onDelete = { path -> vm.deleteRecording(path); detailPath = null }
                    )
                    showSettings -> SettingsScreen(
                        settings = state.settings,
                        onSave = { vm.updateSettings(it); showSettings = false },
                        onBack = { showSettings = false }
                    )
                    else -> MainScreen(
                        state = state,
                        onSelectSim = vm::selectSubscription,
                        onStartRecording = vm::startRecording,
                        onRecordScope = vm::setRecordScope,
                        onStopRecording = vm::stopRecording,
                        onExport = vm::exportLatestCsv,
                        onExportRecording = vm::exportRecording,
                        onDeleteRecording = vm::deleteRecording,
                        onDeleteAll = vm::deleteAllRecordings,
                        onOpenRecording = { detailPath = it },
                        onSettings = { showSettings = true },
                        onDismissMessage = vm::clearMessage
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: AppState,
    onSelectSim: (Int) -> Unit,
    onStartRecording: () -> Unit,
    onRecordScope: (RecordScope) -> Unit,
    onStopRecording: () -> Unit,
    onExport: (CsvExportMode) -> Unit,
    onExportRecording: (String, CsvExportMode) -> Unit,
    onDeleteRecording: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenRecording: (String) -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var neighborsExpanded by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var deletePath by remember { mutableStateOf<String?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }

    val selected = state.sims.firstOrNull { it.subscriptionId == state.selectedSubscriptionId } ?: state.sims.firstOrNull()
    val c = selected?.servingCell ?: CellData()
    val sortedNeighbors = selected?.neighbors.orEmpty().sortedByDescending { it.rsrp.toIntOrNull() ?: Int.MIN_VALUE }
    val strongestNeighbor = sortedNeighbors.firstOrNull()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("CellTracker 0.3.0") },
            actions = { TextButton(onClick = onSettings) { Text("Settings") } }
        )
    }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.sims.size > 1) {
                TabRow(selectedTabIndex = state.sims.indexOfFirst { it.subscriptionId == selected?.subscriptionId }.coerceAtLeast(0)) {
                    state.sims.forEach { sim ->
                        Tab(
                            selected = sim.subscriptionId == selected?.subscriptionId,
                            onClick = { onSelectSim(sim.subscriptionId) },
                            text = { Text("SIM ${sim.simSlotIndex + 1}\n${sim.servingCell.operator}") }
                        )
                    }
                }
            } else if (selected != null) {
                Text("SIM ${selected.simSlotIndex + 1} · ${selected.servingCell.operator}", style = MaterialTheme.typography.titleMedium)
            }

            InfoCard("Network") {
                Field("Operator", c.operator)
                Field("RAT", c.displayRat.ifBlank { c.rat })
                Field("Registered", if (c.registered) "Yes" else "No")
            }
            InfoCard("Serving Cell") {
                Field("MCC / MNC", "${c.mcc} / ${c.mnc}")
                Field("TAC", c.tac)
                Field(if (c.rat == "NR") "NCI" else "Cell ID", c.cellId)
                Field("PCI", c.pci)
                Field(if (c.rat == "NR") "NR-ARFCN" else "EARFCN", c.arfcn)
            }
            InfoCard("Signal") {
                Field(if (c.rat == "NR") "SS-RSRP" else "RSRP", valueWithUnit(c.rsrp, "dBm"))
                Field(if (c.rat == "NR") "SS-RSRQ" else "RSRQ", valueWithUnit(c.rsrq, "dB"))
                Field(if (c.rat == "NR") "SS-SINR" else "SINR", valueWithUnit(c.sinr, "dB"))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { neighborsExpanded = !neighborsExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Neighbor Cells", style = MaterialTheme.typography.titleMedium)
                            Text("${sortedNeighbors.size} detected", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (neighborsExpanded) "▲" else "▼", style = MaterialTheme.typography.titleMedium)
                    }
                    if (!neighborsExpanded) {
                        Spacer(Modifier.height(10.dp)); HorizontalDivider(); Spacer(Modifier.height(10.dp))
                        if (strongestNeighbor == null) Text("No neighbor cells reported", style = MaterialTheme.typography.bodySmall)
                        else {
                            Text("Strongest", style = MaterialTheme.typography.labelMedium)
                            Text("${strongestNeighbor.rat} · PCI ${strongestNeighbor.pci} · ${valueWithUnit(strongestNeighbor.rsrp, "dBm")}")
                        }
                    }
                    AnimatedVisibility(neighborsExpanded) {
                        Column(modifier = Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HorizontalDivider()
                            if (sortedNeighbors.isEmpty()) Text("No neighbor cells reported", style = MaterialTheme.typography.bodySmall)
                            else sortedNeighbors.forEachIndexed { index, n ->
                                NeighborCellItem(index + 1, n)
                                if (index != sortedNeighbors.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }

            val l = state.location
            InfoCard("Location") {
                Field("Latitude", l.latitude); Field("Longitude", l.longitude); Field("Altitude", l.altitude)
                Field("Accuracy", l.accuracy); Field("Speed", l.speedKmh); Field("Bearing", l.bearing)
            }

            InfoCard("Recording") {
                Field("Status", if (state.isRecording) "Recording" else "Stopped")
                Field("Elapsed", formatElapsed(state.recordingElapsedMs))
                Field("Record interval", "${state.settings.recordIntervalMs / 1000.0} s")
                Text("Record scope", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.settings.recordScope == RecordScope.CURRENT_SIM, enabled = !state.isRecording,
                        onClick = { onRecordScope(RecordScope.CURRENT_SIM) })
                    Text("Current SIM"); Spacer(Modifier.width(12.dp))
                    RadioButton(selected = state.settings.recordScope == RecordScope.BOTH_SIMS, enabled = !state.isRecording,
                        onClick = { onRecordScope(RecordScope.BOTH_SIMS) })
                    Text("Both SIMs")
                }
                val active = state.sims.firstOrNull { it.subscriptionId == state.selectedSubscriptionId }
                if (state.isRecording) {
                    if (state.settings.recordScope == RecordScope.BOTH_SIMS) {
                        Field("Recording", state.sims.joinToString(" + ") { "SIM ${it.simSlotIndex + 1} ${it.servingCell.operator}" })
                        state.sims.forEach { Field("SIM ${it.simSlotIndex + 1} samples", (state.recordingSamplesBySubscription[it.subscriptionId] ?: 0).toString()) }
                    } else if (active != null) {
                        Field("Recording", "SIM ${active.simSlotIndex + 1} ${active.servingCell.operator}")
                        Field("Samples", state.recordingSamples.toString())
                    }
                    val ageText = if (state.recordingLocationAgeMs == Long.MAX_VALUE) "--" else String.format(Locale.US, "%.1f s ago", state.recordingLocationAgeMs / 1000.0)
                    Field("Location", if (state.recordingLocationValid) "GPS ready" else "Waiting for GPS...")
                    Field("Last fix", ageText)
                }
                if (state.isRecording) Button(onClick = onStopRecording) { Text("Stop") }
                else Button(onClick = onStartRecording, enabled = active != null) { Text("Start Recording") }

                if (state.recordings.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Recent recordings", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showDeleteAll = true }) { Text("Delete all") }
                    }
                    state.recordings.take(5).forEach { item ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenRecording(item.path) }
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.startedAt)), style = MaterialTheme.typography.labelLarge)
                                    Text("View ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                }
                                Text("${item.simSummary} · ${formatElapsed(item.durationMs)} · ${item.totalSamples} samples", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = {
                                        if (item.simCount > 1) { exportPath = item.path; showExportDialog = true }
                                        else onExportRecording(item.path, CsvExportMode.COMBINED)
                                    }) { Text("Export") }
                                    TextButton(onClick = { deletePath = item.path }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
            state.exportMessage?.let { AssistChip(onClick = onDismissMessage, label = { Text(it) }) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Last cellular update: ${state.lastUpdated}", style = MaterialTheme.typography.bodySmall)
        }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("Export CSV") },
            text = { Text("Choose how to export this dual-SIM recording.") },
            confirmButton = { TextButton(onClick = {
                showExportDialog = false; exportPath?.let { onExportRecording(it, CsvExportMode.SEPARATE_BY_SIM) } ?: onExport(CsvExportMode.SEPARATE_BY_SIM); exportPath = null
            }) { Text("Separate by SIM") } },
            dismissButton = { TextButton(onClick = {
                showExportDialog = false; exportPath?.let { onExportRecording(it, CsvExportMode.COMBINED) } ?: onExport(CsvExportMode.COMBINED); exportPath = null
            }) { Text("Combined") } }
        )
    }
    deletePath?.let { path ->
        AlertDialog(onDismissRequest = { deletePath = null }, title = { Text("Delete recording?") },
            text = { Text("This recording will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onDeleteRecording(path); deletePath = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletePath = null }) { Text("Cancel") } })
    }
    if (showDeleteAll) {
        AlertDialog(onDismissRequest = { showDeleteAll = false }, title = { Text("Delete all recordings?") },
            text = { Text("All saved recording sessions will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { onDeleteAll(); showDeleteAll = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { showDeleteAll = false }) { Text("Cancel") } })
    }
}

private enum class DetailTab { SUMMARY, MAP, SAMPLES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDetailScreen(
    path: String,
    fallbackItem: RecordingItem?,
    onBack: () -> Unit,
    onExport: (String, CsvExportMode) -> Unit,
    onDelete: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(DetailTab.SUMMARY) }
    var selectedSimSlot by remember { mutableStateOf<Int?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val detail by produceState<RecordingDetail?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { runCatching { RecordingDetailRepository.load(path, fallbackItem) }.getOrNull() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Recording Detail") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = {
                TextButton(onClick = {
                    if ((detail?.item?.simCount ?: 1) > 1) showExport = true else onExport(path, CsvExportMode.COMBINED)
                }) { Text("Export") }
                TextButton(onClick = { showDelete = true }) { Text("Delete") }
            }
        )
    }) { padding ->
        if (detail == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val d = detail!!
            val filtered = if (selectedSimSlot == null) d.samples else d.samples.filter { it.simSlot == selectedSimSlot }
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (d.simSlots.size > 1) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = selectedSimSlot == null, onClick = { selectedSimSlot = null }, label = { Text("Both") })
                        d.simSlots.forEach { slot ->
                            FilterChip(selected = selectedSimSlot == slot, onClick = { selectedSimSlot = slot }, label = { Text("SIM $slot") })
                        }
                    }
                }
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    DetailTab.entries.forEach { tab ->
                        Tab(selected = selectedTab == tab, onClick = { selectedTab = tab }, text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                when (selectedTab) {
                    DetailTab.SUMMARY -> RecordingSummary(d.item, filtered)
                    DetailTab.MAP -> RecordingMap(filtered)
                    DetailTab.SAMPLES -> RecordingSamples(filtered)
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(onDismissRequest = { showDelete = false }, title = { Text("Delete recording?") },
            text = { Text("This recording will be permanently deleted.") },
            confirmButton = { TextButton(onClick = { showDelete = false; onDelete(path) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } })
    }
    if (showExport) {
        AlertDialog(onDismissRequest = { showExport = false }, title = { Text("Export dual-SIM recording") },
            text = { Text("Choose combined or separate CSV files.") },
            confirmButton = { TextButton(onClick = { showExport = false; onExport(path, CsvExportMode.SEPARATE_BY_SIM) }) { Text("Separate by SIM") } },
            dismissButton = { TextButton(onClick = { showExport = false; onExport(path, CsvExportMode.COMBINED) }) { Text("Combined") } })
    }
}

@Composable
private fun RecordingSummary(item: RecordingItem, samples: List<TrackSample>) {
    val validLocation = samples.count { it.locationValid }
    val ratCounts = samples.groupingBy { normalizedRat(it) }.eachCount().toList().sortedByDescending { it.second }
    val rsrp = samples.mapNotNull { it.rsrp.toIntOrNull() }
    val first = samples.firstOrNull(); val last = samples.lastOrNull()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoCard("Session") {
            Field("Started", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(item.startedAt)))
            Field("Duration", formatElapsed(item.durationMs))
            Field("Samples", samples.size.toString())
            Field("GPS samples", "$validLocation / ${samples.size}")
        }
        InfoCard("Track") {
            Field("Start", if (first?.locationValid == true) "${formatCoord(first.latitude)}, ${formatCoord(first.longitude)}" else "--")
            Field("End", if (last?.locationValid == true) "${formatCoord(last.latitude)}, ${formatCoord(last.longitude)}" else "--")
        }
        InfoCard("RAT Summary") {
            if (ratCounts.isEmpty()) Text("No samples") else ratCounts.forEach { (rat, count) -> Field(rat, "$count samples") }
        }
        InfoCard("Signal Summary") {
            if (rsrp.isEmpty()) Text("No RSRP samples") else {
                Field("RSRP Avg", String.format(Locale.US, "%.1f dBm", rsrp.average()))
                Field("RSRP Min", "${rsrp.minOrNull()} dBm")
                Field("RSRP Max", "${rsrp.maxOrNull()} dBm")
            }
        }
        val markerCount = samples.count { it.isMarker }
        InfoCard("Markers") { Field("Marked events", markerCount.toString()) }
    }
}

@Composable
private fun RecordingMap(samples: List<TrackSample>) {
    val valid = samples.filter { it.locationValid && it.latitude != null && it.longitude != null }
    Column(Modifier.fillMaxSize()) {
        RatLegend(valid.map { normalizedRat(it) }.distinct())
        if (valid.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No valid GPS points in this recording") }
        } else {
            OsmTrackMap(valid, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RatLegend(rats: List<String>) {
    if (rats.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        rats.take(5).forEach { rat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(Color(ratColor(rat)), CircleShape))
                Spacer(Modifier.width(4.dp)); Text(rat, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun OsmTrackMap(samples: List<TrackSample>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }
    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose { mapView.onPause(); mapView.onDetach() }
    }
    AndroidView(factory = { mapView }, modifier = modifier, update = { map ->
        map.overlays.clear()
        val allPoints = mutableListOf<GeoPoint>()
        samples.groupBy { it.simSlot }.values.forEach { simSamples ->
            var currentRat: String? = null
            var segment = mutableListOf<GeoPoint>()
            fun flush() {
                if (segment.size >= 2 && currentRat != null) {
                    val line = Polyline().apply {
                        setPoints(segment.toList())
                        outlinePaint.color = ratColor(currentRat!!)
                        outlinePaint.strokeWidth = 10f
                    }
                    map.overlays.add(line)
                }
                segment = mutableListOf()
            }
            simSamples.sortedBy { it.timestampMs }.forEach { s ->
                val point = GeoPoint(s.latitude!!, s.longitude!!)
                allPoints += point
                val rat = normalizedRat(s)
                if (currentRat == null) currentRat = rat
                if (rat != currentRat) { flush(); currentRat = rat }
                segment += point
                if (s.isMarker) {
                    map.overlays.add(Marker(map).apply {
                        position = point
                        title = if (s.eventType.isNotBlank()) s.eventType else "Marker"
                        snippet = "${s.operator} · ${normalizedRat(s)} · RSRP ${s.rsrp} dBm"
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    })
                }
            }
            flush()
        }
        if (allPoints.size == 1) {
            map.controller.setCenter(allPoints.first()); map.controller.setZoom(18.0)
        } else if (allPoints.size > 1) {
            map.post { runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPoints), true, 80) } }
        }
        map.invalidate()
    })
}

@Composable
private fun RecordingSamples(samples: List<TrackSample>) {
    if (samples.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No samples") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(samples, key = { "${it.timestampMs}-${it.simSlot}-${it.subscriptionId}" }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(s.timestampMs)), style = MaterialTheme.typography.labelLarge)
                        Text("SIM ${s.simSlot} · ${normalizedRat(s)}", style = MaterialTheme.typography.labelLarge)
                    }
                    Text("${s.operator} · PCI ${s.pci} · ARFCN ${s.arfcn}", style = MaterialTheme.typography.bodySmall)
                    Text("RSRP ${valueWithUnit(s.rsrp, "dBm")} · RSRQ ${valueWithUnit(s.rsrq, "dB")} · SINR ${valueWithUnit(s.sinr, "dB")}", style = MaterialTheme.typography.bodySmall)
                    Text(if (s.locationValid) "${formatCoord(s.latitude)}, ${formatCoord(s.longitude)}" else "Location --", style = MaterialTheme.typography.bodySmall)
                    if (s.isMarker) AssistChip(onClick = {}, label = { Text(if (s.eventType.isBlank()) "Marker" else s.eventType) })
                }
            }
        }
    }
}

@Composable
private fun NeighborCellItem(index: Int, n: CellData) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#$index ${n.rat}", style = MaterialTheme.typography.labelLarge)
            Text(valueWithUnit(n.rsrp, "dBm"), style = MaterialTheme.typography.labelLarge)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("PCI ${n.pci}", style = MaterialTheme.typography.bodySmall)
            Text("${if (n.rat == "NR") "NR-ARFCN" else "EARFCN"} ${n.arfcn}", style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("RSRQ ${valueWithUnit(n.rsrq, "dB")}", style = MaterialTheme.typography.bodySmall)
            if (n.sinr != "--") Text("SINR ${valueWithUnit(n.sinr, "dB")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: AppSettings, onSave: (AppSettings) -> Unit, onBack: () -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = { TextButton(onClick = { onSave(draft) }) { Text("Save") } })
    }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Sampling", style = MaterialTheme.typography.titleLarge)
            IntervalPicker("UI refresh interval", draft.uiRefreshMs) { draft = draft.copy(uiRefreshMs = it) }
            IntervalPicker("Recording interval", draft.recordIntervalMs) { draft = draft.copy(recordIntervalMs = it) }
            HorizontalDivider(); Text("Marker Button", style = MaterialTheme.typography.titleLarge)
            ActionPicker("Tap action", draft.tapAction) { draft = draft.copy(tapAction = it) }
            ActionPicker("Long press action", draft.longPressAction) { draft = draft.copy(longPressAction = it) }
            Text("Marker actions are saved and the data model is ready for map/overlay marking.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(); Text("After mark", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("Vibrate", draft.vibrateOnMark) { draft = draft.copy(vibrateOnMark = it) }
            SettingSwitch("Show toast", draft.toastOnMark) { draft = draft.copy(toastOnMark = it) }
            SettingSwitch("Play sound", draft.soundOnMark) { draft = draft.copy(soundOnMark = it) }
        }
    }
}

@Composable
private fun IntervalPicker(title: String, selected: Long, onSelected: (Long) -> Unit) {
    val values = listOf(500L, 1000L, 2000L, 5000L, 10000L)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { v -> FilterChip(selected = selected == v, onClick = { onSelected(v) }, label = { Text(if (v < 1000) "0.5s" else "${v / 1000}s") }) }
        }
    }
}

@Composable
private fun ActionPicker(title: String, selected: MarkerAction, onSelected: (MarkerAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(selected.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                MarkerAction.entries.forEach { action -> DropdownMenuItem(text = { Text(action.label) }, onClick = { onSelected(action); expanded = false }) }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title); Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); HorizontalDivider(); content()
        }
    }
}

@Composable
private fun Field(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun normalizedRat(s: TrackSample): String {
    val value = (s.displayRat.ifBlank { s.rat }).uppercase(Locale.US)
    return when {
        value.contains("NR") || value.contains("5G") -> if (value.contains("NSA")) "5G NSA" else "5G NR"
        value.contains("LTE") || value.contains("4G") -> "LTE"
        value.contains("WCDMA") || value.contains("UMTS") || value.contains("3G") -> "3G"
        value.contains("GSM") || value.contains("EDGE") || value.contains("2G") -> "2G"
        else -> value.ifBlank { "Unknown" }
    }
}

private fun ratColor(rat: String): Int = when {
    rat.contains("NSA", true) -> AndroidColor.rgb(0, 150, 136)
    rat.contains("NR", true) || rat.contains("5G", true) -> AndroidColor.rgb(76, 175, 80)
    rat.contains("LTE", true) || rat.contains("4G", true) -> AndroidColor.rgb(33, 150, 243)
    rat.contains("3G", true) -> AndroidColor.rgb(255, 152, 0)
    rat.contains("2G", true) -> AndroidColor.rgb(117, 117, 117)
    else -> AndroidColor.rgb(156, 39, 176)
}

private fun formatCoord(v: Double?): String = v?.let { String.format(Locale.US, "%.6f", it) } ?: "--"
private fun valueWithUnit(value: String, unit: String): String = if (value == "--" || value.isBlank()) "--" else "$value $unit"
private fun formatElapsed(ms: Long): String {
    val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
