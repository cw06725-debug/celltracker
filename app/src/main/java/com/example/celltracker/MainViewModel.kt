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
import java.io.File

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
                        latestRecordingPath = status.latestPath ?: latestPathFromPrefs(),
                        recordingLocationValid = status.locationValid,
                        recordingLocationAgeMs = status.locationAgeMs
                    )
                }
            }
        }
        _state.value = _state.value.copy(latestRecordingPath = latestPathFromPrefs(), recordings = loadRecordings())
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
        val selectedId = _state.value.selectedSubscriptionId ?: return
        val both = _state.value.settings.recordScope == RecordScope.BOTH_SIMS && _state.value.sims.size > 1
        val intent = Intent(app, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_SUBSCRIPTION_ID, selectedId)
            .putExtra(RecordingService.EXTRA_BOTH_SIMS, both)
        ContextCompat.startForegroundService(app, intent)
    }

    fun stopRecording() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, RecordingService::class.java))
        viewModelScope.launch { delay(200); _state.value = _state.value.copy(recordings = loadRecordings()) }
    }

    fun setRecordScope(scope: RecordScope) {
        val updated = _state.value.settings.copy(recordScope = scope)
        settingsRepository.save(updated); _state.value = _state.value.copy(settings = updated)
    }

    fun deleteRecording(path: String) {
        File(path).delete()
        if (latestPathFromPrefs() == path) getApplication<Application>().getSharedPreferences("celltracker_recording", Application.MODE_PRIVATE).edit().remove("latest_path").apply()
        _state.value = _state.value.copy(recordings = loadRecordings(), latestRecordingPath = latestPathFromPrefs(), exportMessage = null)
    }

    fun deleteAllRecordings() {
        recordingsDir().listFiles()?.forEach { it.delete() }
        getApplication<Application>().getSharedPreferences("celltracker_recording", Application.MODE_PRIVATE).edit().remove("latest_path").apply()
        _state.value = _state.value.copy(recordings = emptyList(), latestRecordingPath = null, exportMessage = null)
    }

    fun exportRecording(path: String, mode: CsvExportMode) {
        viewModelScope.launch { try { _state.value = _state.value.copy(exportMessage = CsvExporter.exportLatest(getApplication(), path, mode)) } catch(e:Exception){ _state.value=_state.value.copy(error=e.message?:"Export failed") } }
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

    private fun recordingsDir() = File(getApplication<Application>().getExternalFilesDir(null), "recordings").apply { mkdirs() }

    private fun loadRecordings(): List<RecordingItem> = recordingsDir().listFiles { f -> f.extension.equals("csv", true) }?.mapNotNull { f ->
        try {
            val lines=f.readLines().filter { it.isNotBlank() }; if(lines.size<2) return@mapNotNull null
            val rows=lines.drop(1); val first=rows.first().split(','); val last=rows.last().split(',')
            val fmt=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            val start=fmt.parse(first[0].trim('"'))?.time ?: f.lastModified(); val end=fmt.parse(last[0].trim('"'))?.time ?: start
            val simEntries=rows.mapNotNull { r -> val x=r.split(','); if(x.size>3) "SIM ${x[1]} ${x[3].trim('"')}" else null }.distinct()
            val sims=simEntries.joinToString(" + ")
            RecordingItem(f.absolutePath,f.name,start,(end-start).coerceAtLeast(0),rows.size.toLong(),sims,simEntries.size.coerceAtLeast(1))
        } catch(_:Exception){ null }
    }?.sortedByDescending { it.startedAt } ?: emptyList()

    private fun latestPathFromPrefs(): String? = getApplication<Application>()
        .getSharedPreferences("celltracker_recording", Application.MODE_PRIVATE)
        .getString("latest_path", null)
}
