package com.example.celltracker

import android.Manifest
import android.graphics.Color as AndroidColor
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.preference.PreferenceManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize osmdroid once, before any MapView is created. OSM requires a
        // stable app-specific User-Agent; generic/default library agents are blocked.
        Configuration.getInstance().apply {
            load(applicationContext, PreferenceManager.getDefaultSharedPreferences(applicationContext))
            userAgentValue = "CellTracker/${BuildConfig.VERSION_NAME} (+https://github.com/cw06725-debug/celltracker)"
        }

        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner, vm) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) vm.refreshRecordings()
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                var showSettings by remember { mutableStateOf(false) }
                var detailPath by remember { mutableStateOf<String?>(null) }
                var showPingTest by remember { mutableStateOf(false) }
                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.start() }
                var overlayPermissionRequestedForRecording by remember { mutableStateOf(false) }
                val overlayPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    if (android.provider.Settings.canDrawOverlays(this@MainActivity) && state.isRecording && state.settings.floatingWindowEnabled) {
                        runCatching { startService(Intent(this@MainActivity, FloatingOverlayService::class.java)) }
                    }
                }
                var capturePermissionRequestedForRecording by remember { mutableStateOf(false) }
                val capturePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null && state.isRecording) {
                        val intent = Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                            action = ScreenCaptureService.ACTION_INIT
                            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                        }
                        ContextCompat.startForegroundService(this@MainActivity, intent)
                    }
                }

                LaunchedEffect(Unit) {
                    val permissions = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                    if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                    launcher.launch(permissions.toTypedArray())
                }

                LaunchedEffect(
                    state.isRecording,
                    state.settings.floatingWindowEnabled,
                    state.settings.floatingAutoShowDuringRecording,
                    state.settings.floatingKeepWhenStopped
                ) {
                    val shouldShowOverlay = state.settings.floatingWindowEnabled &&
                        ((state.isRecording && state.settings.floatingAutoShowDuringRecording) ||
                            (!state.isRecording && state.settings.floatingKeepWhenStopped))
                    if (!state.isRecording) overlayPermissionRequestedForRecording = false
                    if (shouldShowOverlay && android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                        runCatching { startService(Intent(this@MainActivity, FloatingOverlayService::class.java)) }
                    } else if (state.isRecording && state.settings.floatingWindowEnabled && state.settings.floatingAutoShowDuringRecording &&
                        !android.provider.Settings.canDrawOverlays(this@MainActivity) && !overlayPermissionRequestedForRecording) {
                        overlayPermissionRequestedForRecording = true
                        val intent = Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlayPermissionLauncher.launch(intent)
                    }
                }

                LaunchedEffect(state.isRecording, state.settings.floatingCaptureScreenshotOnMark) {
                    if (!state.isRecording) {
                        capturePermissionRequestedForRecording = false
                        if (!state.settings.floatingKeepWhenStopped) {
                            runCatching { stopService(Intent(this@MainActivity, ScreenCaptureService::class.java)) }
                        }
                    } else if (state.settings.floatingCaptureScreenshotOnMark && !ScreenCaptureService.isReady && !capturePermissionRequestedForRecording) {
                        capturePermissionRequestedForRecording = true
                        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
                        capturePermissionLauncher.launch(mgr.createScreenCaptureIntent())
                    }
                }

                BackHandler(enabled = showSettings || detailPath != null || showPingTest) {
                    when {
                        detailPath != null -> detailPath = null
                        showPingTest -> showPingTest = false
                        showSettings -> showSettings = false
                    }
                }

                // Keep the detail path inside the AnimatedContent target itself. During an
                // exit animation Compose still renders the outgoing destination; reading
                // detailPath!! after Back had already set it to null caused the history-map
                // back crash. Capturing the path here keeps the outgoing screen valid.
                val rootDestination: RootDestination = when {
                    detailPath != null -> RootDestination.Detail(detailPath!!)
                    showPingTest -> RootDestination.PingTest
                    showSettings -> RootDestination.Settings
                    else -> RootDestination.Main
                }
                AnimatedContent(
                    targetState = rootDestination,
                    transitionSpec = {
                        when {
                            targetState == RootDestination.Settings ->
                                (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220)) + fadeIn(tween(180)))
                                    .togetherWith(slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220)) + fadeOut(tween(160)))
                            initialState == RootDestination.Settings ->
                                (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) + fadeIn(tween(180)))
                                    .togetherWith(slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) + fadeOut(tween(160)))
                            else -> fadeIn(tween(120)).togetherWith(fadeOut(tween(100)))
                        }
                    },
                    label = "rootNavigation"
                ) { destination ->
                when (destination) {
                    is RootDestination.Detail -> RecordingDetailScreen(
                        path = destination.path,
                        fallbackItem = state.recordings.firstOrNull { it.path == destination.path },
                        onBack = { detailPath = null },
                        onExport = vm::exportRecording,
                        onDelete = { path -> vm.deleteRecording(path); detailPath = null }
                    )
                    RootDestination.PingTest -> PingTestScreen(
                        state = state.pingTest,
                        selectedSim = state.sims.firstOrNull { it.subscriptionId == state.selectedSubscriptionId } ?: state.sims.firstOrNull(),
                        dataNetwork = state.dataNetwork,
                        onBack = { showPingTest = false },
                        onStart = vm::startPingTest,
                        onStop = vm::stopPingTest,
                        onClear = vm::clearPingResults
                    )
                    RootDestination.Settings -> SettingsScreen(
                        settings = state.settings,
                        onUpdate = vm::updateSettings,
                        onBack = { showSettings = false }
                    )
                    RootDestination.Main -> MainScreen(
                        state = state,
                        onSelectSim = vm::selectSubscription,
                        onStartRecording = vm::startRecording,
                        onRecordScope = vm::setRecordScope,
                        onMarkTarget = vm::setMarkTargetSubscription,
                        onStopRecording = vm::stopRecording,
                        onMarkEvent = vm::markEvent,
                        onExport = vm::exportLatestCsv,
                        onExportRecording = vm::exportRecording,
                        onDeleteRecording = vm::deleteRecording,
                        onDeleteAll = vm::deleteAllRecordings,
                        onOpenRecording = { detailPath = it },
                        onSettings = { showSettings = true },
                        onPingTest = { showPingTest = true },
                        onDismissMessage = vm::clearMessage
                    )
                }
                }
                state.exportResult?.let { result ->
                    ExportSuccessDialog(
                        result = result,
                        onDismiss = vm::clearMessage
                    )
                }
            }
        }
    }
}

private sealed interface RootDestination {
    data object Main : RootDestination
    data object Settings : RootDestination
    data object PingTest : RootDestination
    data class Detail(val path: String) : RootDestination
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun MainScreen(
    state: AppState,
    onSelectSim: (Int) -> Unit,
    onStartRecording: (String) -> Unit,
    onRecordScope: (RecordScope) -> Unit,
    onMarkTarget: (Int) -> Unit,
    onStopRecording: () -> Unit,
    onMarkEvent: (String, String) -> Unit,
    onExport: (CsvExportMode) -> Unit,
    onExportRecording: (String, CsvExportMode) -> Unit,
    onDeleteRecording: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenRecording: (String) -> Unit,
    onSettings: () -> Unit,
    onPingTest: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var neighborsExpanded by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    var deletePath by remember { mutableStateOf<String?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }
    var showLiveMap by remember { mutableStateOf(false) }
    var showMarkDialog by remember { mutableStateOf(false) }
    var showTaskNameDialog by remember { mutableStateOf(false) }
    var taskNameInput by remember { mutableStateOf("") }

    val selected = state.sims.firstOrNull { it.subscriptionId == state.selectedSubscriptionId } ?: state.sims.firstOrNull()
    val context = LocalContext.current
    var lastExitBackAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = showLiveMap) { showLiveMap = false }
    BackHandler(enabled = !showLiveMap) {
        val now = System.currentTimeMillis()
        if (now - lastExitBackAt <= 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastExitBackAt = now
            Toast.makeText(context, "Swipe back again to exit", Toast.LENGTH_SHORT).show()
        }
    }

