package com.example.celltracker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class PingTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cellular: CellularRepository
    private lateinit var location: LocationRepository
    private lateinit var repository: PingRepository
    private var testJob: Job? = null
    private var activeProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var resultFile: File? = null
    private var sessionStartedAt = 0L
    private var ownedRecordingPath: String? = null
    private var finalized = false

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this)
        location = LocationRepository(this)
        repository = PingRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTest("Stopped")
            ACTION_START -> if (testJob?.isActive != true) startTest(readConfig(intent), intent.getIntExtra(EXTRA_SELECTED_SUBSCRIPTION_ID, -1))
        }
        return START_NOT_STICKY
    }

    private fun startTest(config: PingTestConfig, selectedSubscriptionId: Int) {
        startAsForeground(config.taskName, "Preparing ping test")
        acquireWakeLock()
        repository.saveConfig(config)
        sessionStartedAt = System.currentTimeMillis()
        resultFile = repository.createSessionFile(config, sessionStartedAt)
        finalized = false
        ownedRecordingPath = null
        PingTestStore.state.value = PingTestState(
            isRunning = true,
            config = config,
            startedAt = sessionStartedAt,
            statusMessage = "Starting",
            resultPath = resultFile?.absolutePath,
            selectedSubscriptionId = selectedSubscriptionId.takeIf { it >= 0 }
        )

        // Keep GPS updates alive independently of the Activity/ViewModel lifecycle.
        scope.launch { runCatching { location.locations().collectLatest { } } }
        testJob = scope.launch {
            var finalStatus = "Completed"
            try {
                val dataSubAtStart = defaultDataSubscriptionId()
                val recordingSub = dataSubAtStart?.takeIf { it >= 0 } ?: selectedSubscriptionId
                if (config.autoRecord && !isRecordingActive() && recordingSub >= 0) {
                    startOwnedRecording(config.taskName, recordingSub)
                }

                var consecutiveFailures = 0
                var highLatencyActive = false
                var timeoutActive = false
                for (sequence in 1..config.count) {
                    ensureActive()
                    val cycleStartedAt = System.currentTimeMillis()
                    val result = runSinglePing(config.host, config.timeoutMs)
                    val success = result.first != null
                    consecutiveFailures = if (success) 0 else consecutiveFailures + 1

                    var eventType = ""
                    var eventNote = ""
                    val latency = result.first
                    if (success && latency != null) {
                        if (latency >= config.highLatencyThresholdMs) {
                            if (!highLatencyActive) {
                                eventType = "HIGH_PING"
                                eventNote = "Target=${config.host}, sequence=$sequence, RTT=${formatLatency(latency)}, threshold=${formatLatency(config.highLatencyThresholdMs)}"
                            }
                            highLatencyActive = true
                        } else {
                            highLatencyActive = false
                        }
                        timeoutActive = false
                    } else if (consecutiveFailures >= 3 && !timeoutActive) {
                        eventType = "PING_TIMEOUT"
                        eventNote = "Target=${config.host}, sequence=$sequence, $consecutiveFailures consecutive ping failures"
                        timeoutActive = true
                    }

                    val snapshot = captureSnapshot(selectedSubscriptionId)
                    val sample = PingSample(
                        sequence = sequence,
                        timestampMs = cycleStartedAt,
                        latencyMs = latency,
                        success = success,
                        message = result.second,
                        consecutiveFailures = consecutiveFailures,
                        eventSource = if (eventType.isNotBlank()) "AUTO" else "",
                        eventType = eventType,
                        snapshot = snapshot
                    )
                    val recordingPath = linkedRecordingPath()
                    resultFile?.let { repository.appendSample(it, config, sessionStartedAt, recordingPath, sample) }
                    if (eventType.isNotBlank()) markRecordingEvent(sample, eventNote)

                    val previous = PingTestStore.state.value
                    val samples = (previous.samples + sample).takeLast(10_000)
                    PingTestStore.state.value = previous.copy(
                        isRunning = true,
                        completed = sequence,
                        successCount = samples.count { it.success },
                        failureCount = samples.count { !it.success },
                        samples = samples,
                        statusMessage = if (success) "Reply ${formatLatency(latency)}" else "Timeout / failed",
                        recordingPath = recordingPath,
                        consecutiveFailures = consecutiveFailures,
                        dataSimSubscriptionId = snapshot.dataSimSubscriptionId,
                        dataNetwork = snapshot.dataNetwork
                    )
                    updateNotification(config.taskName, "$sequence/${config.count} · ${if (success) formatLatency(latency) else "Timeout"}")
                    if (sequence < config.count) {
                        val spent = System.currentTimeMillis() - cycleStartedAt
                        delay((config.intervalMs - spent).coerceAtLeast(0L))
                    }
                }
            } catch (cancelled: CancellationException) {
                finalStatus = "Stopped"
                throw cancelled
            } catch (error: Exception) {
                finalStatus = "Error: ${error.message ?: "Ping failed"}"
            } finally {
                finishSession(finalStatus)
            }
        }
    }

    private fun linkedRecordingPath(): String? {
        ownedRecordingPath?.let { return it }
        if (!isRecordingActive()) return null
        return RecordingState.status.value.latestPath
            ?: getSharedPreferences("celltracker_recording", MODE_PRIVATE).getString("active_path", null)
    }

    private suspend fun startOwnedRecording(taskName: String, subscriptionId: Int) {
        val intent = Intent(this, RecordingService::class.java)
            .putExtra(RecordingService.EXTRA_SUBSCRIPTION_ID, subscriptionId)
            .putExtra(RecordingService.EXTRA_BOTH_SIMS, false)
            .putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, subscriptionId)
            .putExtra(RecordingService.EXTRA_TASK_NAME, taskName)
        ContextCompat.startForegroundService(this, intent)
        repeat(15) {
            delay(100L)
            val status = RecordingState.status.value
            if (status.isRecording && status.taskName == taskName && status.latestPath != null) {
                ownedRecordingPath = status.latestPath
                return
            }
        }
    }

    private fun isRecordingActive(): Boolean {
        if (RecordingState.status.value.isRecording) return true
        return getSharedPreferences("celltracker_recording", MODE_PRIVATE).getBoolean("active_recording", false)
    }

    private suspend fun captureSnapshot(selectedSubscriptionId: Int): PingNetworkSnapshot {
        val sims = runCatching { cellular.readAllSims() }.getOrDefault(emptyList())
        val dataSub = defaultDataSubscriptionId()
        val sim = sims.firstOrNull { it.subscriptionId == dataSub }
            ?: sims.firstOrNull { it.subscriptionId == selectedSubscriptionId }
            ?: sims.firstOrNull()
        val cell = sim?.servingCell
        val gps = LocationStore.latest.value
        val dataNetwork = currentDataNetwork(dataSub)
        NetworkStore.dataSimSubscriptionId = dataSub ?: -1
        NetworkStore.dataNetwork = dataNetwork
        return PingNetworkSnapshot(
            subscriptionId = cell?.subscriptionId ?: sim?.subscriptionId ?: -1,
            simSlot = cell?.simSlotIndex ?: sim?.simSlotIndex ?: -1,
            operator = cell?.operator.orEmpty().ifBlank { "--" },
            rat = cell?.rat.orEmpty().ifBlank { "--" },
            displayRat = cell?.displayRat.orEmpty().ifBlank { cell?.rat.orEmpty().ifBlank { "--" } },
            mcc = cell?.mcc.orEmpty().ifBlank { "--" },
            mnc = cell?.mnc.orEmpty().ifBlank { "--" },
            tac = cell?.tac.orEmpty().ifBlank { "--" },
            cellId = cell?.cellId.orEmpty().ifBlank { "--" },
            pci = cell?.pci.orEmpty().ifBlank { "--" },
            arfcn = cell?.arfcn.orEmpty().ifBlank { "--" },
            band = cell?.band.orEmpty().ifBlank { "--" },
            bandwidth = cell?.bandwidth.orEmpty().ifBlank { "--" },
            rsrp = cell?.rsrp.orEmpty().ifBlank { "--" },
            rsrq = cell?.rsrq.orEmpty().ifBlank { "--" },
            sinr = cell?.sinr.orEmpty().ifBlank { "--" },
            rssi = cell?.rssi.orEmpty().ifBlank { "--" },
            carrierAggregation = cell?.carrierAggregation.orEmpty().ifBlank { "--" },
            dataSimSubscriptionId = dataSub,
            dataNetwork = dataNetwork,
            latitude = gps.latitude.toDoubleOrNull().takeIf { gps.isValid },
            longitude = gps.longitude.toDoubleOrNull().takeIf { gps.isValid },
            speedKmh = gps.speedKmh,
            gpsAccuracy = gps.accuracy
        )
    }

    private fun markRecordingEvent(sample: PingSample, note: String) {
        if (!isRecordingActive()) return
        val subId = sample.snapshot.subscriptionId.takeIf { it >= 0 }
            ?: RecordingState.status.value.markTargetSubscriptionId.takeIf { it >= 0 }
            ?: return
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_MARK
            putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, subId)
            putExtra(RecordingService.EXTRA_EVENT_TYPE, sample.eventType)
            putExtra(RecordingService.EXTRA_EVENT_NOTE, note)
            putExtra(RecordingService.EXTRA_EVENT_SOURCE, "AUTO")
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private suspend fun runSinglePing(host: String, timeoutMs: Long): Pair<Double?, String> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            val timeoutSeconds = kotlin.math.ceil(timeoutMs / 1000.0).toInt().coerceAtLeast(1)
            process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSeconds.toString(), host)
                .redirectErrorStream(true)
                .start()
            activeProcess = process
            val completed = process.waitFor(timeoutMs + 1_500L, TimeUnit.MILLISECONDS)
            if (!completed) process.destroyForcibly()
            val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            val latency = Regex("time[=<]([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val message = output.lineSequence().firstOrNull {
                it.contains("bytes from", true) || it.contains("timeout", true) || it.contains("unreachable", true) || it.contains("unknown host", true)
            }.orEmpty().ifBlank { if (completed) "No reply" else "Timeout" }
            latency to message
        } catch (error: Exception) {
            null to (error.message ?: "Ping failed")
        } finally {
            runCatching { process?.destroy() }
            activeProcess = null
        }
    }

    private fun stopTest(status: String) {
        val state = PingTestStore.state.value
        if (state.isRunning) PingTestStore.state.value = state.copy(statusMessage = "Stopping")
        testJob?.cancel(CancellationException(status)) ?: finishSession(status)
    }

    private fun finishSession(status: String) {
        if (finalized) return
        finalized = true
        val endedAt = System.currentTimeMillis()
        val previous = PingTestStore.state.value
        val finalStatus = if (status == "Completed" && previous.completed < previous.config.count) "Stopped" else status
        PingTestStore.state.value = previous.copy(isRunning = false, endedAt = endedAt, statusMessage = finalStatus)
        resultFile?.let { repository.finalizeSession(it, sessionStartedAt, endedAt, finalStatus, ownedRecordingPath ?: previous.recordingPath) }
        val ownedPath = ownedRecordingPath
        val recording = RecordingState.status.value
        if (ownedPath != null && recording.isRecording && recording.latestPath == ownedPath) {
            stopService(Intent(this, RecordingService::class.java))
        }
        ownedRecordingPath = null
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun defaultDataSubscriptionId(): Int? = runCatching {
        SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }.getOrNull()

    private fun currentDataNetwork(dataSub: Int?): String {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return "--"
        val network = connectivity.activeNetwork ?: return "No network"
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return "No network"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val manager = getSystemService(TelephonyManager::class.java)
                val type = runCatching { dataSub?.let { manager.createForSubscriptionId(it).dataNetworkType } ?: manager.dataNetworkType }
                    .getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
                when (type) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA -> "3G"
                    TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
                    else -> "Cellular"
                }
            }
            else -> "Other"
        }
    }

    private fun startAsForeground(title: String, text: String) {
        val notification = buildNotification(title, text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(title: String, text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        val stopIntent = Intent(this, PingTestService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = openIntent?.let {
            PendingIntent.getActivity(this, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title.ifBlank { "CellTracker Ping Test" })
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CellTracker Ping Test", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:PingTest").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        activeProcess?.destroyForcibly()
        testJob?.cancel()
        if (!finalized && resultFile != null) finishSession("Stopped")
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun readConfig(intent: Intent): PingTestConfig {
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "8.8.8.8" }
        return PingTestConfig(
            taskName = intent.getStringExtra(EXTRA_TASK_NAME).orEmpty().ifBlank { "Ping_$host" },
            host = host,
            count = intent.getIntExtra(EXTRA_COUNT, 20).coerceIn(1, 10_000),
            intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 1000L).coerceIn(250L, 60_000L),
            timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, 2000L).coerceIn(250L, 60_000L),
            highLatencyThresholdMs = intent.getDoubleExtra(EXTRA_THRESHOLD_MS, 300.0).coerceIn(1.0, 60_000.0),
            autoRecord = intent.getBooleanExtra(EXTRA_AUTO_RECORD, true)
        )
    }

    private fun formatLatency(value: Double?): String = value?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--"

    companion object {
        const val ACTION_START = "com.example.celltracker.PING_START"
        const val ACTION_STOP = "com.example.celltracker.PING_STOP"
        const val EXTRA_TASK_NAME = "ping_task_name"
        const val EXTRA_HOST = "ping_host"
        const val EXTRA_COUNT = "ping_count"
        const val EXTRA_INTERVAL_MS = "ping_interval_ms"
        const val EXTRA_TIMEOUT_MS = "ping_timeout_ms"
        const val EXTRA_THRESHOLD_MS = "ping_threshold_ms"
        const val EXTRA_AUTO_RECORD = "ping_auto_record"
        const val EXTRA_SELECTED_SUBSCRIPTION_ID = "ping_selected_subscription_id"
        const val CHANNEL_ID = "celltracker_ping"
        const val NOTIFICATION_ID = 1003
    }
}
