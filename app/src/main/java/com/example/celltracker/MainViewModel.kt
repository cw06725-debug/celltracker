package com.example.celltracker

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
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
                        recordingMarkTargetSubscriptionId = status.markTargetSubscriptionId.takeIf { it >= 0 } ?: _state.value.recordingMarkTargetSubscriptionId,
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
                val loopStartedAt = System.currentTimeMillis()
                try {
                    val sims = cellular.readAllSims()
                    val selected = _state.value.selectedSubscriptionId
                    val selectedId = when {
                        selected != null && sims.any { it.subscriptionId == selected } -> selected
                        sims.isNotEmpty() -> sims.first().subscriptionId
                        else -> null
                    }
                    val dataSim = defaultDataSubscriptionId()
                    val dataNet = currentDataNetwork()
                    NetworkStore.dataSimSubscriptionId = dataSim ?: -1
                    NetworkStore.dataNetwork = dataNet
                    val now = System.currentTimeMillis()
                    val trends = appendSignalTrends(_state.value.signalTrendBySubscription, sims, now)
                    _state.value = _state.value.copy(
                        sims = sims,
                        selectedSubscriptionId = selectedId,
                        markTargetSubscriptionId = _state.value.markTargetSubscriptionId?.takeIf { id -> sims.any { it.subscriptionId == id } } ?: selectedId,
                        dataSimSubscriptionId = dataSim,
                        dataNetwork = dataNet,
                        signalTrendBySubscription = trends,
                        lastUpdated = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now)),
                        error = null
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = e.message ?: "Unable to read cellular info")
                }
                if (_state.value.isRecording) {
                    val st = RecordingState.status.value
                    _state.value = _state.value.copy(recordingElapsedMs = System.currentTimeMillis() - st.startedAt)
                }
                // Treat uiRefreshMs as the target cadence. Slow modem callbacks are bounded
                // in CellularRepository, and their elapsed time is subtracted here.
                val elapsed = System.currentTimeMillis() - loopStartedAt
                delay((_state.value.settings.uiRefreshMs - elapsed).coerceAtLeast(50L))
            }
        }
    }

    private fun appendSignalTrends(
        existing: Map<Int, List<SignalTrendPoint>>,
        sims: List<SimCellState>,
        now: Long
    ): Map<Int, List<SignalTrendPoint>> {
        val cutoff = now - 60_000L
        val next = existing.mapValues { (_, points) -> points.filter { it.timeMs >= cutoff }.takeLast(180) }.toMutableMap()
        sims.forEach { sim ->
            val c = sim.servingCell
            val point = SignalTrendPoint(
                timeMs = now,
                rsrp = c.rsrp.toFloatOrNull(),
                rsrq = c.rsrq.toFloatOrNull(),
                sinr = c.sinr.toFloatOrNull(),
                rssi = c.rssi.toFloatOrNull()
            )
            val updated = (next[sim.subscriptionId].orEmpty() + point)
                .filter { it.timeMs >= cutoff }
                .takeLast(180)
            next[sim.subscriptionId] = updated
        }
        return next
    }

    fun selectSubscription(id: Int) {
        _state.value = _state.value.copy(selectedSubscriptionId = id)
    }

    fun setMarkTargetSubscription(id: Int) {
        if (!_state.value.isRecording && _state.value.sims.any { it.subscriptionId == id }) {
            _state.value = _state.value.copy(markTargetSubscriptionId = id)
        }
    }

    fun updateSettings(settings: AppSettings) {
        settingsRepository.save(settings)
        _state.value = _state.value.copy(settings = settings)
        restartCellLoop()
    }

    fun startRecording(taskName: String = "") {
        val app = getApplication<Application>()
        val selectedId = _state.value.selectedSubscriptionId ?: return
        val both = _state.value.settings.recordScope == RecordScope.BOTH_SIMS && _state.value.sims.size > 1
        val markTargetId = if (both) (_state.value.markTargetSubscriptionId ?: selectedId) else selectedId
        val intent = Intent(app, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_SUBSCRIPTION_ID, selectedId)
            .putExtra(RecordingService.EXTRA_BOTH_SIMS, both)
            .putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, markTargetId)
            .putExtra(RecordingService.EXTRA_TASK_NAME, taskName.trim())
        _state.value = _state.value.copy(recordingMarkTargetSubscriptionId = markTargetId)
        ContextCompat.startForegroundService(app, intent)
    }

    fun stopRecording() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, RecordingService::class.java))
        viewModelScope.launch { delay(200); _state.value = _state.value.copy(recordings = loadRecordings()) }
    }

    fun markEvent(issueType: String, note: String = "") {
        val app = getApplication<Application>()
        if (!_state.value.isRecording) return
        val selectedId = _state.value.recordingMarkTargetSubscriptionId ?: _state.value.markTargetSubscriptionId ?: _state.value.selectedSubscriptionId ?: return
        val intent = Intent(app, RecordingService::class.java).apply {
            action = RecordingService.ACTION_MARK
            putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, selectedId)
            putExtra(RecordingService.EXTRA_EVENT_TYPE, issueType)
            putExtra(RecordingService.EXTRA_EVENT_NOTE, note)
        }
        ContextCompat.startForegroundService(app, intent)
        val settings = _state.value.settings
        if (settings.toastOnMark) {
            runCatching { Toast.makeText(app, "Marked: $issueType", Toast.LENGTH_SHORT).show() }
        }
        if (settings.vibrateOnMark) {
            runCatching {
                val vibrator = app.getSystemService(Vibrator::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(60L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(60L)
                }
            }
        }
        if (settings.soundOnMark) {
            runCatching {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).apply {
                    startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                    viewModelScope.launch {
                        delay(150)
                        runCatching { release() }
                    }
                }
            }
        }
    }

    fun setRecordScope(scope: RecordScope) {
        val updated = _state.value.settings.copy(recordScope = scope)
        settingsRepository.save(updated); _state.value = _state.value.copy(settings = updated)
    }

    private fun defaultDataSubscriptionId(): Int? = runCatching {
        SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }.getOrNull()

    private fun currentDataNetwork(): String {
        val app = getApplication<Application>()
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return "--"
        val network = cm.activeNetwork ?: return "No network"
        val caps = cm.getNetworkCapabilities(network) ?: return "No network"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val subId = defaultDataSubscriptionId()
                val tm = app.getSystemService(TelephonyManager::class.java)
                val type = runCatching { if (subId != null) tm.createForSubscriptionId(subId).dataNetworkType else tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
                when (type) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
                    TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
                    else -> "Cellular"
                }
            }
            else -> "Other"
        }
    }

    fun deleteRecording(path: String) {
        File(path).delete()
        if (latestPathFromPrefs() == path) getApplication<Application>().getSharedPreferences("celltracker_recording", Application.MODE_PRIVATE).edit().remove("latest_path").apply()
        _state.value = _state.value.copy(recordings = loadRecordings(), latestRecordingPath = latestPathFromPrefs(), exportResult = null)
    }

    fun deleteAllRecordings() {
        recordingsDir().listFiles()?.forEach { it.delete() }
        getApplication<Application>().getSharedPreferences("celltracker_recording", Application.MODE_PRIVATE).edit().remove("latest_path").apply()
        _state.value = _state.value.copy(recordings = emptyList(), latestRecordingPath = null, exportResult = null)
    }

    fun exportRecording(path: String, mode: CsvExportMode) {
        viewModelScope.launch { try { _state.value = _state.value.copy(exportResult = CsvExporter.exportLatest(getApplication(), path, mode), error = null) } catch(e:Exception){ _state.value=_state.value.copy(error=e.message?:"Export failed") } }
    }

    fun exportLatestCsv(mode: CsvExportMode) {
        val path = _state.value.latestRecordingPath ?: latestPathFromPrefs()
        if (path == null) {
            _state.value = _state.value.copy(error = "No recording available")
            return
        }
        viewModelScope.launch {
            try {
                val result = CsvExporter.exportLatest(getApplication(), path, mode)
                _state.value = _state.value.copy(exportResult = result, error = null)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Export failed")
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(exportResult = null)
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