    val selectedIndexForPager = state.sims.indexOfFirst { it.subscriptionId == selected?.subscriptionId }.coerceAtLeast(0)
    val simPagerState = rememberPagerState(initialPage = selectedIndexForPager) { state.sims.size.coerceAtLeast(1) }
    val simPagerScope = rememberCoroutineScope()
    LaunchedEffect(selectedIndexForPager, state.sims.size) {
        if (state.sims.isNotEmpty() && !simPagerState.isScrollInProgress && simPagerState.currentPage != selectedIndexForPager) {
            simPagerState.animateScrollToPage(selectedIndexForPager)
        }
    }
    LaunchedEffect(simPagerState.currentPage, state.sims) {
        state.sims.getOrNull(simPagerState.currentPage)?.let { sim ->
            if (sim.subscriptionId != state.selectedSubscriptionId) onSelectSim(sim.subscriptionId)
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (showLiveMap) "Live Map" else "CellTracker ${BuildConfig.VERSION_NAME}") },
            navigationIcon = { if (showLiveMap) TextButton(onClick = { showLiveMap = false }) { Text("Back") } },
            actions = {
                if (!showLiveMap) TextButton(onClick = { showLiveMap = true }) { Text("Map") }
                if (showLiveMap && state.isRecording) TextButton(onClick = { showMarkDialog = true }) { Text("Mark") }
                TextButton(onClick = onSettings) { Text("Settings") }
            }
        )
    }) { padding ->
        AnimatedContent(
            targetState = showLiveMap,
            transitionSpec = {
                if (targetState) {
                    (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220)) + fadeIn(tween(170)))
                        .togetherWith(slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(220)) + fadeOut(tween(150)))
                } else {
                    (slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) + fadeIn(tween(170)))
                        .togetherWith(slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(220)) + fadeOut(tween(150)))
                }
            },
            label = "mainLiveMap"
        ) { liveMapVisible ->
        if (liveMapVisible) {
            LiveMapScreen(state = state, onSelectSim = onSelectSim, modifier = Modifier.padding(padding).fillMaxSize())
        } else Column(
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            // Keep the SIM selector outside the scrollable content so it remains visible
            // while the user scrolls through Network / Neighbor / Recording cards.
            Surface(tonalElevation = 2.dp) {
                if (state.sims.size > 1) {
                    TabRow(selectedTabIndex = state.sims.indexOfFirst { it.subscriptionId == selected?.subscriptionId }.coerceAtLeast(0)) {
                        state.sims.forEach { sim ->
                            Tab(
                                selected = sim.subscriptionId == selected?.subscriptionId,
                                onClick = {
                                    val index = state.sims.indexOf(sim)
                                    if (index >= 0) simPagerScope.launch { simPagerState.animateScrollToPage(index, animationSpec = tween(420)) }
                                },
                                text = { Text("SIM ${sim.simSlotIndex + 1}\n${sim.servingCell.operator}") }
                            )
                        }
                    }
                } else if (selected != null) {
                    Text(
                        "SIM ${selected.simSlotIndex + 1} · ${selected.servingCell.operator}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }

            HorizontalPager(
                state = simPagerState,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) { page ->
                val pageSelected = state.sims.getOrNull(page) ?: selected
                val c = pageSelected?.servingCell ?: CellData()
                val sortedNeighbors = pageSelected?.neighbors.orEmpty().sortedByDescending { it.rsrp.toIntOrNull() ?: Int.MIN_VALUE }
                val strongestNeighbor = sortedNeighbors.firstOrNull()
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
            InfoCard("Network") {
                Field("Operator", c.operator)
                Field("RAT", c.displayRat.ifBlank { c.rat })
                val dataSim = state.sims.firstOrNull { it.subscriptionId == state.dataSimSubscriptionId }
                Field("Data SIM", dataSim?.let { "SIM ${it.simSlotIndex + 1} ${it.servingCell.operator}" } ?: "--")
                Field("DataNet", state.dataNetwork)
                Field("Data RAT", c.dataRat)
                Field("Voice RAT", c.voiceRat)
                Field("Roaming", c.roaming)
                Field("Registered", if (c.registered) "Yes" else "No")
            }
            InfoCard("Serving Cell") {
                Field("MCC / MNC", "${c.mcc} / ${c.mnc}")
                Field("TAC", c.tac)
                Field(if (c.rat == "NR") "NCI" else "Cell ID", c.cellId)
                Field("PCI", c.pci)
                Field("Band", c.band)
                Field(if (c.rat == "NR") "NR-ARFCN" else "EARFCN", c.arfcn)
                if (c.bandwidth != "--") Field("Bandwidth", c.bandwidth)
                Field("CA / EN-DC", c.carrierAggregation)
            }
            InfoCard("Signal") {
                Field(if (c.rat == "NR") "SS-RSRP" else "RSRP", valueWithUnit(c.rsrp, "dBm"))
                Field(if (c.rat == "NR") "SS-RSRQ" else "RSRQ", valueWithUnit(c.rsrq, "dB"))
                Field(if (c.rat == "NR") "SS-SINR" else "SINR", valueWithUnit(c.sinr, "dB"))
                if (c.rssi != "--") Field("RSSI", valueWithUnit(c.rssi, "dBm"))
                if (c.timingAdvance != "--") Field("Timing Advance", c.timingAdvance)
                if (c.csiRsrp != "--") Field("CSI-RSRP", valueWithUnit(c.csiRsrp, "dBm"))
                if (c.csiRsrq != "--") Field("CSI-RSRQ", valueWithUnit(c.csiRsrq, "dB"))
                if (c.csiSinr != "--") Field("CSI-SINR", valueWithUnit(c.csiSinr, "dB"))
                if (c.cqi != "--") Field("CQI", c.cqi)
                if (c.level != "--") Field("Signal level", "${c.level} / 4")
                if (c.asuLevel != "--") Field("ASU", c.asuLevel)
                SignalTrendSection(c, state.signalTrendBySubscription[c.subscriptionId].orEmpty())
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
                            Text("${strongestNeighbor.rat} · ${strongestNeighbor.band} · PCI ${strongestNeighbor.pci} · ${valueWithUnit(strongestNeighbor.rsrp, "dBm")}")
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

            InfoCard("Automated Tests") {
                Text("Ping Test", style = MaterialTheme.typography.titleSmall)
                Text("Single-DUT latency, success rate and packet loss with automatic issue markers.", style = MaterialTheme.typography.bodySmall)
                if (state.pingTest.completed > 0 || state.pingTest.isRunning) {
                    Field("Status", state.pingTest.statusMessage)
                    Field("Progress", "${state.pingTest.completed} / ${state.pingTest.config.count}")
                    Field("Success", String.format(Locale.US, "%.1f%%", state.pingTest.successRate))
                    Field("Avg latency", state.pingTest.averageLatencyMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--")
                }
                Button(onClick = onPingTest) { Text(if (state.pingTest.isRunning) "Open Ping Test" else "Configure Ping Test") }
            }

            InfoCard("Recording") {
                Field("Status", if (state.isRecording) "Recording" else "Stopped")
                Field("Elapsed", formatElapsed(state.recordingElapsedMs))
                Field("Record interval", "${state.settings.recordIntervalMs / 1000.0} s")
                Text("Record scope", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.settings.recordScope == RecordScope.CURRENT_SIM || state.sims.size < 2, enabled = !state.isRecording,
                        onClick = { onRecordScope(RecordScope.CURRENT_SIM) })
                    Text("Current SIM")
                    if (state.sims.size > 1) {
                        Spacer(Modifier.width(12.dp))
                        RadioButton(selected = state.settings.recordScope == RecordScope.BOTH_SIMS, enabled = !state.isRecording,
                            onClick = { onRecordScope(RecordScope.BOTH_SIMS) })
                        Text("Both SIMs")
                    }
                }
                if (state.sims.size > 1 && state.settings.recordScope == RecordScope.BOTH_SIMS) {
                    Text("Mark target", style = MaterialTheme.typography.labelLarge)
                    state.sims.forEach { sim ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val targetId = if (state.isRecording) state.recordingMarkTargetSubscriptionId else state.markTargetSubscriptionId
                            RadioButton(selected = targetId == sim.subscriptionId, enabled = !state.isRecording, onClick = { onMarkTarget(sim.subscriptionId) })
                            Text("SIM ${sim.simSlotIndex + 1} · ${sim.servingCell.operator}")
                        }
                    }
                    if (state.isRecording) Text("Mark target is locked during recording", style = MaterialTheme.typography.bodySmall)
                }
                val active = pageSelected
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
                if (state.isRecording) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onStopRecording) { Text("Stop") }
                        OutlinedButton(onClick = { showMarkDialog = true }) { Text("Mark issue") }
                    }
                } else Button(onClick = { showTaskNameDialog = true }, enabled = active != null) { Text("Start Recording") }

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
                                Text(recordingDisplayName(item.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
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
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Last cellular update: ${state.lastUpdated}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        }
    }

    if (showMarkDialog) {
        MarkIssueDialog(
            issueTypes = state.settings.issueTypes,
            onDismiss = { showMarkDialog = false },
            onSave = { issue, note ->
                onMarkEvent(issue, note)
                showMarkDialog = false
            }
        )
    }

    if (showTaskNameDialog) {
        AlertDialog(
            onDismissRequest = { showTaskNameDialog = false },
            title = { Text("Recording task") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Give this recording a task name so it is easier to identify later. You can also skip it.")
                    OutlinedTextField(
                        value = taskNameInput,
                        onValueChange = { if (it.length <= 48) taskNameInput = it },
                        label = { Text("Task name") },
                        placeholder = { Text("e.g. Zong 5G Drive Test") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onStartRecording(taskNameInput.trim())
                    showTaskNameDialog = false
                    taskNameInput = ""
                }) { Text("Start") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onStartRecording("")
                        showTaskNameDialog = false
                        taskNameInput = ""
                    }) { Text("Skip") }
                    TextButton(onClick = { showTaskNameDialog = false }) { Text("Cancel") }
                }
            }
        )
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



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PingTestScreen(
    state: PingTestState,
    selectedSim: SimCellState?,
    dataNetwork: String,
    onBack: () -> Unit,
    onStart: (PingTestConfig) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit
) {
    var host by remember(state.isRunning) { mutableStateOf(state.config.host) }
    var count by remember(state.isRunning) { mutableStateOf(state.config.count.toString()) }
    var interval by remember(state.isRunning) { mutableStateOf(state.config.intervalMs.toString()) }
    var timeout by remember(state.isRunning) { mutableStateOf(state.config.timeoutMs.toString()) }
    var threshold by remember(state.isRunning) { mutableStateOf(state.config.highLatencyThresholdMs.toInt().toString()) }
    var autoRecord by remember(state.isRunning) { mutableStateOf(state.config.autoRecord) }

    val latencies = state.samples.mapNotNull { it.latencyMs }.sorted()
    fun percentile(p: Double): String {
        if (latencies.isEmpty()) return "--"
        val index = kotlin.math.ceil((latencies.size - 1) * p).toInt().coerceIn(0, latencies.lastIndex)
        return String.format(Locale.US, "%.1f ms", latencies[index])
    }
    fun latencyText(v: Double?): String = v?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--"
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ping Test") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (!state.isRunning && state.samples.isNotEmpty()) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoCard("Test target") {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { if (!state.isRunning && it.length <= 80) host = it },
                        enabled = !state.isRunning,
                        singleLine = true,
                        label = { Text("Host / IP") },
                        placeholder = { Text("8.8.8.8") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = count,
                            onValueChange = { if (!state.isRunning) count = it.filter { ch -> ch.isDigit() }.take(5) },
                            enabled = !state.isRunning,
                            singleLine = true,
                            label = { Text("Count") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = interval,
                            onValueChange = { if (!state.isRunning) interval = it.filter { ch -> ch.isDigit() }.take(6) },
                            enabled = !state.isRunning,
                            singleLine = true,
                            label = { Text("Interval ms") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeout,
                            onValueChange = { if (!state.isRunning) timeout = it.filter { ch -> ch.isDigit() }.take(5) },
                            enabled = !state.isRunning,
                            singleLine = true,
                            label = { Text("Timeout ms") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = threshold,
                            onValueChange = { if (!state.isRunning) threshold = it.filter { ch -> ch.isDigit() }.take(5) },
                            enabled = !state.isRunning,
                            singleLine = true,
                            label = { Text("High ping ms") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = autoRecord, enabled = !state.isRunning, onCheckedChange = { autoRecord = it })
                        Column {
                            Text("Auto record network data")
                            Text("Creates a recording named Ping_<host> and stops it when the test ends.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                InfoCard("Current network") {
                    val c = selectedSim?.servingCell
                    Field("SIM", selectedSim?.let { "SIM ${it.simSlotIndex + 1} · ${c?.operator ?: "--"}" } ?: "--")
                    Field("RAT", c?.displayRat?.ifBlank { c.rat } ?: "--")
                    Field("RSRP", c?.rsrp?.let { valueWithUnit(it, "dBm") } ?: "--")
                    Field("SINR", c?.sinr?.let { valueWithUnit(it, "dB") } ?: "--")
                    Field("DataNet", dataNetwork)
                }
            }
            item {
                InfoCard("Result") {
                    Field("Status", state.statusMessage)
                    Field("Progress", "${state.completed} / ${state.config.count}")
                    Field("Success rate", if (state.completed > 0) String.format(Locale.US, "%.1f%%", state.successRate) else "--")
                    Field("Packet loss", if (state.completed > 0) String.format(Locale.US, "%.1f%%", state.packetLossRate) else "--")
                    Field("Average", latencyText(state.averageLatencyMs))
                    Field("Min / Max", if (state.samples.isEmpty()) "--" else "${latencyText(state.minLatencyMs)} / ${latencyText(state.maxLatencyMs)}")
                    Field("P50 / P90 / P95", if (latencies.isEmpty()) "--" else "${percentile(0.50)} / ${percentile(0.90)} / ${percentile(0.95)}")
                    if (state.isRunning) {
                        Button(onClick = onStop) { Text("Stop Ping Test") }
                    } else {
                        Button(
                            onClick = {
                                onStart(
                                    PingTestConfig(
                                        host = host.trim().ifBlank { "8.8.8.8" },
                                        count = count.toIntOrNull() ?: 20,
                                        intervalMs = interval.toLongOrNull() ?: 1000L,
                                        timeoutMs = timeout.toLongOrNull() ?: 2000L,
                                        highLatencyThresholdMs = threshold.toDoubleOrNull() ?: 300.0,
                                        autoRecord = autoRecord
                                    )
                                )
                            }
                        ) { Text("Start Ping Test") }
                        if (!state.resultPath.isNullOrBlank() && state.samples.isNotEmpty()) {
                            OutlinedButton(onClick = { shareLocalFile(context, state.resultPath, "text/csv", "Share Ping result") }) { Text("Share Ping CSV") }
                        }
                    }
                    Text("High latency creates AUTO/HIGH_PING markers. Three consecutive failures create an AUTO/PING_TIMEOUT marker.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.samples.isNotEmpty()) {
                item { Text("Samples", style = MaterialTheme.typography.titleMedium) }
                items(state.samples.asReversed().take(200), key = { it.sequence }) { sample ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("#${sample.sequence}", style = MaterialTheme.typography.labelLarge)
                                Text(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(sample.timestampMs)), style = MaterialTheme.typography.bodySmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (sample.success) latencyText(sample.latencyMs) else "Timeout", style = MaterialTheme.typography.labelLarge)
                                Text(if (sample.success) "Success" else "Failed", style = MaterialTheme.typography.bodySmall, color = if (sample.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportSuccessDialog(
    result: ExportResult,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export successful") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(result.message)
                Text("Saved to Downloads/CellTracker", style = MaterialTheme.typography.bodyMedium)
                result.excelName?.let { Text("Excel: $it", style = MaterialTheme.typography.bodySmall) }
                result.kmlName?.let { Text("KML: $it", style = MaterialTheme.typography.bodySmall) }
                if (result.screenshotNames.isNotEmpty()) Text("Screenshots: ${result.screenshotNames.size}", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { result.summaryUri?.let { openExportedFile(context, it, "text/html") } }) { Text("Open Summary") }
                TextButton(onClick = { result.excelUri?.let { openExportedFile(context, it, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") } }) { Text("Open Excel") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { shareExportedFiles(context, result) }) { Text("Share") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

private fun openExportedFile(context: android.content.Context, uriString: String, mimeType: String) {
    val uri = Uri.parse(uriString)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(intent) }
    catch (_: ActivityNotFoundException) { Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show() }
    catch (_: Exception) { Toast.makeText(context, "Unable to open exported file", Toast.LENGTH_SHORT).show() }
}

private fun shareExportedFiles(context: android.content.Context, result: ExportResult) {
    val uris = (result.exportedFileUris + listOfNotNull(result.summaryUri, result.excelUri, result.kmlUri) + result.screenshotUris).distinct().map { Uri.parse(it) }
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uris.first()) }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
    }.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share CellTracker export").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "Unable to share exported files", Toast.LENGTH_SHORT).show() }
}

private fun openLocalScreenshot(context: android.content.Context, path: String) {
    val file = java.io.File(path)
    if (!file.exists()) {
        Toast.makeText(context, "Screenshot file not found", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "image/png")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { Toast.makeText(context, "Unable to open screenshot", Toast.LENGTH_SHORT).show() }
}

private fun shareLocalFile(context: android.content.Context, path: String?, mimeType: String, title: String) {
    if (path.isNullOrBlank()) return
    val file = java.io.File(path)
    if (!file.exists()) {
        Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, "Unable to share file", Toast.LENGTH_SHORT).show() }
}

@Composable
private fun ScreenshotThumbnail(path: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Card(
            modifier = modifier.fillMaxWidth().height(150.dp).clickable { openLocalScreenshot(context, path) },
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Image(
                bitmap = bitmap,
                contentDescription = "Marker screenshot",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        AssistChip(onClick = { openLocalScreenshot(context, path) }, label = { Text("Open screenshot") })
    }
}

@Composable
private fun MarkIssueDialog(
    issueTypes: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    // AlertDialog may measure its text slot with loose/intrinsic constraints on
    // some Compose/device combinations. A verticallyScroll() Column here could
    // therefore crash as soon as the dialog was shown. Keep the dialog content
    // inside a bounded LazyColumn instead.
    val options = remember(issueTypes) {
        issueTypes.map { it.trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { listOf("General") }
    }
    var selected by remember(options) { mutableStateOf(options.first()) }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark issue") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item { Text("Issue type", style = MaterialTheme.typography.labelLarge) }
                items(options, key = { it }) { issue ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = issue }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected == issue, onClick = { selected = issue })
                        Text(issue)
                    }
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Problem description / note (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected, note.trim()) }) { Text("Mark") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveMapScreen(
    state: AppState,
    onSelectSim: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = state.sims.indexOfFirst { it.subscriptionId == state.selectedSubscriptionId }.coerceAtLeast(0)
    val livePagerState = rememberPagerState(initialPage = selectedIndex) { maxOf(1, state.sims.size) }
    val livePagerScope = rememberCoroutineScope()
    var liveSamples by remember { mutableStateOf<List<TrackSample>>(emptyList()) }

    LaunchedEffect(state.latestRecordingPath, state.isRecording, state.recordingSamples) {
        if (!state.isRecording) {
            liveSamples = emptyList()
            return@LaunchedEffect
        }
        val path = state.latestRecordingPath ?: run {
            liveSamples = emptyList()
            return@LaunchedEffect
        }
        while (state.isRecording) {
            liveSamples = withContext(Dispatchers.IO) {
                runCatching { RecordingDetailRepository.loadSamples(path) }.getOrDefault(emptyList())
            }
            delay(1000L)
        }
    }

    // Keep the selected subscription and pager synchronized. Dragging is handled
    // by HorizontalPager itself, so the page follows the finger continuously.
    LaunchedEffect(livePagerState.currentPage, state.sims) {
        state.sims.getOrNull(livePagerState.currentPage)?.let { sim ->
            if (sim.subscriptionId != state.selectedSubscriptionId) onSelectSim(sim.subscriptionId)
        }
    }
    LaunchedEffect(state.selectedSubscriptionId, state.sims.size) {
        val index = state.sims.indexOfFirst { it.subscriptionId == state.selectedSubscriptionId }
        if (index >= 0 && !livePagerState.isScrollInProgress && livePagerState.currentPage != index) {
            livePagerState.animateScrollToPage(index)
        }
    }

    Column(modifier.fillMaxSize()) {
        var headerDrag by remember { mutableFloatStateOf(0f) }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.pointerInput(state.sims.size, livePagerState.currentPage) {
                detectHorizontalDragGestures(
                    onDragStart = { headerDrag = 0f },
                    onHorizontalDrag = { _, amount -> headerDrag += amount },
                    onDragEnd = {
                        val target = when {
                            headerDrag < -70f -> (livePagerState.currentPage + 1).coerceAtMost(state.sims.lastIndex)
                            headerDrag > 70f -> (livePagerState.currentPage - 1).coerceAtLeast(0)
                            else -> livePagerState.currentPage
                        }
                        if (target != livePagerState.currentPage) livePagerScope.launch { livePagerState.animateScrollToPage(target, animationSpec = tween(420)) }
                        headerDrag = 0f
                    },
                    onDragCancel = { headerDrag = 0f }
                )
            }
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (state.sims.size > 1) {
                    TabRow(selectedTabIndex = livePagerState.currentPage.coerceIn(0, state.sims.lastIndex)) {
                        state.sims.forEachIndexed { index, sim ->
                            Tab(
                                selected = livePagerState.currentPage == index,
                                onClick = { livePagerScope.launch { livePagerState.animateScrollToPage(index, animationSpec = tween(420)) } },
                                text = { Text("SIM ${sim.simSlotIndex + 1}\n${sim.servingCell.operator}") }
                            )
                        }
                    }
                } else {
                    state.sims.firstOrNull()?.let { sim ->
                        Text(
                            "SIM ${sim.simSlotIndex + 1} · ${sim.servingCell.operator}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }

        // The map itself needs unrestricted horizontal drags for panning.
        // Disable pager drag here so a map pan never accidentally switches SIM.
        // SIM switching remains available through the tabs above, with animation.
        HorizontalPager(
            state = livePagerState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            userScrollEnabled = false
        ) { page ->
            val mapSelected = state.sims.getOrNull(page) ?: state.sims.firstOrNull()
            val mapVisibleSamples = remember(liveSamples, state.settings.recordScope, mapSelected?.simSlotIndex) {
                if (state.settings.recordScope == RecordScope.BOTH_SIMS || mapSelected == null) liveSamples
                else liveSamples.filter { it.simSlot == mapSelected.simSlotIndex + 1 }
            }

            val currentLat = state.location.latitude.toDoubleOrNull()
            val currentLon = state.location.longitude.toDoubleOrNull()
            val currentPoint = if (state.location.isValid && currentLat != null && currentLon != null) GeoPoint(currentLat, currentLon) else null
            val currentSample = if (currentPoint != null && mapSelected != null) {
                val c = mapSelected.servingCell
                TrackSample(
                    timestampMs = System.currentTimeMillis(),
                    simSlot = mapSelected.simSlotIndex + 1, subscriptionId = mapSelected.subscriptionId, operator = c.operator,
                    rat = c.rat, displayRat = c.displayRat, mcc = c.mcc, mnc = c.mnc, tac = c.tac, cellId = c.cellId,
                    pci = c.pci, arfcn = c.arfcn, rsrp = c.rsrp, rsrq = c.rsrq, sinr = c.sinr,
                    band = c.band, bandwidth = c.bandwidth, rssi = c.rssi, timingAdvance = c.timingAdvance,
                    csiRsrp = c.csiRsrp, csiRsrq = c.csiRsrq, csiSinr = c.csiSinr,
                    latitude = currentLat, longitude = currentLon, altitude = state.location.altitude, accuracy = state.location.accuracy,
                    speedKmh = state.location.speedKmh, bearing = state.location.bearing, locationValid = true
                )
            } else null
            val valid = mapVisibleSamples.filter { it.locationValid && it.latitude != null && it.longitude != null }

            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(if (state.isRecording) "Recording · live track" else "Live location", style = MaterialTheme.typography.labelLarge)
                        Text("${valid.size} GPS points", style = MaterialTheme.typography.bodySmall)
                    }
                    val c = mapSelected?.servingCell
                    Text("${c?.displayRat?.ifBlank { c.rat } ?: "--"}  ${valueWithUnit(c?.rsrp ?: "--", "dBm")}", style = MaterialTheme.typography.labelLarge)
                }

                if (valid.isEmpty() && currentPoint == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (state.isRecording) "Waiting for GPS track..." else "Waiting for live GPS...")
                    }
                } else {
                    RatLegend((valid.map { normalizedRat(it) } + listOfNotNull(mapSelected?.servingCell?.displayRat?.takeIf { it.isNotBlank() })).distinct())
                    OsmTrackMap(
                        samples = valid,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        currentPoint = currentPoint,
                        currentLabel = mapSelected?.servingCell?.let { "${it.operator} · ${it.displayRat.ifBlank { it.rat }} · ${valueWithUnit(it.rsrp, "dBm")}" },
                        currentSample = currentSample,
                        liveFollow = state.isRecording,
                        detailFields = state.settings.mapDetailFields
                    )
                }
            }
        }
    }
}

private enum class DetailTab { SUMMARY, MAP, SAMPLES }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RecordingDetailScreen(
    path: String,
    fallbackItem: RecordingItem?,
    onBack: () -> Unit,
    onExport: (String, CsvExportMode) -> Unit,
    onDelete: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(DetailTab.SUMMARY) }
    val detailPagerState = rememberPagerState(initialPage = DetailTab.SUMMARY.ordinal) { DetailTab.entries.size }
    val detailPagerScope = rememberCoroutineScope()
    LaunchedEffect(detailPagerState.currentPage) { selectedTab = DetailTab.entries[detailPagerState.currentPage] }
    LaunchedEffect(selectedTab) {
        if (!detailPagerState.isScrollInProgress && detailPagerState.currentPage != selectedTab.ordinal) {
            detailPagerState.animateScrollToPage(selectedTab.ordinal)
        }
    }
    var selectedSimSlot by remember { mutableStateOf<Int?>(null) }
    var focusMarkerId by remember { mutableStateOf<String?>(null) }
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
                // Keep all controls in an opaque Compose layer above the Android MapView.
                // AndroidView interop can otherwise visually bleed over sibling composables
                // on some devices/Compose versions.
                var detailHeaderDrag by remember { mutableFloatStateOf(0f) }
                Surface(
                    modifier = Modifier.fillMaxWidth().zIndex(2f).pointerInput(selectedTab) {
                        detectHorizontalDragGestures(
                            onDragStart = { detailHeaderDrag = 0f },
                            onHorizontalDrag = { _, amount -> detailHeaderDrag += amount },
                            onDragEnd = {
                                val current = selectedTab.ordinal
                                val target = when {
                                    detailHeaderDrag < -70f -> (current + 1).coerceAtMost(DetailTab.entries.lastIndex)
                                    detailHeaderDrag > 70f -> (current - 1).coerceAtLeast(0)
                                    else -> current
                                }
                                if (target != current) detailPagerScope.launch { detailPagerState.animateScrollToPage(target, animationSpec = tween(420)) }
                                detailHeaderDrag = 0f
                            },
                            onDragCancel = { detailHeaderDrag = 0f }
                        )
                    },
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (d.simSlots.size > 1) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(selected = selectedSimSlot == null, onClick = { selectedSimSlot = null }, label = { Text("Both") })
                                d.simSlots.forEach { slot ->
                                    FilterChip(selected = selectedSimSlot == slot, onClick = { selectedSimSlot = slot }, label = { Text("SIM $slot") })
                                }
                            }
                        }
                        TabRow(selectedTabIndex = selectedTab.ordinal) {
                            DetailTab.entries.forEach { tab ->
                                Tab(
                                    selected = selectedTab == tab,
                                    onClick = { detailPagerScope.launch { detailPagerState.animateScrollToPage(tab.ordinal, animationSpec = tween(420)) } },
                                    text = { Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }
                    }
                }
                HorizontalPager(
                    state = detailPagerState,
                    modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds().zIndex(0f),
                    // On the Map tab, horizontal gestures belong to the map.
                    // Summary/Samples can still use finger-following page swipes.
                    userScrollEnabled = selectedTab != DetailTab.MAP,
                ) { page ->
                    when (DetailTab.entries[page]) {
                        DetailTab.SUMMARY -> RecordingSummary(d.item, filtered) { marker ->
                            focusMarkerId = marker.markerId
                            selectedTab = DetailTab.MAP
                            detailPagerScope.launch { detailPagerState.animateScrollToPage(DetailTab.MAP.ordinal, animationSpec = tween(420)) }
                        }
                        DetailTab.MAP -> RecordingMap(filtered, focusMarkerId)
                        DetailTab.SAMPLES -> RecordingSamples(filtered) { marker ->
                            focusMarkerId = marker.markerId
                            selectedTab = DetailTab.MAP
                            detailPagerScope.launch { detailPagerState.animateScrollToPage(DetailTab.MAP.ordinal, animationSpec = tween(420)) }
                        }
                    }
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
private fun RecordingSummary(item: RecordingItem, samples: List<TrackSample>, onMarkerClick: (TrackSample) -> Unit) {
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
            Field("Screenshots", samples.count { it.isMarker && it.screenshot.isNotBlank() }.toString())
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
        val markers = samples.filter { it.isMarker }.sortedBy { it.timestampMs }
        InfoCard("Markers") {
            Field("Marked events", markers.size.toString())
            if (markers.isEmpty()) {
                Text("No marked events", style = MaterialTheme.typography.bodySmall)
            } else {
                markers.forEachIndexed { index, marker ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Column(
                        Modifier.fillMaxWidth().clickable { onMarkerClick(marker) }.padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${marker.eventSource.ifBlank { "MANUAL" }} · ${marker.eventType.ifBlank { "Marker" }}", style = MaterialTheme.typography.labelLarge)
                            Text(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(marker.timestampMs)), style = MaterialTheme.typography.labelMedium)
                        }
                        Text("SIM ${marker.simSlot} · ${marker.operator} · ${normalizedRat(marker)}", style = MaterialTheme.typography.bodySmall)
                        Text("RSRP ${valueWithUnit(marker.rsrp, "dBm")} · PCI ${marker.pci} · ${marker.band}", style = MaterialTheme.typography.bodySmall)
                        Text("Tap to view on Map", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (marker.screenshot.isNotBlank()) {
                            Text(java.io.File(marker.screenshot).name, style = MaterialTheme.typography.labelSmall)
                            ScreenshotThumbnail(marker.screenshot)
                        }
                    }
                }
            }
        }
    }
}

private enum class MapSampleFilter { ALL, MARKERS }

@Composable
private fun RecordingMap(samples: List<TrackSample>, focusMarkerId: String? = null) {
    var filter by remember { mutableStateOf(MapSampleFilter.ALL) }
    val valid = remember(samples) {
        samples.filter { it.locationValid && it.latitude != null && it.longitude != null }
    }
    val markerCount = valid.count { it.isMarker }
    val visible = remember(valid, filter) {
        if (filter == MapSampleFilter.MARKERS) valid.filter { it.isMarker } else valid
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(selected = filter == MapSampleFilter.ALL, onClick = { filter = MapSampleFilter.ALL }, label = { Text("All points") })
            FilterChip(selected = filter == MapSampleFilter.MARKERS, onClick = { filter = MapSampleFilter.MARKERS }, label = { Text("Markers ($markerCount)") })
        }
        if (filter == MapSampleFilter.ALL) RatLegend(valid.map { normalizedRat(it) }.distinct())
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color(0xFFFF9800), CircleShape))
            Spacer(Modifier.width(4.dp))
            Text("Issue marker", style = MaterialTheme.typography.labelSmall)
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (filter == MapSampleFilter.MARKERS) "No markers with valid GPS" else "No valid GPS points in this recording")
            }
        } else {
            OsmTrackMap(
                visible,
                Modifier.fillMaxWidth().weight(1f),
                detailFields = SettingsRepository(LocalContext.current).load().mapDetailFields,
                focusMarkerId = focusMarkerId,
                drawTrack = filter == MapSampleFilter.ALL,
                showEndpoints = filter == MapSampleFilter.ALL
            )
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
private fun OsmTrackMap(
    samples: List<TrackSample>,
    modifier: Modifier = Modifier,
    currentPoint: GeoPoint? = null,
    currentLabel: String? = null,
    currentSample: TrackSample? = null,
    liveFollow: Boolean = false,
    detailFields: Set<MapDetailField> = AppSettings().mapDetailFields,
    focusMarkerId: String? = null,
    drawTrack: Boolean = true,
    showEndpoints: Boolean = true
) {
    val context = LocalContext.current
    var selectedSample by remember { mutableStateOf<TrackSample?>(null) }
    var selectedIsCurrent by remember { mutableStateOf(false) }

    // When the user is inspecting the live/current location, SIM switching and
    // cellular refreshes must update the inspector immediately instead of leaving
    // the old SIM snapshot on screen.
    LaunchedEffect(currentSample) {
        if (selectedIsCurrent) selectedSample = currentSample
    }

    // OSM's current tile policy requires the canonical host (no a/b/c subdomains) and an
    // identifiable User-Agent. osmdroid's historical MAPNIK source can resolve through old
    // subdomain URLs, so use the canonical endpoint explicitly.
    val osmTileSource = remember {
        XYTileSource(
            "OpenStreetMap",
            0,
            19,
            256,
            ".png",
            arrayOf("https://tile.openstreetmap.org/"),
            "© OpenStreetMap contributors"
        )
    }

    val mapView = remember(context) {
        MapView(context).apply {
            setTileSource(osmTileSource)
            setUseDataConnection(true)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            maxZoomLevel = 19.0
            controller.setZoom(16.0)
        }
    }

    DisposableEffect(mapView) {
        mapView.onResume()
        onDispose {
            // AndroidView owns attach/detach. Calling onDetach() manually while the
            // outgoing Compose destination is also being removed can double-detach
            // osmdroid internals on some devices. Pause only here.
            mapView.onPause()
        }
    }

    // Use a value-based key instead of List identity. This prevents camera/overlay work from
    // restarting merely because Compose created an equivalent list instance.
    val trackKey = remember(samples) {
        samples.joinToString("|") {
            "${it.timestampMs},${it.simSlot},${it.latitude},${it.longitude},${normalizedRat(it)},${it.isMarker}"
        }.hashCode()
    }

    val livePointKey = currentPoint?.let { "${String.format(Locale.US, "%.6f", it.latitude)},${String.format(Locale.US, "%.6f", it.longitude)}" }

    LaunchedEffect(mapView, trackKey, livePointKey, liveFollow, focusMarkerId, drawTrack, showEndpoints) {
        mapView.overlays.clear()
        val allPoints = mutableListOf<GeoPoint>()

        samples.groupBy { it.simSlot }.values.forEach { simSamples ->
            var currentRat: String? = null
            var segment = mutableListOf<GeoPoint>()
            fun flush() {
                if (drawTrack && segment.size >= 2 && currentRat != null) {
                    mapView.overlays.add(Polyline().apply {
                        setPoints(segment.toList())
                        outlinePaint.color = ratColor(currentRat!!)
                        outlinePaint.strokeWidth = 10f
                    })
                }
                segment = mutableListOf()
            }
            simSamples.sortedBy { it.timestampMs }.forEach { sample ->
                val lat = sample.latitude ?: return@forEach
                val lon = sample.longitude ?: return@forEach
                val point = GeoPoint(lat, lon)
                allPoints += point
                val rat = normalizedRat(sample)
                if (currentRat == null) currentRat = rat
                if (rat != currentRat) { flush(); currentRat = rat }
                segment += point
            }
            flush()
        }

        val ordered = samples.sortedBy { it.timestampMs }
        if (showEndpoints) ordered.firstOrNull()?.let { sample ->
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(sample.latitude!!, sample.longitude!!)
                title = "Start"
                icon = circleMarkerDrawable(context, AndroidColor.rgb(76, 175, 80), 14)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> selectedIsCurrent = false; selectedSample = sample; true }
            })
        }
        if (showEndpoints) ordered.lastOrNull()?.takeIf { it.timestampMs != ordered.firstOrNull()?.timestampMs }?.let { sample ->
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(sample.latitude!!, sample.longitude!!)
                title = "End"
                icon = circleMarkerDrawable(context, AndroidColor.rgb(244, 67, 54), 14)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> selectedIsCurrent = false; selectedSample = sample; true }
            })
        }

        currentPoint?.let { point ->
            if (allPoints.none { it.distanceToAsDouble(point) < 0.5 }) allPoints += point
            mapView.overlays.add(Marker(mapView).apply {
                position = point
                title = "Current location"
                snippet = currentLabel ?: "Live GPS"
                icon = circleMarkerDrawable(context, AndroidColor.rgb(33, 150, 243), 16)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> selectedIsCurrent = true; selectedSample = currentSample; true }
            })
        }

        // One map-tap path for live and history: current location wins, then nearest sample.
        // Tapping empty map dismisses the inspector instead of selecting a far-away point.
        mapView.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (currentPoint != null && currentPoint.distanceToAsDouble(p) <= 45.0) {
                    selectedIsCurrent = true
                    selectedSample = currentSample
                    return true
                }
                val nearest = samples.filter { it.latitude != null && it.longitude != null }
                    .minByOrNull { GeoPoint(it.latitude!!, it.longitude!!).distanceToAsDouble(p) }
                val distance = nearest?.let { GeoPoint(it.latitude!!, it.longitude!!).distanceToAsDouble(p) } ?: Double.MAX_VALUE
                selectedIsCurrent = false
                selectedSample = if (distance <= 80.0) nearest else null
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        // Event markers are added last so they stay above Start/End/current-location overlays.
        val focusedMarker = samples.firstOrNull { it.isMarker && it.markerId.isNotBlank() && it.markerId == focusMarkerId }
        samples.filter { it.isMarker && it.latitude != null && it.longitude != null }.forEach { sample ->
            mapView.overlays.add(Marker(mapView).apply {
                position = GeoPoint(sample.latitude!!, sample.longitude!!)
                title = "⚑ ${sample.eventType.ifBlank { "Issue" }}"
                snippet = "SIM ${sample.simSlot} · ${sample.operator} · ${normalizedRat(sample)} · RSRP ${sample.rsrp} dBm"
                icon = circleMarkerDrawable(context, AndroidColor.rgb(255, 152, 0), 16)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> selectedIsCurrent = false; selectedSample = sample; true }
            })
        }
        if (focusedMarker != null) {
            selectedIsCurrent = false
            selectedSample = focusedMarker
        }

        mapView.invalidate()
        mapView.post {
            when {
                focusedMarker?.latitude != null && focusedMarker.longitude != null -> {
                    mapView.controller.setCenter(GeoPoint(focusedMarker.latitude, focusedMarker.longitude))
                    if (mapView.zoomLevelDouble < 17.0) mapView.controller.setZoom(18.0)
                }
                liveFollow && currentPoint != null -> {
                    mapView.controller.setCenter(currentPoint)
                    if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(17.0)
                }
                allPoints.size == 1 -> { mapView.controller.setCenter(allPoints.first()); mapView.controller.setZoom(18.0) }
                allPoints.size > 1 -> runCatching { mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(allPoints), false, 80) }
            }
        }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().clipToBounds()
        )
        selectedSample?.let { sample ->
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 28.dp).fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (sample.isMarker) "${sample.eventSource.ifBlank { "MANUAL" }} · ${sample.eventType.ifBlank { "Marker" }}" else "Track point", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { selectedIsCurrent = false; selectedSample = null }, contentPadding = PaddingValues(0.dp)) { Text("Close") }
                    }
                    // Operator is essential context on dual-SIM maps, so keep it visible
                    // even when the optional OPERATOR row is disabled in Map Point Details.
                    Text("SIM ${sample.simSlot} · ${sample.operator}", style = MaterialTheme.typography.labelMedium)
                    mapPointRows(sample, detailFields - MapDetailField.OPERATOR - MapDetailField.SIM).forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (sample.isMarker && sample.eventNote.isNotBlank()) Text("Note: ${sample.eventNote}", style = MaterialTheme.typography.bodySmall)
                    if (sample.isMarker && sample.screenshot.isNotBlank()) {
                        val ctx = LocalContext.current
                        AssistChip(onClick = { openLocalScreenshot(ctx, sample.screenshot) }, label = { Text("Open screenshot") })
                    }
                }
            }
        }
        Text(
            "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.80f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun RecordingSamples(samples: List<TrackSample>, onMarkerClick: (TrackSample) -> Unit) {
    val context = LocalContext.current
    if (samples.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No samples") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(samples, key = { "${it.timestampMs}-${it.simSlot}-${it.subscriptionId}" }) { s ->
            Card(
                Modifier.fillMaxWidth().then(if (s.isMarker) Modifier.clickable { onMarkerClick(s) } else Modifier)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(s.timestampMs)), style = MaterialTheme.typography.labelLarge)
                        Text("SIM ${s.simSlot} · ${normalizedRat(s)}", style = MaterialTheme.typography.labelLarge)
                    }
                    Text("${s.operator} · PCI ${s.pci} · ARFCN ${s.arfcn}", style = MaterialTheme.typography.bodySmall)
                    Text("RSRP ${valueWithUnit(s.rsrp, "dBm")} · RSRQ ${valueWithUnit(s.rsrq, "dB")} · SINR ${valueWithUnit(s.sinr, "dB")}", style = MaterialTheme.typography.bodySmall)
                    Text(if (s.locationValid) "${formatCoord(s.latitude)}, ${formatCoord(s.longitude)}" else "Location --", style = MaterialTheme.typography.bodySmall)
                    if (s.isMarker) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { onMarkerClick(s) }, label = { Text("${s.eventSource.ifBlank { "MANUAL" }} · ${if (s.eventType.isBlank()) "Marker" else s.eventType} · Map") })
                            if (s.screenshot.isNotBlank()) {
                                AssistChip(onClick = { openLocalScreenshot(context, s.screenshot) }, label = { Text("Open screenshot") })
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class SignalMetric(val label: String, val unit: String) {
    RSRP("RSRP", "dBm"), RSRQ("RSRQ", "dB"), SINR("SINR", "dB"), RSSI("RSSI", "dBm")
}

@Composable
private fun SignalTrendSection(cell: CellData, points: List<SignalTrendPoint>) {
    var metric by remember(cell.subscriptionId) { mutableStateOf(SignalMetric.RSRP) }
    var selectedTimeMs by remember(cell.subscriptionId) { mutableStateOf<Long?>(null) }
    var chartWidthPx by remember { mutableStateOf(1) }

    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text("Signal trend · last 1 min", style = MaterialTheme.typography.labelLarge)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SignalMetric.entries.forEach { item ->
            val liveValue = when (item) {
                SignalMetric.RSRP -> cell.rsrp
                SignalMetric.RSRQ -> cell.rsrq
                SignalMetric.SINR -> cell.sinr
                SignalMetric.RSSI -> cell.rssi
            }
            val selected = metric == item
            Surface(
                modifier = Modifier.weight(1f).clickable { metric = item; selectedTimeMs = null },
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(vertical = 6.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(item.label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (liveValue == "--") "--" else "$liveValue ${item.unit}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
        }
    }

    val values = points.mapNotNull { point ->
        val value = when (metric) {
            SignalMetric.RSRP -> point.rsrp
            SignalMetric.RSRQ -> point.rsrq
            SignalMetric.SINR -> point.sinr
            SignalMetric.RSSI -> point.rssi
        }
        value?.let { point.timeMs to it }
    }

    if (values.size < 2) {
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            Text("Collecting ${metric.label} trend…", style = MaterialTheme.typography.bodySmall)
        }
    } else {
        val (axisMin, axisMax, axisStep) = when (metric) {
            SignalMetric.RSRP -> Triple(-140f, -60f, 20f)
            SignalMetric.RSRQ -> Triple(-30f, 0f, 10f)
            SignalMetric.SINR -> Triple(-20f, 40f, 15f)
            SignalMetric.RSSI -> Triple(-120f, -40f, 20f)
        }
        val lineColor = MaterialTheme.colorScheme.primary
        val gridColor = lineColor.copy(alpha = 0.14f)
        val markerColor = MaterialTheme.colorScheme.tertiary
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L
        val visibleValues = values.filter { it.first >= windowStart }
        val axisValues = generateSequence(axisMax) { previous ->
            (previous - axisStep).takeIf { it >= axisMin }
        }.toList().let { ticks -> if (ticks.lastOrNull() != axisMin) ticks + axisMin else ticks }

        val selectedPoint = selectedTimeMs?.let { target -> visibleValues.minByOrNull { kotlin.math.abs(it.first - target) } }
        if (selectedPoint != null) {
            val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(selectedPoint.first))
            Surface(
                modifier = Modifier.padding(top = 8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$timeText  ·  ${metric.label} ${String.format(Locale.US, "%.0f", selectedPoint.second)} ${metric.unit}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
                    )
                    TextButton(onClick = { selectedTimeMs = null }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("Live") }
                }
            }
        }

        fun selectByX(x: Float) {
            if (visibleValues.isEmpty() || chartWidthPx <= 0) return
            val fraction = (x / chartWidthPx.toFloat()).coerceIn(0f, 1f)
            val targetTime = windowStart + (fraction * 60_000f).toLong()
            selectedTimeMs = visibleValues.minByOrNull { kotlin.math.abs(it.first - targetTime) }?.first
        }

        Row(Modifier.fillMaxWidth().height(158.dp).padding(top = 10.dp)) {
            Column(
                modifier = Modifier.width(48.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                axisValues.forEach { tickValue ->
                    Text(
                        String.format(Locale.US, "%.0f", tickValue),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { chartWidthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(visibleValues, metric) {
                        detectTapGestures(
                            onTap = { offset -> selectByX(offset.x) }
                        )
                    }
                    .pointerInput(visibleValues, metric) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> selectByX(offset.x) },
                            onHorizontalDrag = { change, _ -> selectByX(change.position.x) }
                        )
                    }
            ) {
                axisValues.forEach { tickValue ->
                    val y = size.height - ((tickValue - axisMin) / (axisMax - axisMin)) * size.height
                    drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(0f, y), end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1f)
                }
                repeat(4) { i ->
                    val x = size.width * i / 3f
                    drawLine(gridColor, start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1f)
                }
                val path = Path()
                var started = false
                visibleValues.forEach { (time, value) ->
                    val x = ((time - windowStart).toFloat() / 60_000f).coerceIn(0f, 1f) * size.width
                    val normalized = ((value.coerceIn(axisMin, axisMax) - axisMin) / (axisMax - axisMin)).coerceIn(0f, 1f)
                    val y = size.height - normalized * size.height
                    if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                }
                if (started) drawPath(path, lineColor, style = Stroke(width = 3f))

                selectedPoint?.let { (time, value) ->
                    val x = ((time - windowStart).toFloat() / 60_000f).coerceIn(0f, 1f) * size.width
                    val normalized = ((value.coerceIn(axisMin, axisMax) - axisMin) / (axisMax - axisMin)).coerceIn(0f, 1f)
                    val y = size.height - normalized * size.height
                    drawLine(markerColor.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 2f)
                    drawCircle(markerColor, radius = 7f, center = androidx.compose.ui.geometry.Offset(x, y))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 48.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("60 s ago", style = MaterialTheme.typography.labelSmall)
            Text("Now", style = MaterialTheme.typography.labelSmall)
        }
        Text(
            if (selectedPoint == null) "${metric.label} · ${metric.unit} · Tap/drag chart to inspect" else "Tap Live to return to current value",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 48.dp, top = 2.dp)
        )
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
            Text("Band ${n.band} · PCI ${n.pci}", style = MaterialTheme.typography.bodySmall)
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
private fun SettingsScreen(settings: AppSettings, onUpdate: (AppSettings) -> Unit, onBack: () -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var page by remember { mutableStateOf("root") }
    var newIssue by remember { mutableStateOf("") }

    fun applySetting(next: AppSettings) {
        draft = next
        onUpdate(next)
    }

    fun navigateTo(next: String) {
        page = next
    }

    BackHandler(enabled = page != "root") {
        page = "root"
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(when (page) { "sampling" -> "Sampling"; "marker" -> "Marker Button"; "floating" -> "Floating Window"; "map" -> "Map Point Details"; "issues" -> "Issue Types"; else -> "Settings" }) },
            navigationIcon = { TextButton(onClick = { if (page == "root") onBack() else { page = "root" } }) { Text("Back") } }
        )
    }) { padding ->
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val enteringChild = targetState != "root"
                val direction = if (enteringChild) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                (slideIntoContainer(direction, tween(210)) + fadeIn(tween(150)))
                    .togetherWith(slideOutOfContainer(direction, tween(210)) + fadeOut(tween(130)))
            },
            label = "settingsNavigation",
            modifier = Modifier.padding(padding).fillMaxSize()
        ) { currentPage ->
            when (currentPage) {
                "sampling" -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    IntervalPicker("UI refresh interval", draft.uiRefreshMs) { applySetting(draft.copy(uiRefreshMs = it)) }
                    IntervalPicker("Recording interval", draft.recordIntervalMs) { applySetting(draft.copy(recordIntervalMs = it)) }
                }
                "marker" -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ActionPicker("Tap action", draft.tapAction) { applySetting(draft.copy(tapAction = it)) }
                    ActionPicker("Long press action", draft.longPressAction) { applySetting(draft.copy(longPressAction = it)) }
                    HorizontalDivider(); Text("After mark", style = MaterialTheme.typography.titleMedium)
                    SettingSwitch("Vibrate", draft.vibrateOnMark) { applySetting(draft.copy(vibrateOnMark = it)) }
                    SettingSwitch("Show toast", draft.toastOnMark) { applySetting(draft.copy(toastOnMark = it)) }
                    SettingSwitch("Play sound", draft.soundOnMark) { applySetting(draft.copy(soundOnMark = it)) }
                }
                "floating" -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val context = LocalContext.current
                    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
                    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        overlayGranted = android.provider.Settings.canDrawOverlays(context)
                    }
                    SettingSwitch("Enable floating window", draft.floatingWindowEnabled) { applySetting(draft.copy(floatingWindowEnabled = it)) }
                    SettingSwitch("Auto show while recording", draft.floatingAutoShowDuringRecording) { applySetting(draft.copy(floatingAutoShowDuringRecording = it)) }
                    SettingSwitch("Keep floating window when not recording", draft.floatingKeepWhenStopped) { applySetting(draft.copy(floatingKeepWhenStopped = it)) }
                    Text("When enabled, the overlay stays available after Stop so the next recording can be started without reopening CellTracker.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Opacity  ${(draft.floatingOpacity * 100).toInt()}%", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = draft.floatingOpacity,
                        onValueChange = { applySetting(draft.copy(floatingOpacity = it.coerceIn(0.20f, 1.00f))) },
                        valueRange = 0.20f..1.00f
                    )
                    Text("Only the background transparency changes; text and MARK stay clear.", style = MaterialTheme.typography.bodySmall)
                    SettingSwitch("Start in compact mode", draft.floatingStartCompact) { applySetting(draft.copy(floatingStartCompact = it)) }
                    SettingSwitch("Remember position", draft.floatingRememberPosition) { applySetting(draft.copy(floatingRememberPosition = it)) }
                    SettingSwitch("Capture screenshot when marking", draft.floatingCaptureScreenshotOnMark) { applySetting(draft.copy(floatingCaptureScreenshotOnMark = it)) }
                    if (draft.floatingCaptureScreenshotOnMark) {
                        SettingSwitch("Include floating window in screenshot", draft.floatingIncludeWindowInScreenshot) {
                            applySetting(draft.copy(floatingIncludeWindowInScreenshot = it))
                        }
                        Text(
                            if (draft.floatingIncludeWindowInScreenshot)
                                "The screenshot keeps the floating window visible, so the captured image also shows the live network information."
                            else
                                "The floating window is temporarily hidden before capture, producing a clean screenshot of the tested app.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text("Screenshots are captured only when a marker is created. Android will ask for screen-capture permission when recording starts.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Expanded mode fields", style = MaterialTheme.typography.titleMedium)
                    FloatingField.entries.forEach { field ->
                        SettingSwitch(field.label, field in draft.floatingExpandedFields) { checked ->
                            applySetting(draft.copy(floatingExpandedFields = if (checked) draft.floatingExpandedFields + field else draft.floatingExpandedFields - field))
                        }
                    }
                    HorizontalDivider()
                    Text("Compact mode fields", style = MaterialTheme.typography.titleMedium)
                    FloatingField.entries.forEach { field ->
                        SettingSwitch(field.label, field in draft.floatingCompactFields) { checked ->
                            applySetting(draft.copy(floatingCompactFields = if (checked) draft.floatingCompactFields + field else draft.floatingCompactFields - field))
                        }
                    }
                    HorizontalDivider()
                    Text(if (overlayGranted) "Overlay permission: Granted" else "Overlay permission: Required", style = MaterialTheme.typography.bodyMedium)
                    if (!overlayGranted) {
                        Button(onClick = {
                            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            permissionLauncher.launch(intent)
                        }) { Text("Grant overlay permission") }
                    }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Usage access for screenshot app names") }
                    Text("Usage access is optional. If granted, screenshot names can include the foreground app name; otherwise CellTracker uses 'Screen'.", style = MaterialTheme.typography.bodySmall)
                    Text("The window uses the recording Mark Target SIM and can be dragged, collapsed and used to create issue markers while another app is on screen.", style = MaterialTheme.typography.bodySmall)
                }
                "map" -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Changes are saved immediately.", style = MaterialTheme.typography.bodySmall)
                    MapDetailField.entries.forEach { field ->
                        SettingSwitch(field.label, field in draft.mapDetailFields) { checked ->
                            applySetting(draft.copy(mapDetailFields = if (checked) draft.mapDetailFields + field else draft.mapDetailFields - field))
                        }
                    }
                }
                "issues" -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Issue choices used by the upcoming Mark workflow. Changes are saved immediately.", style = MaterialTheme.typography.bodySmall)
                    draft.issueTypes.forEach { issue ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(issue); TextButton(onClick = { applySetting(draft.copy(issueTypes = draft.issueTypes - issue)) }) { Text("Remove") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(newIssue, { newIssue = it }, label = { Text("Custom issue") }, modifier = Modifier.weight(1f), singleLine = true)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            val v = newIssue.trim()
                            if (v.isNotEmpty() && v !in draft.issueTypes) {
                                applySetting(draft.copy(issueTypes = draft.issueTypes + v))
                                newIssue = ""
                            }
                        }) { Text("Add") }
                    }
                }
                else -> Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsMenuRow("Sampling", "UI refresh and recording intervals") { navigateTo("sampling") }
                    HorizontalDivider()
                    SettingsMenuRow("Marker Button", "Tap, long press and feedback") { navigateTo("marker") }
                    HorizontalDivider()
                    SettingsMenuRow("Floating Window", "Overlay info, opacity, compact mode and permission") { navigateTo("floating") }
                    HorizontalDivider()
                    SettingsMenuRow("Map Point Details", "Choose information shown for a map point") { navigateTo("map") }
                    HorizontalDivider()
                    SettingsMenuRow("Issue Types", "Manage built-in and custom issue choices") { navigateTo("issues") }
                }
            }
        }
    }
}

