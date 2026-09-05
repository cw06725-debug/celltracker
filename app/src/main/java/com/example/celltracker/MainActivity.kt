package com.example.celltracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
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

                if (showSettings) {
                    SettingsScreen(
                        settings = state.settings,
                        onSave = { vm.updateSettings(it); showSettings = false },
                        onBack = { showSettings = false }
                    )
                } else {
                    MainScreen(
                        state = state,
                        onSelectSim = vm::selectSubscription,
                        onStartRecording = vm::startRecording,
                        onStopRecording = vm::stopRecording,
                        onExport = vm::exportLatestCsv,
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
    onStopRecording: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var neighborsExpanded by remember { mutableStateOf(false) }
    val selected = state.sims.firstOrNull { it.subscriptionId == state.selectedSubscriptionId } ?: state.sims.firstOrNull()
    val c = selected?.servingCell ?: CellData()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("CellTracker 0.2") },
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
                        Text(if (neighborsExpanded) "▼ Neighbor Cells (${selected?.neighbors?.size ?: 0})" else "▶ Neighbor Cells (${selected?.neighbors?.size ?: 0})", style = MaterialTheme.typography.titleMedium)
                    }
                    AnimatedVisibility(neighborsExpanded) {
                        Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            HorizontalDivider()
                            if (selected?.neighbors.isNullOrEmpty()) Text("No neighbor cells reported")
                            selected?.neighbors?.forEach { n ->
                                Text("${n.rat}  PCI ${n.pci}  ARFCN ${n.arfcn}  RSRP ${valueWithUnit(n.rsrp, "dBm")}  RSRQ ${valueWithUnit(n.rsrq, "dB")}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            val l = state.location
            InfoCard("Location") {
                Field("Latitude", l.latitude)
                Field("Longitude", l.longitude)
                Field("Altitude", l.altitude)
                Field("Accuracy", l.accuracy)
                Field("Speed", l.speedKmh)
                Field("Bearing", l.bearing)
            }

            InfoCard("Recording") {
                Field("Status", if (state.isRecording) "Recording" else "Stopped")
                Field("Elapsed", formatElapsed(state.recordingElapsedMs))
                Field("Samples", state.recordingSamples.toString())
                Field("Record interval", "${state.settings.recordIntervalMs / 1000.0} s")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isRecording) Button(onClick = onStopRecording) { Text("Stop") }
                    else Button(onClick = onStartRecording) { Text("Start Recording") }
                    OutlinedButton(onClick = onExport, enabled = state.latestRecordingPath != null) { Text("Export CSV") }
                }
            }

            state.exportMessage?.let {
                AssistChip(onClick = onDismissMessage, label = { Text(it) })
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Last cellular update: ${state.lastUpdated}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: AppSettings, onSave: (AppSettings) -> Unit, onBack: () -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            actions = { TextButton(onClick = { onSave(draft) }) { Text("Save") } }
        )
    }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Sampling", style = MaterialTheme.typography.titleLarge)
            IntervalPicker("UI refresh interval", draft.uiRefreshMs) { draft = draft.copy(uiRefreshMs = it) }
            IntervalPicker("Recording interval", draft.recordIntervalMs) { draft = draft.copy(recordIntervalMs = it) }
            HorizontalDivider()
            Text("Marker Button", style = MaterialTheme.typography.titleLarge)
            ActionPicker("Tap action", draft.tapAction) { draft = draft.copy(tapAction = it) }
            ActionPicker("Long press action", draft.longPressAction) { draft = draft.copy(longPressAction = it) }
            Text("Marker actions are saved now and will be connected to the map/overlay marker button in later versions.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("After mark", style = MaterialTheme.typography.titleMedium)
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
            values.forEach { v ->
                FilterChip(
                    selected = selected == v,
                    onClick = { onSelected(v) },
                    label = { Text(if (v < 1000) "0.5s" else "${v / 1000}s") }
                )
            }
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
                MarkerAction.entries.forEach { action ->
                    DropdownMenuItem(text = { Text(action.label) }, onClick = { onSelected(action); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Field(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun valueWithUnit(value: String, unit: String): String = if (value == "--") value else "$value $unit"

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
