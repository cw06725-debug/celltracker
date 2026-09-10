package com.example.celltracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

class BasementTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cellular: CellularRepository
    private var wakeLock: PowerManager.WakeLock? = null
    private var sampleJob: Job? = null
    private var stageJob: Job? = null
    private var recoveryJob: Job? = null

    private var config = BasementTestConfig()
    private var sessionStartedAt = 0L
    private var sessionDirName = ""
    private var lastSample: BasementNetworkSample? = null
    private var noServiceStartedAt: Long? = null
    private var startHadNr: Boolean? = null
    private var startHadLte: Boolean? = null

    private val samples = mutableListOf<BasementNetworkSample>()
    private val events = mutableListOf<BasementEvent>()
    private val pingSamples = mutableListOf<BasementPingSample>()
    private val pointResults = linkedMapOf<String, BasementPointResult>()
    private val segmentStart = linkedMapOf<String, Long>()
    private val segmentEnd = linkedMapOf<String, Long>()

    private var overlay: android.view.View? = null
    private var overlayTitle: TextView? = null
    private var overlayNetwork: TextView? = null
    private var overlaySub: TextView? = null
    private var overlayButton: Button? = null

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTest(readConfig(intent))
            ACTION_PRIMARY -> handlePrimaryAction()
            ACTION_ABORT -> finishTest("ABORTED")
            ACTION_FINISH -> finishTest("COMPLETED")
        }
        return START_NOT_STICKY
    }

    private fun startTest(newConfig: BasementTestConfig) {
        if (BasementTestStore.state.value.isRunning) return
        config = newConfig
        sessionStartedAt = System.currentTimeMillis()
        sessionDirName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartedAt))
        lastSample = null
        noServiceStartedAt = null
        startHadNr = null
        startHadLte = null
        samples.clear(); events.clear(); pingSamples.clear(); pointResults.clear()
        segmentStart.clear(); segmentEnd.clear()
        segmentStart[SEG_START_B1] = sessionStartedAt

        acquireWakeLock()
        startForegroundServiceNotification("START → B1")
        BasementTestStore.state.value = BasementLiveState(
            isRunning = true,
            stage = BasementStage.START_TO_B1,
            stageStartedAt = sessionStartedAt,
            sessionStartedAt = sessionStartedAt,
            statusMessage = "Walking to B1"
        )
        appendEvent("TEST_START", "", "START", "Basement weak coverage test started")
        showOverlay()
        updateOverlay()

        sampleJob = scope.launch { samplingLoop() }
    }

    private suspend fun samplingLoop() {
        while (isActive && BasementTestStore.state.value.isRunning) {
            val started = System.currentTimeMillis()
            val sample = captureNetworkSample()
            if (sample != null) {
                detectEvents(lastSample, sample)
                samples += sample
                lastSample = sample

                if (startHadNr == null && sample.stage == BasementStage.START_TO_B1.name) {
                    startHadNr = sample.nrState == "CONNECTED" || sample.rat == "5G"
                    startHadLte = sample.lteRsrp != "--" || sample.rat == "4G" || sample.displayRat.contains("LTE", true)
                }

                val state = BasementTestStore.state.value
                BasementTestStore.state.value = state.copy(
                    currentRat = sample.rat,
                    currentRsrp = if (sample.rat == "5G" && sample.nrSsRsrp != "--") sample.nrSsRsrp else sample.lteRsrp,
                    operator = sample.operator
                )
                updateOverlay()
                updateNotification(state.stage.label)
            }
            val spent = System.currentTimeMillis() - started
            delay((1000L - spent).coerceAtLeast(100L))
        }
    }

    private suspend fun captureNetworkSample(): BasementNetworkSample? {
        val sims = runCatching { cellular.readAllSims() }.getOrDefault(emptyList())
        val defaultDataSub = defaultDataSubscriptionId()
        val sim = sims.firstOrNull { it.subscriptionId == config.selectedSubscriptionId }
            ?: sims.firstOrNull { it.subscriptionId == defaultDataSub }
            ?: sims.firstOrNull()
            ?: return null

        val serving = sim.servingCell
        val nr = when {
            serving.rat == "NR" -> serving
            else -> sim.neighbors.firstOrNull { it.rat == "NR" }
        }
        val lte = serving.takeIf { it.rat == "LTE" }
        val registered = serving.registered
        val rat = ratCategory(serving, registered)
        val nrConnected = registered && (
            serving.rat == "NR" ||
                serving.displayRat.contains("5G", true) ||
                serving.carrierAggregation.contains("EN-DC", true)
            )
        val nrState = when {
            nrConnected -> "CONNECTED"
            nr != null -> "VISIBLE"
            else -> "RELEASED"
        }

        val stage = BasementTestStore.state.value.stage
        return BasementNetworkSample(
            timestampMs = System.currentTimeMillis(),
            stage = stage.name,
            segment = segmentFor(stage),
            simSlot = sim.simSlotIndex,
            subscriptionId = sim.subscriptionId,
            operator = serving.operator.ifBlank { "--" },
            rat = rat,
            displayRat = serving.displayRat.ifBlank { serving.rat.ifBlank { "--" } },
            registered = registered,
            lteBand = lte?.band ?: "--",
            ltePci = lte?.pci ?: "--",
            lteArfcn = lte?.arfcn ?: "--",
            lteRsrp = lte?.rsrp ?: "--",
            lteRsrq = lte?.rsrq ?: "--",
            lteSinr = lte?.sinr ?: "--",
            lteCellId = lte?.cellId ?: "--",
            lteTac = lte?.tac ?: "--",
            nrState = nrState,
            nrBand = nr?.band ?: "--",
            nrPci = nr?.pci ?: "--",
            nrArfcn = nr?.arfcn ?: "--",
            nrSsRsrp = nr?.rsrp ?: "--",
            nrSsRsrq = nr?.rsrq ?: "--",
            nrSsSinr = nr?.sinr ?: "--",
            nrCellId = nr?.cellId ?: "--",
            nrTac = nr?.tac ?: "--",
            dataState = currentDataState()
        )
    }

    private fun ratCategory(cell: CellData, registered: Boolean): String {
        if (!registered || cell.rat == "--") return "NO_SERVICE"
        val d = cell.displayRat.uppercase(Locale.US)
        val r = cell.rat.uppercase(Locale.US)
        val data = cell.dataRat.uppercase(Locale.US)
        return when {
            d.contains("5G") || r == "NR" || data == "NR" -> "5G"
            r == "LTE" || data == "LTE" -> "4G"
            r.contains("WCDMA") || data.contains("UMTS") || data.contains("HSPA") -> "3G"
            r.contains("GSM") || data == "GSM" || data == "EDGE" || data == "GPRS" -> "2G"
            else -> r.ifBlank { "UNKNOWN" }
        }
    }

    private fun currentDataState(): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "NO_NETWORK"
        val network = cm.activeNetwork ?: return "NO_NETWORK"
        val caps = cm.getNetworkCapabilities(network) ?: return "NO_NETWORK"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "CELLULAR_VALIDATED"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR_NOT_VALIDATED"
            else -> "OTHER"
        }
    }

    private fun detectEvents(prev: BasementNetworkSample?, cur: BasementNetworkSample) {
        if (prev == null) return
        if (prev.rat != cur.rat) {
            appendEvent("RAT_CHANGE", prev.rat, cur.rat, "${prev.rat} → ${cur.rat}", cur.segment)
        }
        val prevCell = if (prev.rat == "5G" && prev.nrCellId != "--") prev.nrCellId else prev.lteCellId
        val curCell = if (cur.rat == "5G" && cur.nrCellId != "--") cur.nrCellId else cur.lteCellId
        if (prevCell != "--" && curCell != "--" && prevCell != curCell) {
            appendEvent("CELL_CHANGE", prevCell, curCell, "Serving cell changed", cur.segment)
        }
        val prevBand = if (prev.rat == "5G" && prev.nrBand != "--") prev.nrBand else prev.lteBand
        val curBand = if (cur.rat == "5G" && cur.nrBand != "--") cur.nrBand else cur.lteBand
        if (prevBand != "--" && curBand != "--" && prevBand != curBand) {
            appendEvent("BAND_CHANGE", prevBand, curBand, "Band changed", cur.segment)
        }
        if (prev.nrState != cur.nrState) {
            val type = if (cur.nrState == "CONNECTED") "NR_ADD" else if (prev.nrState == "CONNECTED") "NR_RELEASE" else "NR_STATE_CHANGE"
            appendEvent(type, prev.nrState, cur.nrState, "$type ${prev.nrState} → ${cur.nrState}", cur.segment)
        }
        if (prev.dataState != cur.dataState) {
            appendEvent("DATA_STATE_CHANGE", prev.dataState, cur.dataState, "Data state changed", cur.segment)
        }

        if (prev.rat != "NO_SERVICE" && cur.rat == "NO_SERVICE") {
            noServiceStartedAt = cur.timestampMs
            appendEvent("NO_SERVICE_START", prev.rat, "NO_SERVICE", "Service lost", cur.segment)
        } else if (prev.rat == "NO_SERVICE" && cur.rat != "NO_SERVICE") {
            val start = noServiceStartedAt
            val duration = start?.let { cur.timestampMs - it }
            events += BasementEvent(
                timestampMs = cur.timestampMs,
                segment = cur.segment,
                type = "NO_SERVICE_END",
                from = "NO_SERVICE",
                to = cur.rat,
                durationMs = duration,
                description = "Service restored"
            )
            noServiceStartedAt = null
        }
    }

    private fun handlePrimaryAction() {
        when (BasementTestStore.state.value.stage) {
            BasementStage.START_TO_B1 -> arriveB1First()
            BasementStage.B1_COMPLETE -> startRoute(BasementStage.B1_TO_B2, SEG_B1_B2, "B1", "B2")
            BasementStage.B1_TO_B2 -> arriveB2()
            BasementStage.B2_COMPLETE -> startRoute(BasementStage.B2_TO_B1, SEG_B2_B1, "B2", "B1 Return")
            BasementStage.B2_TO_B1 -> arriveB1Return()
            BasementStage.B1_RETURN_COMPLETE -> startRoute(BasementStage.B1_TO_START, SEG_B1_START, "B1 Return", "START")
            BasementStage.B1_TO_START -> arriveStart()
            BasementStage.RECOVERY_COMPLETE -> finishTest("COMPLETED")
            else -> Unit
        }
    }

    private fun arriveB1First() {
        val now = System.currentTimeMillis()
        segmentEnd[SEG_START_B1] = now
        appendEvent("ARRIVE_B1", "START", "B1", "Arrived B1", SEG_START_B1)
        runFixedPoint("B1 First", BasementStage.B1_STABILIZING, BasementStage.B1_PING, BasementStage.B1_COMPLETE)
    }

    private fun arriveB2() {
        val now = System.currentTimeMillis()
        segmentEnd[SEG_B1_B2] = now
        appendEvent("ARRIVE_B2", "B1", "B2", "Arrived B2", SEG_B1_B2)
        runFixedPoint("B2", BasementStage.B2_STABILIZING, BasementStage.B2_PING, BasementStage.B2_COMPLETE)
    }

    private fun arriveB1Return() {
        val now = System.currentTimeMillis()
        segmentEnd[SEG_B2_B1] = now
        appendEvent("ARRIVE_B1_RETURN", "B2", "B1 Return", "Arrived B1 return", SEG_B2_B1)
        runFixedPoint("B1 Return", BasementStage.B1_RETURN_STABILIZING, BasementStage.B1_RETURN_PING, BasementStage.B1_RETURN_COMPLETE)
    }

    private fun startRoute(stage: BasementStage, segment: String, from: String, to: String) {
        val now = System.currentTimeMillis()
        segmentStart[segment] = now
        updateStage(stage, "$from → $to")
        appendEvent("SEGMENT_START", from, to, "$from → $to started", segment)
    }

    private fun runFixedPoint(point: String, stabilize: BasementStage, pingStage: BasementStage, complete: BasementStage) {
        stageJob?.cancel()
        stageJob = scope.launch {
            updateStage(stabilize, "$point stabilizing")
            for (remain in config.stabilizeSeconds downTo 1) {
                updateCountdown(remain)
                delay(1000L)
            }
            updateCountdown(null)
            updateStage(pingStage, "$point ping")
            val result = runPointPing(point)
            pointResults[point] = result
            BasementTestStore.state.value = BasementTestStore.state.value.copy(lastPointResult = result)
            updateStage(complete, "$point completed")
            updateOverlay()
        }
    }

    private suspend fun runPointPing(point: String): BasementPointResult {
        val total = config.pingSeconds.coerceAtLeast(1)
        var received = 0
        val latencies = mutableListOf<Double>()
        for (seq in 1..total) {
            if (!BasementTestStore.state.value.isRunning) break
            val cycleStarted = System.currentTimeMillis()
            val (rtt, message) = runSinglePing(config.host, 2000L)
            val success = rtt != null
            if (success) {
                received++
                latencies += rtt!!
            }
            val snap = lastSample
            pingSamples += BasementPingSample(
                point = point,
                timestampMs = System.currentTimeMillis(),
                sequence = seq,
                success = success,
                rttMs = rtt,
                message = message,
                rat = snap?.rat ?: "--",
                rsrp = when {
                    snap == null -> "--"
                    snap.rat == "5G" && snap.nrSsRsrp != "--" -> snap.nrSsRsrp
                    else -> snap.lteRsrp
                }
            )
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                pingProgress = seq,
                pingTotal = total,
                statusMessage = "$point Ping $seq/$total"
            )
            updateOverlay()
            val spent = System.currentTimeMillis() - cycleStarted
            delay((1000L - spent).coerceAtLeast(0L))
        }
        val sent = pingSamples.count { it.point == point }
        val recv = pingSamples.count { it.point == point && it.success }
        val values = pingSamples.filter { it.point == point }.mapNotNull { it.rttMs }
        val loss = if (sent == 0) 100.0 else (sent - recv) * 100.0 / sent
        return BasementPointResult(
            point = point,
            sent = sent,
            received = recv,
            lossPct = loss,
            avgRttMs = values.takeIf { it.isNotEmpty() }?.average(),
            minRttMs = values.minOrNull(),
            maxRttMs = values.maxOrNull(),
            result = if (recv > 0) "PASS" else "FAIL"
        )
    }

    private fun arriveStart() {
        val now = System.currentTimeMillis()
        segmentEnd[SEG_B1_START] = now
        appendEvent("ARRIVE_START", "B1 Return", "START", "Returned to START", SEG_B1_START)
        updateStage(BasementStage.RECOVERY, "Checking LTE/Data/5G recovery")
        runRecovery(now)
    }

    private fun runRecovery(arrivalMs: Long) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            val requireNr = startHadNr == true
            val requireLte = startHadLte != false
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                lteRecoveryRequired = requireLte,
                nrRecoveryRequired = requireNr,
                lteRecoveryMs = if (requireLte) null else -2L,
                nrRecoveryMs = if (requireNr) null else -2L
            )

            var lteStreak = 0
            var nrStreak = 0
            var dataStreak = 0
            var lteRecovery: Long? = if (requireLte) null else -2L
            var nrRecovery: Long? = if (requireNr) null else -2L
            var dataRecovery: Long? = null

            val maxSeconds = config.recoverySeconds.coerceAtLeast(1)
            for (sec in 1..maxSeconds) {
                if (!BasementTestStore.state.value.isRunning) return@launch
                val cycleStarted = System.currentTimeMillis()
                val snap = lastSample
                val lteAvailable = snap != null && snap.registered && (
                    snap.lteRsrp != "--" || snap.rat == "4G" || snap.displayRat.contains("LTE", true)
                    )
                val nrAvailable = snap != null && (snap.nrState == "CONNECTED" || snap.rat == "5G")
                lteStreak = if (lteAvailable) lteStreak + 1 else 0
                nrStreak = if (nrAvailable) nrStreak + 1 else 0

                val (rtt, recoveryMessage) = runSinglePing(config.host, 850L)
                val recoverySnap = lastSample
                pingSamples += BasementPingSample(
                    point = "START Recovery",
                    timestampMs = System.currentTimeMillis(),
                    sequence = sec,
                    success = rtt != null,
                    rttMs = rtt,
                    message = recoveryMessage,
                    rat = recoverySnap?.rat ?: "--",
                    rsrp = when {
                        recoverySnap == null -> "--"
                        recoverySnap.rat == "5G" && recoverySnap.nrSsRsrp != "--" -> recoverySnap.nrSsRsrp
                        else -> recoverySnap.lteRsrp
                    }
                )
                dataStreak = if (rtt != null) dataStreak + 1 else 0
                val now = System.currentTimeMillis()
                if (requireLte && lteRecovery == null && lteStreak >= 2) {
                    lteRecovery = (now - arrivalMs).coerceAtLeast(0L)
                    appendEvent("LTE_RECOVERY", "", "${lteRecovery}ms", "LTE stable for 2 samples")
                }
                if (requireNr && nrRecovery == null && nrStreak >= 2) {
                    nrRecovery = (now - arrivalMs).coerceAtLeast(0L)
                    appendEvent("NR_RECOVERY", "", "${nrRecovery}ms", "NR stable for 2 samples")
                }
                if (dataRecovery == null && dataStreak >= 2) {
                    dataRecovery = (now - arrivalMs).coerceAtLeast(0L)
                    appendEvent("DATA_RECOVERY", "", "${dataRecovery}ms", "Ping succeeded twice")
                }

                val doneLte = !requireLte || lteRecovery != null
                val doneNr = !requireNr || nrRecovery != null
                val doneData = dataRecovery != null
                BasementTestStore.state.value = BasementTestStore.state.value.copy(
                    lteRecoveryMs = lteRecovery,
                    nrRecoveryMs = nrRecovery,
                    dataRecoveryMs = dataRecovery,
                    countdownSeconds = maxSeconds - sec,
                    statusMessage = "Recovery ${sec}s / ${maxSeconds}s"
                )
                updateOverlay()
                if (doneLte && doneNr && doneData) {
                    updateStage(BasementStage.RECOVERY_COMPLETE, "Recovery completed")
                    return@launch
                }
                val spent = System.currentTimeMillis() - cycleStarted
                delay((1000L - spent).coerceAtLeast(0L))
            }

            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                recoveryTimedOut = true,
                countdownSeconds = 0
            )
            appendEvent("RECOVERY_TIMEOUT", "", "", "Recovery exceeded ${config.recoverySeconds}s")
            updateStage(BasementStage.RECOVERY_COMPLETE, "Recovery timeout")
        }
    }

    private fun updateStage(stage: BasementStage, message: String) {
        BasementTestStore.state.value = BasementTestStore.state.value.copy(
            stage = stage,
            stageStartedAt = System.currentTimeMillis(),
            countdownSeconds = null,
            pingProgress = if (stage.name.contains("PING")) BasementTestStore.state.value.pingProgress else 0,
            pingTotal = if (stage.name.contains("PING")) BasementTestStore.state.value.pingTotal else 0,
            statusMessage = message
        )
        updateOverlay()
        updateNotification(stage.label)
    }

    private fun updateCountdown(value: Int?) {
        BasementTestStore.state.value = BasementTestStore.state.value.copy(countdownSeconds = value)
        updateOverlay()
    }

    private fun segmentFor(stage: BasementStage): String = when (stage) {
        BasementStage.START_TO_B1 -> SEG_START_B1
        BasementStage.B1_TO_B2 -> SEG_B1_B2
        BasementStage.B2_TO_B1 -> SEG_B2_B1
        BasementStage.B1_TO_START -> SEG_B1_START
        BasementStage.B1_STABILIZING, BasementStage.B1_PING, BasementStage.B1_COMPLETE -> "B1 First"
        BasementStage.B2_STABILIZING, BasementStage.B2_PING, BasementStage.B2_COMPLETE -> "B2"
        BasementStage.B1_RETURN_STABILIZING, BasementStage.B1_RETURN_PING, BasementStage.B1_RETURN_COMPLETE -> "B1 Return"
        BasementStage.RECOVERY, BasementStage.RECOVERY_COMPLETE -> "START Recovery"
        else -> ""
    }

    private fun appendEvent(type: String, from: String, to: String, description: String, segment: String = segmentFor(BasementTestStore.state.value.stage)) {
        events += BasementEvent(System.currentTimeMillis(), segment, type, from, to, null, description)
    }

    private suspend fun runSinglePing(host: String, timeoutMs: Long): Pair<Double?, String> = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            val timeoutSeconds = ceil(timeoutMs / 1000.0).toInt().coerceAtLeast(1)
            process = ProcessBuilder("/system/bin/ping", "-c", "1", "-W", timeoutSeconds.toString(), host)
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(timeoutMs + 300L, TimeUnit.MILLISECONDS)
            if (!completed) process.destroyForcibly()
            val output = runCatching { process.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
            val latency = Regex("time[=<]([0-9.]+)\\s*ms", RegexOption.IGNORE_CASE)
                .find(output)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            val message = output.lineSequence().firstOrNull {
                it.contains("bytes from", true) || it.contains("timeout", true) || it.contains("unreachable", true)
            }.orEmpty().ifBlank { if (completed) "No reply" else "Timeout" }
            latency to message
        } catch (e: Exception) {
            null to (e.message ?: "Ping failed")
        } finally {
            runCatching { process?.destroy() }
        }
    }

    private fun finishTest(status: String) {
        if (!BasementTestStore.state.value.isRunning) return
        sampleJob?.cancel(); sampleJob = null
        stageJob?.cancel(); stageJob = null
        recoveryJob?.cancel(); recoveryJob = null
        if (noServiceStartedAt != null) {
            val now = System.currentTimeMillis()
            events += BasementEvent(now, segmentFor(BasementTestStore.state.value.stage), "NO_SERVICE_END", "NO_SERVICE", "TEST_END", now - noServiceStartedAt!!, "Test ended while out of service")
            noServiceStartedAt = null
        }
        appendEvent("TEST_END", BasementTestStore.state.value.stage.name, status, "Basement test finished")
        val endedAt = System.currentTimeMillis()

        scope.launch {
            val reportPath = exportReports(endedAt, status)
            val finalStage = if (status == "COMPLETED") BasementStage.FINISHED else BasementStage.ABORTED
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                isRunning = false,
                stage = finalStage,
                countdownSeconds = null,
                reportPath = reportPath,
                statusMessage = if (reportPath.isNotBlank()) "$status · report saved" else "$status · export failed"
            )
            removeOverlay()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun exportReports(endedAt: Long, status: String): String {
        val operator = samples.firstOrNull()?.operator?.takeIf { it != "--" } ?: "Unknown"
        val safeOperator = sanitize(operator)
        val safeDevice = sanitize(config.deviceLabel.ifBlank { "DUT" })
        val folder = "${safeOperator}_${safeDevice}_${sessionDirName}"
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sessionStartedAt))
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Reports/$date/Basement/$folder"

        val raw = buildNetworkCsv()
        val evt = buildEventsCsv()
        val ping = buildPingCsv()
        val summaryCsv = buildSummaryCsv(endedAt, status)
        val html = buildSummaryHtml(endedAt, status)

        val ok = listOf(
            writePublicText(relative, "network_raw.csv", "text/csv", raw),
            writePublicText(relative, "events.csv", "text/csv", evt),
            writePublicText(relative, "ping.csv", "text/csv", ping),
            writePublicText(relative, "summary.csv", "text/csv", summaryCsv),
            writePublicText(relative, "summary.html", "text/html", html)
        ).all { it }
        return if (ok) "$relative/summary.html" else ""
    }

    private fun writePublicText(relative: String, name: String, mime: String, text: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relative)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            var success = false
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                success = true
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } catch (_: Throwable) {
                contentResolver.delete(uri, null, null)
            }
            success
        }.getOrDefault(false)
    }

    private fun buildNetworkCsv(): String = buildString {
        appendLine("timestamp_ms,timestamp,stage,segment,sim,subscription_id,operator,rat,display_rat,registered,lte_band,lte_pci,lte_earfcn,lte_rsrp,lte_rsrq,lte_sinr,lte_cell_id,lte_tac,nr_state,nr_band,nr_pci,nr_arfcn,nr_ss_rsrp,nr_ss_rsrq,nr_ss_sinr,nr_cell_id,nr_tac,data_state")
        samples.forEach { s ->
            appendLine(listOf(
                s.timestampMs, timeText(s.timestampMs), s.stage, s.segment, s.simSlot + 1, s.subscriptionId, s.operator,
                s.rat, s.displayRat, s.registered, s.lteBand, s.ltePci, s.lteArfcn, s.lteRsrp, s.lteRsrq, s.lteSinr,
                s.lteCellId, s.lteTac, s.nrState, s.nrBand, s.nrPci, s.nrArfcn, s.nrSsRsrp, s.nrSsRsrq, s.nrSsSinr,
                s.nrCellId, s.nrTac, s.dataState
            ).joinToString(",") { csv(it.toString()) })
        }
    }

    private fun buildEventsCsv(): String = buildString {
        appendLine("timestamp_ms,timestamp,segment,segment_elapsed_ms,event_type,from,to,duration_ms,description")
        events.sortedBy { it.timestampMs }.forEach { e ->
            val elapsed = segmentStart[e.segment]?.let { (e.timestampMs - it).coerceAtLeast(0L) } ?: ""
            appendLine(listOf(e.timestampMs, timeText(e.timestampMs), e.segment, elapsed, e.type, e.from, e.to, e.durationMs ?: "", e.description)
                .joinToString(",") { csv(it.toString()) })
        }
    }

    private fun buildPingCsv(): String = buildString {
        appendLine("point,timestamp_ms,timestamp,sequence,result,rtt_ms,rat,rsrp,message")
        pingSamples.forEach { p ->
            appendLine(listOf(p.point, p.timestampMs, timeText(p.timestampMs), p.sequence, if (p.success) "PASS" else "FAIL",
                p.rttMs?.let { String.format(Locale.US, "%.2f", it) } ?: "", p.rat, p.rsrp, p.message)
                .joinToString(",") { csv(it.toString()) })
        }
    }

    private fun buildSummaryCsv(endedAt: Long, status: String): String = buildString {
        appendLine("section,item,value")
        fun row(section: String, item: String, value: String) = appendLine("${csv(section)},${csv(item)},${csv(value)}")
        row("Session", "Status", status)
        row("Session", "Started", timeText(sessionStartedAt))
        row("Session", "Ended", timeText(endedAt))
        row("Session", "Duration", formatDuration(endedAt - sessionStartedAt))
        row("Weak Coverage", "5G Retention", formatDuration(initialRetentionMs("5G")))
        row("Weak Coverage", "LTE Retention", formatDuration(firstContiguousRatMs("4G")))
        val routeSamples = samples.filter { it.segment in setOf(SEG_START_B1, SEG_B1_B2, SEG_B2_B1, SEG_B1_START) }
        val minLteRsrp = routeSamples.mapNotNull { it.lteRsrp.toDoubleOrNull() }.minOrNull()
        val minNrRsrp = routeSamples.mapNotNull { it.nrSsRsrp.toDoubleOrNull() }.minOrNull()
        row("Weak Coverage", "Minimum LTE RSRP", minLteRsrp?.let { "$it dBm" } ?: "--")
        row("Weak Coverage", "Minimum NR SS-RSRP", minNrRsrp?.let { "$it dBm" } ?: "--")
        val pingFailPoints = pointResults.values.count { it.result == "FAIL" }
        row("Data Availability", "Ping Fail", "$pingFailPoints / ${pointResults.size}")
        val ns = noServiceStats(endedAt)
        row("Extreme Weak Coverage", "No Service", if (ns.first > 0) "YES" else "NO")
        row("Extreme Weak Coverage", "No Service Count", ns.first.toString())
        row("Extreme Weak Coverage", "Total No Service", formatDuration(ns.second))
        row("Extreme Weak Coverage", "Longest No Service", formatDuration(ns.third))
        pointResults.values.forEach { r ->
            val snap = samples.firstOrNull { it.segment == r.point }
            if (snap != null) {
                row(r.point, "Network", "${snap.rat} ${if (snap.rat == "5G") snap.nrBand else snap.lteBand}".trim())
                row(r.point, "LTE RSRP", snap.lteRsrp)
                row(r.point, "LTE RSRQ", snap.lteRsrq)
                row(r.point, "LTE SINR", snap.lteSinr)
                row(r.point, "NR SS-RSRP", snap.nrSsRsrp)
                row(r.point, "NR SS-RSRQ", snap.nrSsRsrq)
                row(r.point, "NR SS-SINR", snap.nrSsSinr)
            }
            row(r.point, "Ping Result", r.result)
            row(r.point, "Sent", r.sent.toString())
            row(r.point, "Received", r.received.toString())
            row(r.point, "Loss", String.format(Locale.US, "%.1f%%", r.lossPct))
            row(r.point, "Avg RTT", r.avgRttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--")
            row(r.point, "Min RTT", r.minRttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--")
            row(r.point, "Max RTT", r.maxRttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--")
        }
        val st = BasementTestStore.state.value
        row("Recovery", "LTE", recoveryText(st.lteRecoveryMs, st.lteRecoveryRequired))
        row("Recovery", "Data", recoveryText(st.dataRecoveryMs, true))
        row("Recovery", "5G", recoveryText(st.nrRecoveryMs, st.nrRecoveryRequired))
        listOf(SEG_START_B1, SEG_B1_B2, SEG_B2_B1, SEG_B1_START).forEach { seg ->
            val start = segmentStart[seg] ?: return@forEach
            val end = segmentEnd[seg] ?: endedAt
            row("Segment $seg", "Duration", formatDuration(end - start))
            val dist = ratDistribution(seg, end)
            listOf("5G", "4G", "3G", "2G", "NO_SERVICE").forEach { rat ->
                val ms = dist[rat] ?: 0L
                val pct = if (end > start) ms * 100.0 / (end - start) else 0.0
                row("Segment $seg", "$rat Distribution", "${formatDuration(ms)} / ${String.format(Locale.US, "%.1f%%", pct)}")
            }
            val lteRsrp = samples.filter { it.segment == seg }.mapNotNull { it.lteRsrp.toDoubleOrNull() }
            if (lteRsrp.isNotEmpty()) {
                row("Segment $seg", "LTE Avg RSRP", String.format(Locale.US, "%.1f dBm", lteRsrp.average()))
                row("Segment $seg", "LTE Min RSRP", "${lteRsrp.minOrNull()} dBm")
                row("Segment $seg", "LTE Max RSRP", "${lteRsrp.maxOrNull()} dBm")
            }
            val nrRsrp = samples.filter { it.segment == seg }.mapNotNull { it.nrSsRsrp.toDoubleOrNull() }
            if (nrRsrp.isNotEmpty()) {
                row("Segment $seg", "NR Avg SS-RSRP", String.format(Locale.US, "%.1f dBm", nrRsrp.average()))
                row("Segment $seg", "NR Min SS-RSRP", "${nrRsrp.minOrNull()} dBm")
            }
        }
    }

    private fun buildSummaryHtml(endedAt: Long, status: String): String {
        val st = BasementTestStore.state.value
        val ns = noServiceStats(endedAt)
        val points = pointResults.values.joinToString("") { r ->
            val snap = samples.firstOrNull { it.segment == r.point }
            val network = snap?.let { "${it.rat} ${if (it.rat == "5G") it.nrBand else it.lteBand}" } ?: "--"
            val rsrp = snap?.let { if (it.rat == "5G" && it.nrSsRsrp != "--") it.nrSsRsrp else it.lteRsrp } ?: "--"
            val sinr = snap?.let { if (it.rat == "5G" && it.nrSsSinr != "--") it.nrSsSinr else it.lteSinr } ?: "--"
            "<tr><td>${html(r.point)}</td><td>${html(network)}</td><td>${html(rsrp)}</td><td>${html(sinr)}</td><td>${html(r.result)}</td><td>${String.format(Locale.US, "%.1f%%", r.lossPct)}</td><td>${r.avgRttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--"}</td></tr>"
        }
        val transitions = events.filter { it.type in setOf("RAT_CHANGE", "CELL_CHANGE", "BAND_CHANGE", "NR_ADD", "NR_RELEASE", "NO_SERVICE_START", "NO_SERVICE_END") }
            .joinToString("") { e ->
                "<tr><td>${timeText(e.timestampMs)}</td><td>${html(e.segment)}</td><td>${html(e.type)}</td><td>${html(e.from)}</td><td>${html(e.to)}</td><td>${html(e.description)}</td></tr>"
            }
        val segmentsHtml = listOf(SEG_START_B1, SEG_B1_B2, SEG_B2_B1, SEG_B1_START).joinToString("") { seg ->
            val start = segmentStart[seg] ?: sessionStartedAt
            val end = segmentEnd[seg] ?: endedAt
            val dist = ratDistribution(seg, end)
            val total = (end - start).coerceAtLeast(1L)
            fun d(r: String): String {
                val ms = dist[r] ?: 0L
                return "${formatDuration(ms)} / ${String.format(Locale.US, "%.1f%%", ms * 100.0 / total)}"
            }
            val lte = samples.filter { it.segment == seg }.mapNotNull { it.lteRsrp.toDoubleOrNull() }
            val nr = samples.filter { it.segment == seg }.mapNotNull { it.nrSsRsrp.toDoubleOrNull() }
            "<tr><td>${html(seg)}</td><td>${formatDuration(end-start)}</td><td>${d("5G")}</td><td>${d("4G")}</td><td>${d("NO_SERVICE")}</td><td>${lte.takeIf { it.isNotEmpty() }?.average()?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}</td><td>${nr.takeIf { it.isNotEmpty() }?.average()?.let { String.format(Locale.US, "%.1f", it) } ?: "--"}</td></tr>"
        }
        return """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Basement Weak Coverage Summary</title>
<style>body{font-family:sans-serif;margin:18px;color:#1f2937}h1{font-size:24px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:10px}.card{border:1px solid #ddd;border-radius:12px;padding:12px}.k{font-size:12px;color:#666}.v{font-size:18px;font-weight:700;margin-top:4px}table{border-collapse:collapse;width:100%;margin-top:8px}th,td{border-bottom:1px solid #ddd;padding:8px;text-align:left;font-size:13px}th{background:#f6f6f6}</style></head><body>
<h1>Weak Coverage Summary</h1>
<div class="grid">
<div class="card"><div class="k">Status</div><div class="v">${html(status)}</div></div>
<div class="card"><div class="k">5G Retention</div><div class="v">${formatDuration(initialRetentionMs("5G"))}</div></div>
<div class="card"><div class="k">LTE Retention</div><div class="v">${formatDuration(firstContiguousRatMs("4G"))}</div></div>
<div class="card"><div class="k">No Service</div><div class="v">${if (ns.first > 0) "YES · ${formatDuration(ns.second)}" else "NO"}</div></div>
<div class="card"><div class="k">LTE Recovery</div><div class="v">${recoveryText(st.lteRecoveryMs, st.lteRecoveryRequired)}</div></div>
<div class="card"><div class="k">Data Recovery</div><div class="v">${recoveryText(st.dataRecoveryMs, true)}</div></div>
<div class="card"><div class="k">5G Recovery</div><div class="v">${recoveryText(st.nrRecoveryMs, st.nrRecoveryRequired)}</div></div>
</div>
<h2>Fixed Points</h2>
<table><tr><th>Point</th><th>Network</th><th>RSRP</th><th>SINR</th><th>Ping</th><th>Loss</th><th>Avg RTT</th></tr>$points</table>
<h2>Route Segments</h2>
<table><tr><th>Segment</th><th>Duration</th><th>5G</th><th>4G</th><th>No Service</th><th>LTE Avg RSRP</th><th>NR Avg SS-RSRP</th></tr>$segmentsHtml</table>
<h2>Network Events</h2>
<table><tr><th>Time</th><th>Segment</th><th>Event</th><th>From</th><th>To</th><th>Description</th></tr>$transitions</table>
<h2>Session</h2><p>${timeText(sessionStartedAt)} → ${timeText(endedAt)} · ${formatDuration(endedAt-sessionStartedAt)}</p>
<p>Raw files: network_raw.csv · events.csv · ping.csv · summary.csv</p>
</body></html>
""".trimIndent()
    }

    private fun ratDistribution(segment: String, fallbackEnd: Long): Map<String, Long> {
        val rows = samples.filter { it.segment == segment }.sortedBy { it.timestampMs }
        if (rows.isEmpty()) return emptyMap()
        val end = segmentEnd[segment] ?: fallbackEnd
        val out = linkedMapOf<String, Long>()
        rows.forEachIndexed { index, row ->
            val next = rows.getOrNull(index + 1)?.timestampMs ?: end
            val dur = (next - row.timestampMs).coerceIn(0L, 2000L)
            out[row.rat] = (out[row.rat] ?: 0L) + dur
        }
        return out
    }

    private fun initialRetentionMs(rat: String): Long {
        val routeRows = samples.filter { it.segment == SEG_START_B1 }.sortedBy { it.timestampMs }
        if (routeRows.isEmpty() || routeRows.first().rat != rat) return 0L
        val firstOther = routeRows.firstOrNull { it.rat != rat }?.timestampMs
            ?: segmentEnd[SEG_START_B1]
            ?: routeRows.last().timestampMs
        return (firstOther - routeRows.first().timestampMs).coerceAtLeast(0L)
    }

    private fun firstContiguousRatMs(rat: String): Long {
        val routeRows = samples.filter { it.segment in setOf(SEG_START_B1, SEG_B1_B2, SEG_B2_B1, SEG_B1_START) }.sortedBy { it.timestampMs }
        val first = routeRows.indexOfFirst { it.rat == rat }
        if (first < 0) return 0L
        var end = routeRows[first].timestampMs
        for (i in first + 1 until routeRows.size) {
            if (routeRows[i].rat != rat) break
            end = routeRows[i].timestampMs
        }
        return (end - routeRows[first].timestampMs + 1000L).coerceAtLeast(0L)
    }

    private fun noServiceStats(endedAt: Long): Triple<Int, Long, Long> {
        val intervals = mutableListOf<Long>()
        var start: Long? = null
        samples.sortedBy { it.timestampMs }.forEach { s ->
            if (s.rat == "NO_SERVICE" && start == null) start = s.timestampMs
            if (s.rat != "NO_SERVICE" && start != null) {
                intervals += (s.timestampMs - start!!).coerceAtLeast(0L)
                start = null
            }
        }
        if (start != null) intervals += (endedAt - start!!).coerceAtLeast(0L)
        return Triple(intervals.size, intervals.sum(), intervals.maxOrNull() ?: 0L)
    }

    private fun recoveryText(value: Long?, required: Boolean): String {
        if (!required || value == -2L) return "N/A"
        return value?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "FAIL >${config.recoverySeconds}s"
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this) || overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xE6202124.toInt())
        }
        overlayTitle = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
        }
        overlayNetwork = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 12f
        }
        overlaySub = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 11f
        }
        overlayButton = Button(this).apply {
            textSize = 11f
            setOnClickListener { handlePrimaryAction() }
        }
        root.addView(overlayTitle)
        root.addView(overlayNetwork)
        root.addView(overlaySub)
        root.addView(overlayButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        val lp = WindowManager.LayoutParams(
            dp(230), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8); y = dp(80)
        }
        runCatching { wm.addView(root, lp); overlay = root }
    }

    private fun updateOverlay() {
        val state = BasementTestStore.state.value
        android.os.Handler(mainLooper).post {
            overlayTitle?.text = "Basement Test · ${state.stage.label}"
            overlayNetwork?.text = "${state.currentRat} · RSRP ${state.currentRsrp} dBm"
            overlaySub?.text = overlaySubText(state)
            val action = primaryActionLabel(state.stage)
            overlayButton?.text = action.first
            overlayButton?.isEnabled = action.second
        }
    }

    private fun overlaySubText(state: BasementLiveState): String = when (state.stage) {
        BasementStage.B1_STABILIZING, BasementStage.B2_STABILIZING, BasementStage.B1_RETURN_STABILIZING ->
            "Stabilizing: ${state.countdownSeconds ?: 0}s"
        BasementStage.B1_PING, BasementStage.B2_PING, BasementStage.B1_RETURN_PING -> {
            val r = state.lastPointResult
            if (state.pingProgress < state.pingTotal) "Ping: ${state.pingProgress}/${state.pingTotal}s"
            else r?.let { "Loss ${String.format(Locale.US, "%.1f%%", it.lossPct)} · Avg ${it.avgRttMs?.let { v -> String.format(Locale.US, "%.0fms", v) } ?: "--"}" } ?: "Ping testing"
        }
        BasementStage.B1_COMPLETE, BasementStage.B2_COMPLETE, BasementStage.B1_RETURN_COMPLETE -> {
            state.lastPointResult?.let { "${it.result} · Loss ${String.format(Locale.US, "%.1f%%", it.lossPct)} · Avg ${it.avgRttMs?.let { v -> String.format(Locale.US, "%.0fms", v) } ?: "--"}" } ?: "Completed"
        }
        BasementStage.RECOVERY, BasementStage.RECOVERY_COMPLETE ->
            "LTE ${shortRecovery(state.lteRecoveryMs, state.lteRecoveryRequired)} · Data ${shortRecovery(state.dataRecoveryMs, true)} · 5G ${shortRecovery(state.nrRecoveryMs, state.nrRecoveryRequired)}"
        else -> state.operator
    }

    private fun shortRecovery(v: Long?, required: Boolean): String {
        if (!required || v == -2L) return "N/A"
        return v?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "…"
    }

    private fun primaryActionLabel(stage: BasementStage): Pair<String, Boolean> = when (stage) {
        BasementStage.START_TO_B1 -> "ARRIVE B1" to true
        BasementStage.B1_COMPLETE -> "START B1 → B2" to true
        BasementStage.B1_TO_B2 -> "ARRIVE B2" to true
        BasementStage.B2_COMPLETE -> "START B2 → B1" to true
        BasementStage.B2_TO_B1 -> "ARRIVE B1" to true
        BasementStage.B1_RETURN_COMPLETE -> "START B1 → START" to true
        BasementStage.B1_TO_START -> "ARRIVE START" to true
        BasementStage.RECOVERY_COMPLETE -> "FINISH TEST" to true
        BasementStage.B1_STABILIZING, BasementStage.B2_STABILIZING, BasementStage.B1_RETURN_STABILIZING -> "AUTO WAIT" to false
        BasementStage.B1_PING, BasementStage.B2_PING, BasementStage.B1_RETURN_PING -> "PING RUNNING" to false
        BasementStage.RECOVERY -> "RECOVERING" to false
        else -> "WAIT" to false
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        overlay = null
        overlayTitle = null
        overlayNetwork = null
        overlaySub = null
        overlayButton = null
    }

    private fun startForegroundServiceNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CellTracker Basement Test")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, n)
    }

    private fun updateNotification(text: String) {
        val state = BasementTestStore.state.value
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CellTracker Basement Test")
            .setContentText("$text · ${state.currentRat} ${state.currentRsrp} dBm")
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Basement Weak Coverage Test", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:BasementTest")
            .apply { acquire(45 * 60_000L) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun readConfig(intent: Intent): BasementTestConfig = BasementTestConfig(
        deviceLabel = intent.getStringExtra(EXTRA_DEVICE_LABEL).orEmpty().ifBlank { "DUT" },
        host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "8.8.8.8" },
        stabilizeSeconds = intent.getIntExtra(EXTRA_STABILIZE_SECONDS, 30).coerceIn(5, 120),
        pingSeconds = intent.getIntExtra(EXTRA_PING_SECONDS, 60).coerceIn(5, 180),
        recoverySeconds = intent.getIntExtra(EXTRA_RECOVERY_SECONDS, 60).coerceIn(10, 180),
        selectedSubscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1)
    )

    private fun defaultDataSubscriptionId(): Int? = runCatching {
        SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }.getOrNull()

    private fun timeText(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ms))
    private fun formatDuration(ms: Long): String {
        val totalMs = ms.coerceAtLeast(0L)
        val sec = totalMs / 1000
        val min = sec / 60
        val rem = sec % 60
        return if (min > 0) String.format(Locale.US, "%02d:%02d", min, rem) else String.format(Locale.US, "%.1fs", totalMs / 1000.0)
    }
    private fun sanitize(v: String): String = v.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(40).ifBlank { "Unknown" }
    private fun csv(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
    private fun html(v: String): String = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        sampleJob?.cancel(); stageJob?.cancel(); recoveryJob?.cancel()
        removeOverlay()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.example.celltracker.BASEMENT_START"
        const val ACTION_PRIMARY = "com.example.celltracker.BASEMENT_PRIMARY"
        const val ACTION_ABORT = "com.example.celltracker.BASEMENT_ABORT"
        const val ACTION_FINISH = "com.example.celltracker.BASEMENT_FINISH"

        const val EXTRA_DEVICE_LABEL = "device_label"
        const val EXTRA_HOST = "host"
        const val EXTRA_STABILIZE_SECONDS = "stabilize_seconds"
        const val EXTRA_PING_SECONDS = "ping_seconds"
        const val EXTRA_RECOVERY_SECONDS = "recovery_seconds"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"

        const val CHANNEL_ID = "celltracker_basement_test"
        const val NOTIFICATION_ID = 1011

        const val SEG_START_B1 = "START → B1"
        const val SEG_B1_B2 = "B1 → B2"
        const val SEG_B2_B1 = "B2 → B1 Return"
        const val SEG_B1_START = "B1 → START Return"
    }
}