private fun Modifier.horizontalSwipe(
    enabled: Boolean = true,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
): Modifier = if (!enabled) this else pointerInput(onSwipeLeft, onSwipeRight) {
    var totalDrag = 0f
    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onHorizontalDrag = { change, dragAmount ->
            totalDrag += dragAmount
            change.consume()
        },
        onDragEnd = {
            when {
                totalDrag < -80f -> onSwipeLeft()
                totalDrag > 80f -> onSwipeRight()
            }
            totalDrag = 0f
        },
        onDragCancel = { totalDrag = 0f }
    )
}

@Composable
private fun SettingsMenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Text("›", style = MaterialTheme.typography.headlineSmall)
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

private fun mapPointRows(sample: TrackSample, fields: Set<MapDetailField>): List<Pair<String, String>> {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(sample.timestampMs))
    val values = linkedMapOf(
        MapDetailField.TIME to ("Time" to time), MapDetailField.SIM to ("SIM" to "SIM ${sample.simSlot}"),
        MapDetailField.OPERATOR to ("Operator" to sample.operator), MapDetailField.RAT to ("RAT" to normalizedRat(sample)),
        MapDetailField.RSRP to ("RSRP" to valueWithUnit(sample.rsrp, "dBm")), MapDetailField.RSRQ to ("RSRQ" to valueWithUnit(sample.rsrq, "dB")),
        MapDetailField.SINR to ("SINR" to valueWithUnit(sample.sinr, "dB")), MapDetailField.PCI to ("PCI" to sample.pci),
        MapDetailField.ARFCN to ("ARFCN" to sample.arfcn), MapDetailField.TAC to ("TAC" to sample.tac),
        MapDetailField.CELL_ID to ("Cell ID / NCI" to sample.cellId), MapDetailField.BAND to ("Band" to sample.band),
        MapDetailField.BANDWIDTH to ("Bandwidth" to sample.bandwidth), MapDetailField.RSSI to ("RSSI" to valueWithUnit(sample.rssi, "dBm")),
        MapDetailField.TIMING_ADVANCE to ("Timing Advance" to sample.timingAdvance), MapDetailField.CSI_RSRP to ("CSI-RSRP" to valueWithUnit(sample.csiRsrp, "dBm")),
        MapDetailField.CSI_RSRQ to ("CSI-RSRQ" to valueWithUnit(sample.csiRsrq, "dB")), MapDetailField.CSI_SINR to ("CSI-SINR" to valueWithUnit(sample.csiSinr, "dB")),
        MapDetailField.CQI to ("CQI" to sample.cqi), MapDetailField.SIGNAL_LEVEL to ("Signal level" to sample.level), MapDetailField.ASU to ("ASU" to sample.asuLevel),
        MapDetailField.CA to ("CA / EN-DC" to sample.carrierAggregation), MapDetailField.DATA_RAT to ("Data RAT" to sample.dataRat), MapDetailField.VOICE_RAT to ("Voice RAT" to sample.voiceRat), MapDetailField.ROAMING to ("Roaming" to sample.roaming),
        MapDetailField.MCC to ("MCC" to sample.mcc), MapDetailField.MNC to ("MNC" to sample.mnc), MapDetailField.LATITUDE to ("Latitude" to formatCoord(sample.latitude)),
        MapDetailField.LONGITUDE to ("Longitude" to formatCoord(sample.longitude)), MapDetailField.ACCURACY to ("Accuracy" to valueWithUnit(sample.accuracy, "m")),
        MapDetailField.SPEED to ("Speed" to valueWithUnit(sample.speedKmh, "km/h")), MapDetailField.BEARING to ("Bearing" to valueWithUnit(sample.bearing, "°"))
    )
    return MapDetailField.entries.filter { it in fields }.mapNotNull { values[it] }
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

private fun circleMarkerDrawable(context: android.content.Context, color: Int, sizeDp: Int): GradientDrawable {
    val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1), AndroidColor.WHITE)
        setSize(px, px)
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

private fun recordingDisplayName(fileName: String): String {
    val base = fileName.substringBeforeLast('.')
    val prefix = "CellTracker_"
    if (!base.startsWith(prefix)) return base
    val body = base.removePrefix(prefix)
    val match = Regex("^(.*)_(DualSIM|SIM(?:1|2)?)_\\d{8}_\\d{6}$").matchEntire(body)
    val task = match?.groupValues?.getOrNull(1).orEmpty()
    return if (task.isBlank()) "Untitled recording" else task.replace('_', ' ')
}

private fun formatCoord(v: Double?): String = v?.let { String.format(Locale.US, "%.6f", it) } ?: "--"
private fun valueWithUnit(value: String, unit: String): String = if (value == "--" || value.isBlank()) "--" else "$value $unit"
private fun formatElapsed(ms: Long): String {
    val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
