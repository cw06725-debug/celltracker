package com.example.celltracker

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { vm.start() }

                LaunchedEffect(Unit) {
                    launcher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    ))
                }

                MainScreen(state = state, onRefresh = { vm.start() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: AppState, onRefresh: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("CellTracker 0.1") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val c = state.servingCell
            InfoCard("Network") {
                Field("Operator", c.operator)
                Field("RAT", c.rat)
                Field("Registered", if (c.registered) "Yes" else "No")
                Field("Neighbors", state.neighborCount.toString())
            }
            InfoCard("Serving Cell") {
                Field("MCC / MNC", "${c.mcc} / ${c.mnc}")
                Field("TAC", c.tac)
                Field(if (c.rat == "NR") "NCI" else "Cell ID", c.cellId)
                Field("PCI", c.pci)
                Field(if (c.rat == "NR") "NR-ARFCN" else "EARFCN", c.arfcn)
            }
            InfoCard("Signal") {
                Field(if (c.rat == "NR") "SS-RSRP" else "RSRP", c.rsrp)
                Field(if (c.rat == "NR") "SS-RSRQ" else "RSRQ", c.rsrq)
                Field(if (c.rat == "NR") "SS-SINR" else "SINR", c.sinr)
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
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("Last cellular update: ${state.lastUpdated}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRefresh, modifier = Modifier.align(Alignment.End)) { Text("Refresh") }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                HorizontalDivider()
                content()
            }
        )
    }
}

@Composable
private fun Field(name: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
