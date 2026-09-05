package com.example.celltracker

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val cellular = CellularRepository(app)
    private val location = LocationRepository(app)
    private val settingsRepository = SettingsRepository(app)

    private val _state = MutableStateFlow(AppState(settings = settingsRepository.load()))
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var cellJob: Job? = null
    private var locationJob: Job? = null
    private var recordingStatusJob: Job? = null

    fun start() {
        restartCellLoop()
        if (locationJob?.isActive != true) {
            locationJob = viewModelScope.launch {
                location.locations().collect { loc -> _state.value = _state.value.copy(location = loc) }
            }
        }
        if (recordingStatusJob?.isActive != true) {
            recordingStatusJob = viewModelScope.launch {
                RecordingState.status.collect { status ->
                    _state.value = _state.value.copy(
                        isRecording = status.isRecording,
                        recordingElapsedMs = if (status.isRecording) System.currentTimeMillis() - status.startedAt else _state.value.recordingElapsedMs,
                        recordingSamples = status.totalSamples,
                        recordingSamplesBySubscription = status.samplesBySubscription,
                        latestRecordingPath = status.latestPath ?: latestPathFromPrefs()
                    )
                }
            }
        }
        _state.value = _state.value.copy(latestRecordingPath = latestPathFromPrefs())
    }

    private fun restartCellLoop() {
        cellJob?.cancel()
        cellJob = viewModelScope.launch {
            while (true) {
                try {
                    val sims = cellular.readAllSims()
                    val selected = _state.value.selectedSubscriptionId
                    val selectedId = when {
                        selected != null && sims.any { it.subscriptionId == selected } -> selected
                        sims.isNotEmpty() -> sims.first().subscriptionId
                        else -> null
                    }
                    _state.value = _state.value.copy(
                        sims = sims,
                        selectedSubscriptionId = selectedId,
                        lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
                        error = null
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = e.message ?: "Unable to read cellular info")
                }
                delay(_state.value.settings.uiRefreshMs)
                if (_state.value.isRecording) {
                    val st = RecordingState.status.value
                    _state.value = _state.value.copy(recordingElapsedMs = System.currentTimeMillis() - st.startedAt)
                }
            }
        }
    }

    fun selectSubscription(id: Int) {
        _state.value = _state.value.copy(selectedSubscriptionId = id)
    }

    fun updateSettings(settings: AppSettings) {
        settingsRepository.save(settings)
        _state.value = _state.value.copy(settings = settings)
        restartCellLoop()
    }

    fun startRecording() {
        val app = getApplication<Application>()
        ContextCompat.startForegroundService(app, Intent(app, RecordingService::class.java))
    }

    fun stopRecording() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, RecordingService::class.java))
    }

    fun exportLatestCsv(mode: CsvExportMode) {
        val path = _state.value.latestRecordingPath ?: latestPathFromPrefs()
        if (path == null) {
            _state.value = _state.value.copy(exportMessage = "No recording available")
            return
        }
        viewModelScope.launch {
            try {
                val result = CsvExporter.exportLatest(getApplication(), path, mode)
                _state.value = _state.value.copy(exportMessage = result, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Export failed")
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(exportMessage = null)
    }

    private fun latestPathFromPrefs(): String? = getApplication<Application>()
        .getSharedPreferences("celltracker_recording", Application.MODE_PRIVATE)
        .getString("latest_path", null)
}
