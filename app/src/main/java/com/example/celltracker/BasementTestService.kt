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
import android.view.MotionEvent
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
    private data class SegmentRoundMetric(
        val segment: String,
        val durationMs: Long,
        val ratShare: Map<String, Double>,
        val bandShare: Map<String, Double>,
        val lteAvgRsrp: Double?,
        val nrAvgRsrp: Double?
    )

    private data class PointRoundMetric(
        val point: String,
        val successPct: Double,
        val avgRttMs: Double?,
        val result: String
    )

    private data class RoundSummary(
        val round: Int,
        val segments: List<SegmentRoundMetric>,
        val points: List<PointRoundMetric>,
        val lteRecoveryMs: Long?,
        val dataRecoveryMs: Long?,
        val nrRecoveryMs: Long?,
        val lteRecoveryRequired: Boolean,
        val nrRecoveryRequired: Boolean,
        val recoveryTimedOut: Boolean,
        val noServiceCount: Int,
        val noServiceTotalMs: Long,
        val noServiceLongestMs: Long
    )

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
    private var activeSegmentIndex = 0
    private var currentRound = 1
    private val roundSummaries = mutableListOf<RoundSummary>()

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
    private var overlayLp: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> prepareTest(readConfig(intent))
            ACTION_START -> startTest(readConfig(intent))
            ACTION_PRIMARY -> handlePrimaryAction()
            ACTION_ABORT -> finishTest("ABORTED")
            ACTION_FINISH -> finishTest("COMPLETED")
        }
        return START_NOT_STICKY
    }

    private fun prepareTest(newConfig: BasementTestConfig) {
        if (BasementTestStore.state.value.isRunning) return
        config = newConfig
        startForegroundServiceNotification("Ready to Start")
        BasementTestStore.state.value = BasementLiveState(
            isRunning = true,
            stage = BasementStage.PREPARED,
            stageStartedAt = System.currentTimeMillis(),
            routeName = config.routeName,
            currentPointName = config.routePoints.firstOrNull()?.name.orEmpty(),
            nextPointName = config.routePoints.getOrNull(1)?.name.orEmpty(),
            routeSegmentCount = (config.routePoints.size - 1).coerceAtLeast(0),
            currentRound = 1,
            totalRounds = config.rounds,
            statusMessage = "Floating controller ready · press START TEST"
        )
        showOverlay()
        updateOverlay()
    }

    // Kept for compatibility with callers that still send ACTION_START directly.
    private fun startTest(newConfig: BasementTestConfig) {
        if (BasementTestStore.state.value.stage != BasementStage.PREPARED) {
            config = newConfig
        }
        beginTest()
    }

    private fun routeSegmentName(index: Int): String {
        val from = config.routePoints.getOrNull(index)?.name ?: return ""
        val to = config.routePoints.getOrNull(index + 1)?.name ?: return ""
        return "$from → $to"
    }

    private fun routeSegments(): List<String> =
        (0 until (config.routePoints.size - 1).coerceAtLeast(0)).map { routeSegmentName(it) }

    private fun segmentKey(round: Int, segment: String): String = "R$round|$segment"

    private fun beginTest() {
        if (BasementTestStore.state.value.stage != BasementStage.PREPARED &&
            BasementTestStore.state.value.stage != BasementStage.IDLE) return
        if (config.routePoints.size < 2) return

        sessionStartedAt = System.currentTimeMillis()
        sessionDirName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(sessionStartedAt))
        lastSample = null
        noServiceStartedAt = null
        startHadNr = null
        startHadLte = null
        activeSegmentIndex = 0
        currentRound = 1
        samples.clear(); events.clear(); pingSamples.clear(); pointResults.clear(); roundSummaries.clear()
        segmentStart.clear(); segmentEnd.clear()

        val firstSegment = routeSegmentName(0)
        segmentStart[segmentKey(currentRound, firstSegment)] = sessionStartedAt
        val from = config.routePoints[0].name
        val to = config.routePoints[1].name
        acquireWakeLock()
        BasementTestStore.state.value = BasementLiveState(
            isRunning = true,
            stage = BasementStage.ROUTE_TRAVEL,
            stageStartedAt = sessionStartedAt,
            sessionStartedAt = sessionStartedAt,
            routeName = config.routeName,
            currentSegment = firstSegment,
            currentPointName = from,
            nextPointName = to,
            routeSegmentIndex = 1,
            routeSegmentCount = config.routePoints.size - 1,
            currentRound = currentRound,
            totalRounds = config.rounds,
            statusMessage = "Round $currentRound/${config.rounds} · Walking: $from → $to"
        )
        appendEvent("TEST_START", "", from, "${config.routeName} weak coverage route started", firstSegment)
        updateOverlay()
        updateNotification(firstSegment)
        sampleJob = scope.launch { samplingLoop() }
    }

    private suspend fun samplingLoop() {
        while (currentCoroutineContext().isActive && BasementTestStore.state.value.isRunning) {
            val started = System.currentTimeMillis()
            val sample = captureNetworkSample()
            if (sample != null) {
                detectEvents(lastSample, sample)
                samples += sample
                lastSample = sample

                if (startHadNr == null && BasementTestStore.state.value.routeSegmentIndex == 1) {
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
            round = currentRound,
            timestampMs = System.currentTimeMillis(),
            stage = stage.name,
            segment = BasementTestStore.state.value.currentSegment.ifBlank { segmentFor(stage) },
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
                round = currentRound,
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
            BasementStage.PREPARED -> beginTest()
            BasementStage.ROUTE_TRAVEL -> arriveNextPoint()
            BasementStage.POINT_COMPLETE -> startNextSegment()
            BasementStage.ROUND_COMPLETE -> startNextRound()
            BasementStage.RECOVERY_COMPLETE -> finishTest("COMPLETED")
            else -> Unit
        }
    }

    private fun arriveNextPoint() {
        val now = System.currentTimeMillis()
        val segment = routeSegmentName(activeSegmentIndex)
        segmentEnd[segmentKey(currentRound, segment)] = now
        val from = config.routePoints.getOrNull(activeSegmentIndex)?.name ?: ""
        val point = config.routePoints.getOrNull(activeSegmentIndex + 1) ?: return
        appendEvent("ARRIVE_POINT", from, point.name, "Arrived ${point.name}", segment)
        if (activeSegmentIndex + 1 == config.routePoints.lastIndex) {
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                currentPointName = point.name,
                nextPointName = "",
                currentSegment = "${point.name} Recovery"
            )
            updateStage(BasementStage.RECOVERY, "${point.name} recovery")
            runRecovery(now)
        } else {
            runConfiguredPoint(point)
        }
    }

    private fun runConfiguredPoint(point: WeakCoveragePoint) {
        stageJob?.cancel()
        stageJob = scope.launch {
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                stage = BasementStage.POINT_STABILIZING,
                stageStartedAt = System.currentTimeMillis(),
                currentPointName = point.name,
                nextPointName = config.routePoints.getOrNull(activeSegmentIndex + 2)?.name.orEmpty(),
                currentSegment = point.name,
                statusMessage = "${point.name} stabilizing"
            )
            updateOverlay()
            for (remain in config.stabilizeSeconds downTo 1) {
                updateCountdown(remain)
                delay(1000L)
            }
            updateCountdown(null)
            if (point.pingEnabled) {
                updateStage(BasementStage.POINT_PING, "${point.name} ping")
                BasementTestStore.state.value = BasementTestStore.state.value.copy(
                    currentPointName = point.name,
                    currentSegment = point.name
                )
                val result = runPointPing(point.name)
                pointResults["$currentRound|${point.name}"] = result
                BasementTestStore.state.value = BasementTestStore.state.value.copy(lastPointResult = result)
            }
            updateStage(BasementStage.POINT_COMPLETE, if (point.pingEnabled) "${point.name} completed" else "${point.name} ready")
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                currentPointName = point.name,
                nextPointName = config.routePoints.getOrNull(activeSegmentIndex + 2)?.name.orEmpty(),
                currentSegment = point.name
            )
            updateOverlay()
        }
    }

    private fun startNextSegment() {
        if (activeSegmentIndex >= config.routePoints.lastIndex - 1) return
        activeSegmentIndex++
        val segment = routeSegmentName(activeSegmentIndex)
        val from = config.routePoints[activeSegmentIndex].name
        val to = config.routePoints[activeSegmentIndex + 1].name
        val now = System.currentTimeMillis()
        segmentStart[segmentKey(currentRound, segment)] = now
        BasementTestStore.state.value = BasementTestStore.state.value.copy(
            stage = BasementStage.ROUTE_TRAVEL,
            stageStartedAt = now,
            currentSegment = segment,
            currentPointName = from,
            nextPointName = to,
            routeSegmentIndex = activeSegmentIndex + 1,
            routeSegmentCount = config.routePoints.size - 1,
            currentRound = currentRound,
            totalRounds = config.rounds,
            countdownSeconds = null,
            pingProgress = 0,
            pingTotal = 0,
            statusMessage = "Round $currentRound/${config.rounds} · $from → $to"
        )
        appendEvent("SEGMENT_START", from, to, "$from → $to started", segment)
        updateOverlay()
        updateNotification(segment)
    }

    private suspend fun runPointPing(point: String): BasementPointResult {
        val total = config.pingSeconds.coerceAtLeast(1)
        for (seq in 1..total) {
            if (!BasementTestStore.state.value.isRunning) break
            val cycleStarted = System.currentTimeMillis()
            val (rtt, message) = runSinglePing(config.host, 2000L)
            val snap = lastSample
            pingSamples += BasementPingSample(
                round = currentRound,
                point = point,
                timestampMs = System.currentTimeMillis(),
                sequence = seq,
                success = rtt != null,
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
        val rows = pingSamples.filter { it.round == currentRound && it.point == point }
        val sent = rows.size
        val recv = rows.count { it.success }
        val values = rows.mapNotNull { it.rttMs }
        val loss = if (sent == 0) 100.0 else (sent - recv) * 100.0 / sent
        return BasementPointResult(
            round = currentRound,
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

    private fun segmentFor(stage: BasementStage): String {
        val live = BasementTestStore.state.value
        return when (stage) {
            BasementStage.PREPARED -> ""
            BasementStage.ROUTE_TRAVEL -> live.currentSegment
            BasementStage.POINT_STABILIZING, BasementStage.POINT_PING, BasementStage.POINT_COMPLETE -> live.currentPointName
            BasementStage.RECOVERY, BasementStage.RECOVERY_COMPLETE -> live.currentPointName.ifBlank { "Recovery" } + " Recovery"
            else -> live.currentSegment
        }
    }

    private fun appendEvent(
        type: String,
        from: String,
        to: String,
        description: String,
        segment: String = segmentFor(BasementTestStore.state.value.stage)
    ) {
        events += BasementEvent(currentRound, System.currentTimeMillis(), segment, type, from, to, null, description)
    }

    private fun updateStage(stage: BasementStage, message: String) {
        BasementTestStore.state.value = BasementTestStore.state.value.copy(
            stage = stage,
            stageStartedAt = System.currentTimeMillis(),
            countdownSeconds = null,
            statusMessage = message
        )
        updateOverlay()
        updateNotification(message)
    }

    private fun updateCountdown(value: Int?) {
        BasementTestStore.state.value = BasementTestStore.state.value.copy(countdownSeconds = value)
        updateOverlay()
    }

    private fun runRecovery(arrivalMs: Long) {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            val requireNr = startHadNr == true
            val requireLte = startHadLte != false
            val arrivalSnap = lastSample

            fun lteAvailable(snap: BasementNetworkSample?): Boolean =
                snap != null && snap.registered && (
                    snap.lteRsrp != "--" || snap.rat == "4G" || snap.displayRat.contains("LTE", true)
                )
            fun nrAvailable(snap: BasementNetworkSample?): Boolean =
                snap != null && (snap.nrState == "CONNECTED" || snap.rat == "5G")

            // If the capability is already present at the exact ARRIVE START mark, this is not
            // a "recovery after arrival"; report 0.0 s instead of waiting two more samples and
            // misleadingly showing ~1–2 s.
            var lteRecovery: Long? = when {
                !requireLte -> -2L
                lteAvailable(arrivalSnap) -> 0L
                else -> null
            }
            var nrRecovery: Long? = when {
                !requireNr -> -2L
                nrAvailable(arrivalSnap) -> 0L
                else -> null
            }
            var dataRecovery: Long? = null

            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                lteRecoveryRequired = requireLte,
                nrRecoveryRequired = requireNr,
                lteRecoveryMs = lteRecovery,
                nrRecoveryMs = nrRecovery,
                dataRecoveryMs = null
            )

            var lteStreak = 0
            var nrStreak = 0
            var lteFirstSeenMs: Long? = null
            var nrFirstSeenMs: Long? = null

            val maxSeconds = config.recoverySeconds.coerceAtLeast(1)
            for (sec in 1..maxSeconds) {
                if (!BasementTestStore.state.value.isRunning) return@launch
                val cycleStarted = System.currentTimeMillis()
                val snap = lastSample
                val lteNow = lteAvailable(snap)
                val nrNow = nrAvailable(snap)

                if (lteRecovery == null) {
                    if (lteNow) {
                        if (lteStreak == 0) lteFirstSeenMs = snap?.timestampMs ?: System.currentTimeMillis()
                        lteStreak++
                    } else {
                        lteStreak = 0
                        lteFirstSeenMs = null
                    }
                    if (lteStreak >= 2) {
                        lteRecovery = ((lteFirstSeenMs ?: System.currentTimeMillis()) - arrivalMs).coerceAtLeast(0L)
                        appendEvent("LTE_RECOVERY", "", "${lteRecovery}ms", "LTE restored and confirmed by 2 consecutive 1Hz samples")
                    }
                }

                if (nrRecovery == null) {
                    if (nrNow) {
                        if (nrStreak == 0) nrFirstSeenMs = snap?.timestampMs ?: System.currentTimeMillis()
                        nrStreak++
                    } else {
                        nrStreak = 0
                        nrFirstSeenMs = null
                    }
                    if (nrStreak >= 2) {
                        nrRecovery = ((nrFirstSeenMs ?: System.currentTimeMillis()) - arrivalMs).coerceAtLeast(0L)
                        appendEvent("NR_RECOVERY", "", "${nrRecovery}ms", "NR restored and confirmed by 2 consecutive 1Hz samples")
                    }
                }

                // Data Recovery is based on real data-plane availability. The first successful
                // recovery Ping is T_data; no icon-only inference is used.
                val (rtt, recoveryMessage) = runSinglePing(config.host, 850L)
                val recoverySnap = lastSample
                pingSamples += BasementPingSample(
                    round = currentRound,
                    point = "${config.routePoints.lastOrNull()?.name ?: "START"} Recovery",
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
                if (dataRecovery == null && rtt != null) {
                    dataRecovery = (System.currentTimeMillis() - arrivalMs).coerceAtLeast(0L)
                    appendEvent("DATA_RECOVERY", "", "${dataRecovery}ms", "First successful recovery Ping")
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
                    completeCurrentRound(false)
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
            completeCurrentRound(true)
        }
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
        if (BasementTestStore.state.value.stage == BasementStage.PREPARED) {
            BasementTestStore.state.value = BasementLiveState()
            removeOverlay()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        sampleJob?.cancel(); sampleJob = null
        stageJob?.cancel(); stageJob = null
        recoveryJob?.cancel(); recoveryJob = null
        if (noServiceStartedAt != null) {
            val now = System.currentTimeMillis()
            events += BasementEvent(currentRound, now, segmentFor(BasementTestStore.state.value.stage), "NO_SERVICE_END", "NO_SERVICE", "TEST_END", now - noServiceStartedAt!!, "Test ended while out of service")
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
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/CellTracker/Reports/$date/WeakCoverage/$folder"

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
        appendLine("round,timestamp_ms,timestamp,stage,segment,sim,subscription_id,operator,rat,display_rat,registered,lte_band,lte_pci,lte_earfcn,lte_rsrp,lte_rsrq,lte_sinr,lte_cell_id,lte_tac,nr_state,nr_band,nr_pci,nr_arfcn,nr_ss_rsrp,nr_ss_rsrq,nr_ss_sinr,nr_cell_id,nr_tac,data_state")
        samples.forEach { s ->
            appendLine(listOf(
                s.round, s.timestampMs, timeText(s.timestampMs), s.stage, s.segment, s.simSlot + 1, s.subscriptionId, s.operator,
                s.rat, s.displayRat, s.registered, s.lteBand, s.ltePci, s.lteArfcn, s.lteRsrp, s.lteRsrq, s.lteSinr,
                s.lteCellId, s.lteTac, s.nrState, s.nrBand, s.nrPci, s.nrArfcn, s.nrSsRsrp, s.nrSsRsrq, s.nrSsSinr,
                s.nrCellId, s.nrTac, s.dataState
            ).joinToString(",") { csv(it.toString()) })
        }
    }

    private fun buildEventsCsv(): String = buildString {
        appendLine("round,timestamp_ms,timestamp,segment,segment_elapsed_ms,event_type,from,to,duration_ms,description")
        events.sortedBy { it.timestampMs }.forEach { e ->
            val elapsed = segmentStart[segmentKey(e.round, e.segment)]?.let { (e.timestampMs - it).coerceAtLeast(0L) } ?: ""
            appendLine(listOf(e.round, e.timestampMs, timeText(e.timestampMs), e.segment, elapsed, e.type, e.from, e.to, e.durationMs ?: "", e.description)
                .joinToString(",") { csv(it.toString()) })
        }
    }

    private fun buildPingCsv(): String = buildString {
        appendLine("round,point,timestamp_ms,timestamp,sequence,result,rtt_ms,rat,rsrp,message")
        pingSamples.forEach { p ->
            appendLine(listOf(p.round, p.point, p.timestampMs, timeText(p.timestampMs), p.sequence, if (p.success) "PASS" else "FAIL",
                p.rttMs?.let { String.format(Locale.US, "%.2f", it) } ?: "", p.rat, p.rsrp, p.message)
                .joinToString(",") { csv(it.toString()) })
        }
    }

    private fun buildSummaryCsv(endedAt: Long, status: String): String = buildString {
        appendLine("section,item,value")
        fun row(section: String, item: String, value: String) =
            appendLine("${csv(section)},${csv(item)},${csv(value)}")

        val completed = roundSummaries.sortedBy { it.round }
        row("Session", "Status", status)
        row("Session", "Route Name", config.routeName)
        row("Session", "Route", config.routePoints.joinToString(" → ") { it.name })
        row("Session", "Configured Rounds", config.rounds.toString())
        row("Session", "Completed Rounds", completed.size.toString())
        row("Session", "Started", timeText(sessionStartedAt))
        row("Session", "Ended", timeText(endedAt))
        row("Session", "Duration", formatDuration(endedAt - sessionStartedAt))

        fun avgRecovery(selector: (RoundSummary) -> Long?, required: (RoundSummary) -> Boolean): String {
            val values = completed.filter(required).mapNotNull(selector).filter { it >= 0L }
            return if (values.isEmpty()) "N/A" else String.format(Locale.US, "%.1fs", values.average() / 1000.0)
        }

        row("Average", "LTE Recovery", avgRecovery({ it.lteRecoveryMs }, { it.lteRecoveryRequired }))
        row("Average", "Data Recovery", avgRecovery({ it.dataRecoveryMs }, { true }))
        row("Average", "5G Recovery", avgRecovery({ it.nrRecoveryMs }, { it.nrRecoveryRequired }))
        if (completed.isNotEmpty()) {
            row("Average", "No Service Count", String.format(Locale.US, "%.2f", completed.map { it.noServiceCount }.average()))
            row("Average", "Total No Service", formatDuration(completed.map { it.noServiceTotalMs }.average().toLong()))
            row("Average", "Longest No Service", formatDuration(completed.map { it.noServiceLongestMs }.average().toLong()))
        }

        config.routePoints.filter { it.pingEnabled }.forEach { point ->
            val rows = completed.flatMap { r -> r.points.filter { it.point == point.name } }
            if (rows.isNotEmpty()) {
                row("Average Point ${point.name}", "Success Rate", String.format(Locale.US, "%.1f%%", rows.map { it.successPct }.average()))
                val rtts = rows.mapNotNull { it.avgRttMs }
                row("Average Point ${point.name}", "Avg RTT", if (rtts.isEmpty()) "--" else String.format(Locale.US, "%.1f ms", rtts.average()))
                row("Average Point ${point.name}", "PASS Rounds", "${rows.count { it.result == "PASS" }} / ${rows.size}")
            }
        }

        routeSegments().forEach { seg ->
            val metrics = completed.mapNotNull { r -> r.segments.firstOrNull { it.segment == seg } }
            if (metrics.isNotEmpty()) {
                row("Average Segment $seg", "Duration", formatDuration(metrics.map { it.durationMs }.average().toLong()))
                listOf("5G", "4G", "3G", "2G", "NO_SERVICE").forEach { rat ->
                    val avg = metrics.map { it.ratShare[rat] ?: 0.0 }.average()
                    row("Average Segment $seg", "$rat Share", String.format(Locale.US, "%.1f%%", avg))
                }
                val lte = metrics.mapNotNull { it.lteAvgRsrp }
                val nr = metrics.mapNotNull { it.nrAvgRsrp }
                row("Average Segment $seg", "LTE Avg RSRP", if (lte.isEmpty()) "--" else String.format(Locale.US, "%.1f dBm", lte.average()))
                row("Average Segment $seg", "NR Avg SS-RSRP", if (nr.isEmpty()) "--" else String.format(Locale.US, "%.1f dBm", nr.average()))
                val bands = metrics.flatMap { it.bandShare.keys }.toSet()
                bands.sorted().forEach { band ->
                    val avg = metrics.map { it.bandShare[band] ?: 0.0 }.average()
                    row("Average Segment $seg", "Band $band Share", String.format(Locale.US, "%.1f%%", avg))
                }
            }
        }

        completed.forEach { r ->
            row("Round ${r.round}", "Recovery LTE", recoveryText(r.lteRecoveryMs, r.lteRecoveryRequired))
            row("Round ${r.round}", "Recovery Data", recoveryText(r.dataRecoveryMs, true))
            row("Round ${r.round}", "Recovery 5G", recoveryText(r.nrRecoveryMs, r.nrRecoveryRequired))
            row("Round ${r.round}", "No Service Count", r.noServiceCount.toString())
            row("Round ${r.round}", "Total No Service", formatDuration(r.noServiceTotalMs))
            r.points.forEach { p ->
                row("Round ${r.round} · ${p.point}", "Success Rate", String.format(Locale.US, "%.1f%%", p.successPct))
                row("Round ${r.round} · ${p.point}", "Avg RTT", p.avgRttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--")
                row("Round ${r.round} · ${p.point}", "Result", p.result)
            }
            r.segments.forEach { seg ->
                row("Round ${r.round} · ${seg.segment}", "Duration", formatDuration(seg.durationMs))
                seg.ratShare.entries.sortedBy { it.key }.forEach { (rat, share) ->
                    row("Round ${r.round} · ${seg.segment}", "$rat Share", String.format(Locale.US, "%.1f%%", share))
                }
                seg.bandShare.entries.sortedBy { it.key }.forEach { (band, share) ->
                    row("Round ${r.round} · ${seg.segment}", "Band $band Share", String.format(Locale.US, "%.1f%%", share))
                }
            }
        }
    }

    private data class BasementTimelineInterval(
        val startMs: Long,
        val endMs: Long,
        val rat: String,
        val band: String
    )

    private fun timelineIntervals(segment: String, fallbackEnd: Long, round: Int = currentRound): List<BasementTimelineInterval> {
        val key = segmentKey(round, segment)
        val start = segmentStart[key] ?: return emptyList()
        val end = (segmentEnd[key] ?: fallbackEnd).coerceAtLeast(start)
        if (end <= start) return emptyList()

        val rows = samples.filter { it.round == round && it.segment == segment && it.timestampMs <= end }.sortedBy { it.timestampMs }
        val previous = samples.filter { it.round == round && it.timestampMs <= start }.maxByOrNull { it.timestampMs }
        var current = previous ?: rows.firstOrNull() ?: return emptyList()
        var cursor = start
        val raw = mutableListOf<BasementTimelineInterval>()

        fun bandOf(row: BasementNetworkSample): String = when (row.rat) {
            "5G" -> row.nrBand.takeIf { it != "--" } ?: "5G UNKNOWN"
            "4G" -> row.lteBand.takeIf { it != "--" } ?: "4G UNKNOWN"
            "3G", "2G" -> row.displayRat.ifBlank { row.rat }
            "NO_SERVICE" -> "NO SERVICE"
            else -> row.displayRat.ifBlank { "UNKNOWN" }
        }

        rows.forEach { row ->
            val t = row.timestampMs.coerceIn(start, end)
            if (t > cursor) {
                raw += BasementTimelineInterval(cursor, t, current.rat, bandOf(current))
                cursor = t
            }
            current = row
        }
        if (cursor < end) raw += BasementTimelineInterval(cursor, end, current.rat, bandOf(current))

        // Merge adjacent periods with identical RAT+Band. The transition is then naturally the
        // boundary between two rows, so distribution and transition data live in one table.
        val merged = mutableListOf<BasementTimelineInterval>()
        raw.forEach { row ->
            val last = merged.lastOrNull()
            if (last != null && last.rat == row.rat && last.band == row.band && last.endMs == row.startMs) {
                merged[merged.lastIndex] = last.copy(endMs = row.endMs)
            } else merged += row
        }
        return merged
    }

    private fun buildSummaryHtml(endedAt: Long, status: String): String {
        val completed = roundSummaries.sortedBy { it.round }

        fun avgRecovery(selector: (RoundSummary) -> Long?, required: (RoundSummary) -> Boolean): String {
            val values = completed.filter(required).mapNotNull(selector).filter { it >= 0L }
            return if (values.isEmpty()) "N/A" else String.format(Locale.US, "%.1fs", values.average() / 1000.0)
        }

        val pointAverageRows = config.routePoints.filter { it.pingEnabled }.joinToString("") { point ->
            val rows = completed.flatMap { r -> r.points.filter { it.point == point.name } }
            if (rows.isEmpty()) "" else {
                val success = rows.map { it.successPct }.average()
                val rtts = rows.mapNotNull { it.avgRttMs }
                val avgRtt = if (rtts.isEmpty()) "--" else String.format(Locale.US, "%.1f ms", rtts.average())
                "<tr><td>${html(point.name)}</td><td>${String.format(Locale.US, "%.1f%%", success)}</td><td>$avgRtt</td><td>${rows.count { it.result == "PASS" }} / ${rows.size}</td></tr>"
            }
        }.ifBlank { "<tr><td colspan='4'>No fixed-point Ping results</td></tr>" }

        val segmentAverageRows = routeSegments().joinToString("") { seg ->
            val metrics = completed.mapNotNull { r -> r.segments.firstOrNull { it.segment == seg } }
            if (metrics.isEmpty()) "" else {
                fun avgRat(rat: String) = String.format(Locale.US, "%.1f%%", metrics.map { it.ratShare[rat] ?: 0.0 }.average())
                val lte = metrics.mapNotNull { it.lteAvgRsrp }
                val nr = metrics.mapNotNull { it.nrAvgRsrp }
                "<tr><td>${html(seg)}</td><td>${formatDuration(metrics.map { it.durationMs }.average().toLong())}</td><td>${avgRat("5G")}</td><td>${avgRat("4G")}</td><td>${avgRat("3G")}</td><td>${avgRat("2G")}</td><td>${avgRat("NO_SERVICE")}</td><td>${if(lte.isEmpty()) "--" else String.format(Locale.US, "%.1f", lte.average())}</td><td>${if(nr.isEmpty()) "--" else String.format(Locale.US, "%.1f", nr.average())}</td></tr>"
            }
        }.ifBlank { "<tr><td colspan='9'>No completed segment data</td></tr>" }

        val roundSections = completed.joinToString("") { r ->
            val pointRows = r.points.joinToString("") { p ->
                "<tr><td>${html(p.point)}</td><td>${String.format(Locale.US, "%.1f%%", p.successPct)}</td><td>${p.avgRttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--"}</td><td>${html(p.result)}</td></tr>"
            }.ifBlank { "<tr><td colspan='4'>No Ping points</td></tr>" }

            val segmentRows = r.segments.joinToString("") { seg ->
                fun share(rat: String) = String.format(Locale.US, "%.1f%%", seg.ratShare[rat] ?: 0.0)
                "<tr><td>${html(seg.segment)}</td><td>${formatDuration(seg.durationMs)}</td><td>${share("5G")}</td><td>${share("4G")}</td><td>${share("3G")}</td><td>${share("2G")}</td><td>${share("NO_SERVICE")}</td></tr>"
            }

            val eventBlocks = routeSegments().joinToString("") { seg ->
                val rows = events.filter { it.round == r.round && it.segment == seg }.sortedBy { it.timestampMs }
                    .joinToString("") { e ->
                        "<tr><td>${timeText(e.timestampMs)}</td><td>${html(e.type)}</td><td>${html(e.from)}</td><td>${html(e.to)}</td><td>${e.durationMs?.let { formatDuration(it) } ?: "--"}</td><td>${html(e.description)}</td></tr>"
                    }.ifBlank { "<tr><td colspan='6'>None</td></tr>" }
                "<h4>${html(seg)}</h4><table><tr><th>Time</th><th>Event</th><th>From</th><th>To</th><th>Duration</th><th>Description</th></tr>$rows</table>"
            }

            """
            <section class="segment-block">
              <h2>Round ${r.round}</h2>
              <div class="mini-grid">
                <div><b>LTE Recovery</b><br>${recoveryText(r.lteRecoveryMs, r.lteRecoveryRequired)}</div>
                <div><b>Data Recovery</b><br>${recoveryText(r.dataRecoveryMs, true)}</div>
                <div><b>5G Recovery</b><br>${recoveryText(r.nrRecoveryMs, r.nrRecoveryRequired)}</div>
                <div><b>Total No Service</b><br>${formatDuration(r.noServiceTotalMs)}</div>
              </div>
              <h3>Fixed Points</h3>
              <table><tr><th>Point</th><th>Success Rate</th><th>Avg RTT</th><th>Result</th></tr>$pointRows</table>
              <h3>Route Segments</h3>
              <table><tr><th>Segment</th><th>Duration</th><th>5G</th><th>4G</th><th>3G</th><th>2G</th><th>No Service</th></tr>$segmentRows</table>
              <h3>Network Events</h3>
              $eventBlocks
            </section>
            """.trimIndent()
        }.ifBlank { "<p>No completed rounds yet.</p>" }

        val avgNoService = if (completed.isEmpty()) "--" else formatDuration(completed.map { it.noServiceTotalMs }.average().toLong())

        return """
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Weak Coverage Route Summary</title>
<style>
body{font-family:sans-serif;margin:18px;color:#1f2937}
h1{font-size:24px}.grid,.mini-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
.card,.segment-block{border:1px solid #ddd;border-radius:12px;padding:12px;margin-bottom:14px}
.k{font-size:12px;color:#666}.v{font-size:18px;font-weight:700;margin-top:4px}
table{border-collapse:collapse;width:100%;margin-top:8px;margin-bottom:14px}
th,td{border-bottom:1px solid #ddd;padding:8px;text-align:left;font-size:13px;vertical-align:top}
th{background:#f6f6f6}.note{background:#f6f7fb;border-radius:10px;padding:10px;font-size:13px}
</style></head><body>
<h1>Weak Coverage Route Summary</h1>
<p><b>Route:</b> ${html(config.routeName)} · ${html(config.routePoints.joinToString(" → ") { it.name })}</p>
<p><b>Rounds:</b> ${completed.size} completed / ${config.rounds} configured</p>
<div class="grid">
<div class="card"><div class="k">Status</div><div class="v">${html(status)}</div></div>
<div class="card"><div class="k">Avg LTE Recovery</div><div class="v">${avgRecovery({ it.lteRecoveryMs }, { it.lteRecoveryRequired })}</div></div>
<div class="card"><div class="k">Avg Data Recovery</div><div class="v">${avgRecovery({ it.dataRecoveryMs }, { true })}</div></div>
<div class="card"><div class="k">Avg 5G Recovery</div><div class="v">${avgRecovery({ it.nrRecoveryMs }, { it.nrRecoveryRequired })}</div></div>
<div class="card"><div class="k">Avg No Service</div><div class="v">$avgNoService</div></div>
</div>
<div class="note"><b>Average rule:</b> each completed route round has equal weight. Fixed-point Success Rate / RTT, Recovery and Route Segment statistics are averaged across completed rounds.</div>
<h2>Average Fixed Points</h2>
<table><tr><th>Point</th><th>Avg Success Rate</th><th>Avg RTT</th><th>PASS Rounds</th></tr>$pointAverageRows</table>
<h2>Average Route Segments</h2>
<table><tr><th>Segment</th><th>Avg Duration</th><th>5G</th><th>4G</th><th>3G</th><th>2G</th><th>No Service</th><th>LTE Avg RSRP</th><th>NR Avg SS-RSRP</th></tr>$segmentAverageRows</table>
<h2>Round Details</h2>
$roundSections
<h2>Session</h2>
<p>${timeText(sessionStartedAt)} → ${timeText(endedAt)} · ${formatDuration(endedAt-sessionStartedAt)}</p>
<p>Raw files: network_raw.csv · events.csv · ping.csv · summary.csv</p>
</body></html>
""".trimIndent()
    }

    /**
     * Exact segment-time allocation. v0.37 started counting at the first sample after the
     * segment mark, leaving the first ~0–1 s unclassified; therefore an all-LTE segment could
     * show 96–99% instead of 100%. v0.38 allocates the whole [segmentStart, segmentEnd] range.
     */
    private fun distributionFor(
        segment: String,
        fallbackEnd: Long,
        round: Int = currentRound,
        selector: (BasementNetworkSample) -> String
    ): Map<String, Long> {
        val key = segmentKey(round, segment)
        val start = segmentStart[key] ?: return emptyMap()
        val end = (segmentEnd[key] ?: fallbackEnd).coerceAtLeast(start)
        if (end <= start) return emptyMap()

        val rows = samples.filter { it.round == round && it.segment == segment && it.timestampMs <= end }.sortedBy { it.timestampMs }
        val previous = samples.filter { it.round == round && it.timestampMs <= start }.maxByOrNull { it.timestampMs }
        var current = previous ?: rows.firstOrNull()
        if (current == null) return mapOf("UNKNOWN" to (end - start))

        val out = linkedMapOf<String, Long>()
        var cursor = start
        rows.forEach { row ->
            val t = row.timestampMs.coerceIn(start, end)
            if (t > cursor) {
                val key = selector(current!!).ifBlank { "UNKNOWN" }
                out[key] = (out[key] ?: 0L) + (t - cursor)
                cursor = t
            }
            current = row
        }
        if (cursor < end) {
            val key = selector(current!!).ifBlank { "UNKNOWN" }
            out[key] = (out[key] ?: 0L) + (end - cursor)
        }
        return out
    }

    private fun ratDistribution(segment: String, fallbackEnd: Long, round: Int = currentRound): Map<String, Long> =
        distributionFor(segment, fallbackEnd, round) { it.rat }

    private fun bandDistribution(segment: String, fallbackEnd: Long, round: Int = currentRound): Map<String, Long> =
        distributionFor(segment, fallbackEnd, round) { row ->
            when (row.rat) {
                "5G" -> row.nrBand.takeIf { it != "--" } ?: "5G UNKNOWN"
                "4G" -> row.lteBand.takeIf { it != "--" } ?: "4G UNKNOWN"
                "3G", "2G" -> "${row.rat} ${row.displayRat}".trim()
                "NO_SERVICE" -> "NO SERVICE"
                else -> row.displayRat.ifBlank { "UNKNOWN" }
            }
        }

    private fun initialRetentionMs(rat: String, round: Int = currentRound): Long {
        val firstSeg = routeSegments().firstOrNull() ?: return 0L
        val routeRows = samples.filter { it.round == round && it.segment == firstSeg }.sortedBy { it.timestampMs }
        if (routeRows.isEmpty() || routeRows.first().rat != rat) return 0L
        val firstOther = routeRows.firstOrNull { it.rat != rat }?.timestampMs
            ?: segmentEnd[segmentKey(round, firstSeg)]
            ?: routeRows.last().timestampMs
        return (firstOther - routeRows.first().timestampMs).coerceAtLeast(0L)
    }

    private fun firstContiguousRatMs(rat: String, round: Int = currentRound): Long {
        val routeRows = samples.filter { it.round == round && it.segment in routeSegments().toSet() }.sortedBy { it.timestampMs }
        val first = routeRows.indexOfFirst { it.rat == rat }
        if (first < 0) return 0L
        var end = routeRows[first].timestampMs
        for (i in first + 1 until routeRows.size) {
            if (routeRows[i].rat != rat) break
            end = routeRows[i].timestampMs
        }
        return (end - routeRows[first].timestampMs + 1000L).coerceAtLeast(0L)
    }

    private fun noServiceStats(endedAt: Long, round: Int? = null): Triple<Int, Long, Long> {
        val intervals = mutableListOf<Long>()
        var start: Long? = null
        samples.filter { round == null || it.round == round }.sortedBy { it.timestampMs }.forEach { s ->
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

    private fun snapshotCurrentRound(recoveryTimedOut: Boolean) {
        val live = BasementTestStore.state.value
        val segments = routeSegments().mapNotNull { seg ->
            val key = segmentKey(currentRound, seg)
            val start = segmentStart[key] ?: return@mapNotNull null
            val end = segmentEnd[key] ?: System.currentTimeMillis()
            val duration = (end - start).coerceAtLeast(1L)
            val rat = ratDistribution(seg, end, currentRound)
            val band = bandDistribution(seg, end, currentRound)
            val lte = samples.filter { it.round == currentRound && it.segment == seg }.mapNotNull { it.lteRsrp.toDoubleOrNull() }
            val nr = samples.filter { it.round == currentRound && it.segment == seg }.mapNotNull { it.nrSsRsrp.toDoubleOrNull() }
            SegmentRoundMetric(
                segment = seg,
                durationMs = duration,
                ratShare = rat.mapValues { (_, ms) -> ms * 100.0 / duration },
                bandShare = band.mapValues { (_, ms) -> ms * 100.0 / duration },
                lteAvgRsrp = lte.takeIf { it.isNotEmpty() }?.average(),
                nrAvgRsrp = nr.takeIf { it.isNotEmpty() }?.average()
            )
        }
        val points = pointResults.values
            .filter { it.round == currentRound }
            .map { r ->
                PointRoundMetric(
                    point = r.point,
                    successPct = if (r.sent > 0) r.received * 100.0 / r.sent else 0.0,
                    avgRttMs = r.avgRttMs,
                    result = r.result
                )
            }
        val ns = noServiceStats(System.currentTimeMillis(), currentRound)
        roundSummaries.removeAll { it.round == currentRound }
        roundSummaries += RoundSummary(
            round = currentRound,
            segments = segments,
            points = points,
            lteRecoveryMs = live.lteRecoveryMs,
            dataRecoveryMs = live.dataRecoveryMs,
            nrRecoveryMs = live.nrRecoveryMs,
            lteRecoveryRequired = live.lteRecoveryRequired,
            nrRecoveryRequired = live.nrRecoveryRequired,
            recoveryTimedOut = recoveryTimedOut,
            noServiceCount = ns.first,
            noServiceTotalMs = ns.second,
            noServiceLongestMs = ns.third
        )
    }

    private fun completeCurrentRound(timedOut: Boolean) {
        snapshotCurrentRound(timedOut)
        if (currentRound < config.rounds) {
            updateStage(BasementStage.ROUND_COMPLETE, "Round $currentRound/${config.rounds} completed")
            BasementTestStore.state.value = BasementTestStore.state.value.copy(
                recoveryTimedOut = timedOut,
                statusMessage = "Round $currentRound/${config.rounds} completed"
            )
            updateOverlay()
        } else {
            updateStage(BasementStage.RECOVERY_COMPLETE, if (timedOut) "Recovery timeout" else "Recovery completed")
            BasementTestStore.state.value = BasementTestStore.state.value.copy(recoveryTimedOut = timedOut)
            updateOverlay()
        }
    }

    private fun startNextRound() {
        if (currentRound >= config.rounds) return
        currentRound++
        activeSegmentIndex = 0
        startHadNr = null
        startHadLte = null
        noServiceStartedAt = null
        pointResults.entries.removeAll { it.value.round == currentRound }
        val now = System.currentTimeMillis()
        val firstSegment = routeSegmentName(0)
        segmentStart[segmentKey(currentRound, firstSegment)] = now
        val from = config.routePoints.first().name
        val to = config.routePoints[1].name
        BasementTestStore.state.value = BasementTestStore.state.value.copy(
            stage = BasementStage.ROUTE_TRAVEL,
            stageStartedAt = now,
            currentSegment = firstSegment,
            currentPointName = from,
            nextPointName = to,
            routeSegmentIndex = 1,
            routeSegmentCount = config.routePoints.size - 1,
            currentRound = currentRound,
            totalRounds = config.rounds,
            countdownSeconds = null,
            pingProgress = 0,
            pingTotal = 0,
            lastPointResult = null,
            lteRecoveryMs = null,
            dataRecoveryMs = null,
            nrRecoveryMs = null,
            recoveryTimedOut = false,
            statusMessage = "Round $currentRound/${config.rounds} · Walking: $from → $to"
        )
        appendEvent("ROUND_START", "", from, "Round $currentRound started", firstSegment)
        updateOverlay()
        updateNotification("Round $currentRound/${config.rounds} · $firstSegment")
    }

    private fun avgLong(values: List<Long?>): Long? {
        val valid = values.filterNotNull().filter { it >= 0L }
        return if (valid.isEmpty()) null else valid.average().toLong()
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
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - dp(238)).coerceAtLeast(0)
            y = dp(80)
        }
        overlayLp = lp

        // Drag by the title area so the primary action button remains easy to tap while walking.
        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        overlayTitle?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    downX = lp.x; downY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (resources.displayMetrics.widthPixels - dp(230)).coerceAtLeast(0)
                    val maxY = (resources.displayMetrics.heightPixels - dp(120)).coerceAtLeast(0)
                    lp.x = (downX + (event.rawX - downRawX).toInt()).coerceIn(0, maxX)
                    lp.y = (downY + (event.rawY - downRawY).toInt()).coerceIn(0, maxY)
                    runCatching { wm.updateViewLayout(root, lp) }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(root, lp); overlay = root }
    }

    private fun updateOverlay() {
        val state = BasementTestStore.state.value
        android.os.Handler(mainLooper).post {
            val title = when (state.stage) {
                BasementStage.PREPARED -> "${config.routeName} · Ready"
                BasementStage.ROUTE_TRAVEL -> state.currentSegment
                BasementStage.POINT_STABILIZING, BasementStage.POINT_PING, BasementStage.POINT_COMPLETE -> state.currentPointName
                BasementStage.RECOVERY, BasementStage.RECOVERY_COMPLETE -> "${state.currentPointName} · Recovery"
                BasementStage.ROUND_COMPLETE -> "Round ${state.currentRound} Completed"
                else -> state.stage.label
            }
            overlayTitle?.text = "Weak Coverage · $title"
            overlayNetwork?.text = "${state.currentRat} · RSRP ${state.currentRsrp} dBm"
            overlaySub?.text = overlaySubText(state)
            val action = primaryActionLabel(state)
            overlayButton?.text = action.first
            overlayButton?.isEnabled = action.second
        }
    }

    private fun overlaySubText(state: BasementLiveState): String = when (state.stage) {
        BasementStage.POINT_STABILIZING -> "Stabilizing: ${state.countdownSeconds ?: 0}s"
        BasementStage.POINT_PING -> if (state.pingProgress < state.pingTotal) {
            "Ping: ${state.pingProgress}/${state.pingTotal}s"
        } else state.lastPointResult?.let {
            val success = if (it.sent > 0) it.received * 100.0 / it.sent else 0.0
            "Success ${String.format(Locale.US, "%.1f%%", success)} · Avg ${it.avgRttMs?.let { v -> String.format(Locale.US, "%.0fms", v) } ?: "--"}"
        } ?: "Ping testing"
        BasementStage.POINT_COMPLETE -> state.lastPointResult?.let {
            val success = if (it.sent > 0) it.received * 100.0 / it.sent else 0.0
            "${it.result} · Success ${String.format(Locale.US, "%.1f%%", success)}"
        } ?: "Point completed"
        BasementStage.RECOVERY, BasementStage.RECOVERY_COMPLETE ->
            "LTE ${shortRecovery(state.lteRecoveryMs, state.lteRecoveryRequired)} · Data ${shortRecovery(state.dataRecoveryMs, true)} · 5G ${shortRecovery(state.nrRecoveryMs, state.nrRecoveryRequired)}"
        BasementStage.ROUND_COMPLETE -> "Round ${state.currentRound}/${state.totalRounds} completed"
        BasementStage.ROUTE_TRAVEL -> "Round ${state.currentRound}/${state.totalRounds} · Segment ${state.routeSegmentIndex}/${state.routeSegmentCount}"
        else -> state.operator
    }

    private fun shortRecovery(v: Long?, required: Boolean): String {
        if (!required || v == -2L) return "N/A"
        return v?.let { String.format(Locale.US, "%.1fs", it / 1000.0) } ?: "…"
    }

    private fun primaryActionLabel(state: BasementLiveState): Pair<String, Boolean> = when (state.stage) {
        BasementStage.PREPARED -> "START TEST" to true
        BasementStage.ROUTE_TRAVEL -> "ARRIVE ${state.nextPointName.uppercase(Locale.US)}" to true
        BasementStage.POINT_COMPLETE -> "START → ${state.nextPointName.uppercase(Locale.US)}" to true
        BasementStage.ROUND_COMPLETE -> "START ROUND ${state.currentRound + 1}" to true
        BasementStage.RECOVERY_COMPLETE -> "FINISH TEST" to true
        BasementStage.POINT_STABILIZING -> "AUTO WAIT" to false
        BasementStage.POINT_PING -> "PING RUNNING" to false
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
        overlayLp = null
    }

    private fun startForegroundServiceNotification(text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CellTracker Weak Coverage")
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
            .setContentTitle("CellTracker Weak Coverage")
            .setContentText("$text · ${state.currentRat} ${state.currentRsrp} dBm")
            .setOngoing(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Weak Coverage Route Test", NotificationManager.IMPORTANCE_LOW)
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

    private fun readConfig(intent: Intent): BasementTestConfig {
        val names = intent.getStringArrayListExtra(EXTRA_ROUTE_NAMES)
            ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        val pingFlags = intent.getBooleanArrayExtra(EXTRA_ROUTE_PING) ?: BooleanArray(0)
        val points = if (names.size >= 2) {
            names.mapIndexed { index, name ->
                WeakCoveragePoint(name, pingFlags.getOrNull(index) ?: (index in 1 until names.lastIndex))
            }
        } else BasementTestConfig().routePoints
        return BasementTestConfig(
            deviceLabel = intent.getStringExtra(EXTRA_DEVICE_LABEL).orEmpty().ifBlank { "DUT" },
            routeName = intent.getStringExtra(EXTRA_ROUTE_NAME).orEmpty().ifBlank { "Basement" },
            routePoints = points,
            host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "8.8.8.8" },
            stabilizeSeconds = intent.getIntExtra(EXTRA_STABILIZE_SECONDS, 30).coerceIn(0, 120),
            pingSeconds = intent.getIntExtra(EXTRA_PING_SECONDS, 60).coerceIn(1, 180),
            recoverySeconds = intent.getIntExtra(EXTRA_RECOVERY_SECONDS, 60).coerceIn(5, 180),
            selectedSubscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1),
            rounds = intent.getIntExtra(EXTRA_ROUNDS, 1).coerceIn(1, 20)
        )
    }

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
        const val ACTION_PREPARE = "com.example.celltracker.BASEMENT_PREPARE"
        const val ACTION_START = "com.example.celltracker.BASEMENT_START"
        const val ACTION_PRIMARY = "com.example.celltracker.BASEMENT_PRIMARY"
        const val ACTION_ABORT = "com.example.celltracker.BASEMENT_ABORT"
        const val ACTION_FINISH = "com.example.celltracker.BASEMENT_FINISH"

        const val EXTRA_DEVICE_LABEL = "device_label"
        const val EXTRA_ROUTE_NAME = "route_name"
        const val EXTRA_ROUTE_NAMES = "route_names"
        const val EXTRA_ROUTE_PING = "route_ping"
        const val EXTRA_HOST = "host"
        const val EXTRA_STABILIZE_SECONDS = "stabilize_seconds"
        const val EXTRA_PING_SECONDS = "ping_seconds"
        const val EXTRA_RECOVERY_SECONDS = "recovery_seconds"
        const val EXTRA_SUBSCRIPTION_ID = "subscription_id"
        const val EXTRA_ROUNDS = "rounds"

        const val CHANNEL_ID = "celltracker_basement_test"
        const val NOTIFICATION_ID = 1011

        const val SEG_START_B1 = "START → B1"
        const val SEG_B1_B2 = "B1 → B2"
        const val SEG_B2_B1 = "B2 → B1 Return"
        const val SEG_B1_START = "B1 → START Return"
    }
}
