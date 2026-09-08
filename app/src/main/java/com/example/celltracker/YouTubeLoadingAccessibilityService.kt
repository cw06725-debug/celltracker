package com.example.celltracker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Path
import android.os.SystemClock
import android.accessibilityservice.GestureDescription
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class YouTubeLoadingAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var activeInstance: YouTubeLoadingAccessibilityService? = null

        /** Called after Video Loading is armed. AccessibilityService may already be connected. */
        fun requestOverlay() {
            activeInstance?.scope?.launch {
                if (activeInstance?.repo?.isArmed() == true) activeInstance?.showOverlay()
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repo: VideoLoadingRepository
    private var overlay: View? = null
    private var file: java.io.File? = null
    private var config = VideoLoadingConfig()
    private var running = false
    private var seq = 0
    private var t0 = 0L
    private var currentTitle = ""
    private var recordingStarted = false
    private val testedContentKeys = linkedSetOf<String>()
    private var autoScrollCount = 0
    private enum class CreatorMode { VIDEOS, SHORTS }
    private var sessionMode: CreatorMode? = null
    private var lockedContentTop = 0
    private var lockedContentBottom = 0
    private var overlayStatus: TextView? = null
    private var semiSawPlayback = false
    private var semiPendingClickMs = 0L
    private var semiPendingClickElapsedMs = 0L
    private var semiT0ElapsedMs = 0L
    private var semiT0Source = ""
    private var semiPendingTitle = ""
    private var semiIgnorePlaybackUntilList = false
    private var semiLastPlaybackPage = false
    private var clockJob: Job? = null
    private var semiCaptureOverlay: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    // Keep the original touch instant for a short time. If a gesture we classified as a scroll
    // unexpectedly opens a watch/Shorts page, YouTube actually treated it as a tap. Recover T0
    // from the user's real ACTION_DOWN instead of forcing RETRY or using a late page fallback.
    private var semiLastGestureWallMs = 0L
    private var semiLastGestureElapsedMs = 0L
    private var semiLastGestureTitle = ""
    // While replaying a confirmed list scroll, suppress YouTube click/page-transition events.
    // Some YouTube builds make the mini-player clickable and a replayed swipe can graze it;
    // without this guard that accidental mini-player activation becomes a phantom Attempt.
    private var semiReplayingScroll = false
    private var semiScrollGuardUntilElapsedMs = 0L

    override fun onServiceConnected() {
        activeInstance = this
        repo = VideoLoadingRepository(this)
        config = repo.loadConfig()
        if (repo.isArmed()) showOverlay()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (::repo.isInitialized && repo.isArmed() && overlay == null) showOverlay()
        if (event == null || !running || !config.semiAuto) return
        if (event.packageName?.toString() != "com.google.android.youtube") return

        val now = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow
        val playbackPage = looksLikePlaybackPage(root)
        val wasPlaybackPage = semiLastPlaybackPage
        semiLastPlaybackPage = playbackPage

        // After LOADED/AD we perform Back ourselves. YouTube can continue emitting watch-page
        // accessibility events for a short time while the page is closing. Never interpret those
        // stale events as a second semi-auto attempt. Re-arm only after the creator/list page is
        // actually visible again.
        if (semiIgnorePlaybackUntilList) {
            // Return handling is owned by completeSemiAttempt(). During YouTube's transition the
            // accessibility tree can briefly report a non-playback page and then report the old
            // watch page again. Re-arming here caused the return transition itself to become a
            // phantom second attempt. Ignore every event until the return coroutine confirms a
            // stable non-playback page.
            return
        }

        if (t0 > 0L) {
            if (playbackPage) semiSawPlayback = true
            if (semiSawPlayback && looksLikeCreatorPage(root) && !playbackPage && now - t0 > 300L) {
                completeSemiAttempt("PASS", "MANUAL_BACK", overlayStatus, performBack = false)
            }
            return
        }

        // A confirmed scroll must never create an Attempt. During replay/settling, suppress stale
        // click/page-transition events. IMPORTANT: never auto-BACK here. A real user tap made right
        // after a scroll can legitimately open a video while the short scroll guard is still active;
        // the old auto-BACK path would immediately throw the user back to the list even in SEMI mode.
        // If playback appears during the guard, leave the page alone. Once the guard expires the
        // normal touch/accessibility path can establish T0; otherwise LOADED will simply report that
        // no valid T0 was armed instead of navigating behind the user's back.
        if (semiReplayingScroll || nowElapsed < semiScrollGuardUntilElapsedMs) {
            semiPendingClickMs = 0L
            semiPendingClickElapsedMs = 0L
            semiPendingTitle = ""
            if (playbackPage && !wasPlaybackPage) {
                overlayStatus?.text = "SEMI · playback opened while scroll settled · no auto return"
            }
            return
        }

        // Semi-auto must be tolerant of OEM / YouTube accessibility differences.  Some builds
        // emit TYPE_VIEW_CLICKED from a thumbnail child, some from the card parent, and some only
        // expose the watch-page transition.  Keep the most recent plausible YouTube click as T0,
        // then commit it when the player page becomes visible.
        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED && !playbackPage) {
            // Capture T0 FIRST, validate it only after YouTube reaches a playback page.
            // AccessibilityEvent.eventTime uses uptimeMillis; convert it to both wall-clock and
            // elapsedRealtime so the report can be compared with screen recording while duration
            // uses a monotonic clock.
            val ageMs = (SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0L)
            val clickElapsed = nowElapsed - ageMs
            val clickWall = now - ageMs
            val src = event.source
            semiPendingClickMs = clickWall
            semiPendingClickElapsedMs = clickElapsed
            semiPendingTitle = src?.let {
                nodeLabel(it).ifBlank { descendantLabels(it).firstOrNull().orEmpty() }
            }.orEmpty().take(160)
            overlayStatus?.text = "SEMI · click captured · validating video…"
        }

        if (playbackPage && t0 == 0L) {
            // Normally the click event arrives first. If YouTube suppresses TYPE_VIEW_CLICKED,
            // allow a creator/list -> playback transition as a fallback. Do not repeatedly create
            // attempts from multiple events while we are already sitting on the same watch page.
            val pendingAge = if (semiPendingClickMs > 0L) now - semiPendingClickMs else Long.MAX_VALUE
            val hasFreshClick = pendingAge in 0..10_000L && semiPendingClickElapsedMs > 0L
            val freshPageTransition = !wasPlaybackPage
            if (!hasFreshClick && !freshPageTransition) return
            seq++
            currentTitle = semiPendingTitle.ifBlank { "Manual media $seq" }
            if (hasFreshClick) {
                t0 = semiPendingClickMs
                semiT0ElapsedMs = semiPendingClickElapsedMs
                semiT0Source = "ACCESSIBILITY_CLICK"
                overlayStatus?.text = "SEMI · #$seq timing · T0 CLICK · tap LOADED when ready"
            } else {
                // Keep the session usable, but mark fallback timing LOW confidence. It will not be
                // included in normal PASS delay statistics.
                t0 = now
                semiT0ElapsedMs = nowElapsed
                semiT0Source = "PAGE_FALLBACK_LOW"
                overlayStatus?.text = "SEMI · #$seq LOW accuracy T0 · tap LOADED when ready"
            }
            semiSawPlayback = true
            semiPendingClickMs = 0L
            semiPendingClickElapsedMs = 0L
            semiPendingTitle = ""
        }
    }
    override fun onInterrupt() {}

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 14)
            setBackgroundColor(0xE6202124.toInt())
        }
        val header = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            text = "YouTube Video Test  ·  drag here"
            setPadding(8, 8, 8, 12)
        }
        val status = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            text = "Ready · open creator Videos page"
            setPadding(8, 0, 8, 8)
        }
        overlayStatus = status
        val clock = TextView(this).apply {
            setTextColor(0xffffffff.toInt())
            text = "TIME --:--:--.---"
            setPadding(8, 0, 8, 8)
        }
        val rowTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rowBottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "START" }
        val loaded = Button(this).apply { text = "LOADED"; visibility = View.GONE }
        val ad = Button(this).apply { text = "AD / SKIP"; visibility = View.GONE }
        val stop = Button(this).apply { text = "STOP"; visibility = View.GONE }
        val weighted = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        rowTop.addView(start, weighted)
        rowTop.addView(loaded, weighted)
        rowBottom.addView(ad, weighted)
        rowBottom.addView(stop, weighted)
        box.addView(header); box.addView(status); box.addView(clock); box.addView(rowTop); box.addView(rowBottom)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 180 }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + e.rawX - downX).toInt().coerceAtLeast(0)
                    lp.y = (startY + e.rawY - downY).toInt().coerceAtLeast(0)
                    runCatching { wm.updateViewLayout(box, lp) }
                    true
                }
                else -> true
            }
        }

        start.setOnClickListener {
            if (running) {
                if (config.semiAuto) {
                    status.text = if (t0 > 0L) "SEMI · #$seq timing… tap LOADED" else "SEMI AUTO · tap the next YouTube video manually"
                } else if (t0 > 0L) {
                    status.text = "Video $seq/${config.count} is already loading…"
                } else {
                    status.text = "RETRYING…"
                    scope.launch { next(status) }
                }
            } else {
                status.text = "STARTING…"
                val accepted = startTest(status)
                if (accepted) {
                    start.text = "RETRY"
                    loaded.visibility = View.VISIBLE
                    ad.visibility = View.VISIBLE
                    stop.visibility = View.VISIBLE
                }
            }
        }
        loaded.setOnClickListener {
            if (running && config.semiAuto && t0 == 0L && semiLastGestureElapsedMs > 0L) {
                // Final semi-auto safety net: if YouTube actually opened the video but its page
                // structure never became detectable, the tester's LOADED press is authoritative.
                // Use the most recent retained content-area ACTION_DOWN as T0 instead of losing the sample.
                val age = SystemClock.elapsedRealtime() - semiLastGestureElapsedMs
                if (age in 0..30_000L) {
                    seq++
                    currentTitle = semiLastGestureTitle.ifBlank { "Manual media $seq" }
                    t0 = semiLastGestureWallMs
                    semiT0ElapsedMs = semiLastGestureElapsedMs
                    semiT0Source = "OVERLAY_CONFIRMED_BY_LOADED"
                    semiSawPlayback = true
                    semiLastGestureWallMs = 0L
                    semiLastGestureElapsedMs = 0L
                    semiLastGestureTitle = ""
                    completeSemiAttempt("PASS", "MANUAL_BUTTON", status, performBack = true)
                } else {
                    status.text = "SEMI · no recent video tap · tap video first"
                }
            } else if (running && t0 > 0) {
                if (config.semiAuto) completeSemiAttempt("PASS", "MANUAL_BUTTON", status, performBack = true)
                else completeAttempt("PASS", "MANUAL", status)
            } else status.text = if (config.semiAuto) "SEMI · tap a YouTube video first" else "Nothing is loading · LOADED ignored"
        }
        ad.setOnClickListener {
            if (running && t0 > 0) {
                if (config.semiAuto) completeSemiAttempt("AD", "MANUAL_AD", status, performBack = true)
                else completeAttempt("AD", "MANUAL_AD", status)
            } else status.text = "No active video · AD ignored"
        }
        stop.setOnClickListener {
            // Give immediate UI feedback before repository/export work starts so STOP never feels
            // unresponsive on slower devices.
            status.text = "STOPPING… saving current results"
            start.isEnabled = false
            loaded.isEnabled = false
            ad.isEnabled = false
            stop.isEnabled = false
            stop.text = "STOPPING…"
            stopTest("Stopped", status)
            scope.launch {
                delay(2000)
                dismissOverlay()
            }
        }
        wm.addView(box, lp)
        overlay = box
        overlayLp = lp
        clockJob?.cancel()
        clockJob = scope.launch {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            while (isActive && overlay === box) {
                clock.text = "TIME " + fmt.format(java.util.Date())
                delay(20)
            }
        }
    }

    private fun startTest(status: TextView): Boolean {
        if (rootInActiveWindow?.packageName?.toString() != "com.google.android.youtube") {
            status.text = "START failed · open YouTube creator Videos page"
            return false
        }
        config = repo.loadConfig()
        file = repo.create(System.currentTimeMillis())
        running = true; seq = 0
        testedContentKeys.clear()
        autoScrollCount = 0
        semiPendingClickMs = 0L
        semiPendingClickElapsedMs = 0L
        semiT0ElapsedMs = 0L
        semiT0Source = ""
        semiPendingTitle = ""
        semiLastGestureWallMs = 0L
        semiLastGestureElapsedMs = 0L
        semiLastGestureTitle = ""
        semiReplayingScroll = false
        semiScrollGuardUntilElapsedMs = 0L
        semiIgnorePlaybackUntilList = false
        semiLastPlaybackPage = looksLikePlaybackPage(rootInActiveWindow)
        sessionMode = detectCreatorMode(rootInActiveWindow)
        val initialBounds = creatorContentBounds(rootInActiveWindow) ?: safeContentBounds()
        if (!config.semiAuto && sessionMode == null) {
            sessionMode = inferModeFromVisibleGeometry(rootInActiveWindow, initialBounds)
        }
        if (!config.semiAuto && sessionMode == null) {
            running = false
            file?.delete()
            file = null
            status.text = "START failed · open creator Videos/Shorts list or use SEMI AUTO"
            return false
        }
        lockedContentTop = initialBounds.top
        lockedContentBottom = initialBounds.bottom
        if (config.autoRecord && !RecordingState.status.value.isRecording) {
            val sub = SubscriptionManager.getDefaultDataSubscriptionId()
            ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java)
                .putExtra(RecordingService.EXTRA_SUBSCRIPTION_ID, sub)
                .putExtra(RecordingService.EXTRA_TASK_NAME, "YouTube_Video_Loading"))
            recordingStarted = true
        }
        if (config.semiAuto) {
            status.text = "SEMI AUTO · tap a video · touch T0 armed"
            scope.launch { delay(250); installSemiTouchCapture() }
        } else {
            status.text = "Started · locating Video #1…"
            scope.launch { delay(500); next(status) }
        }
        return true
    }

    private suspend fun next(status: TextView) {
        if (!running) return
        if (seq >= config.count) { stopTest("Completed", status); return }
        if (rootInActiveWindow?.packageName?.toString() != "com.google.android.youtube") {
            status.text = "Paused · waiting for YouTube creator page"
            return
        }
        delay(config.returnWaitMs)

        // Never select by attempt index. After Back, YouTube may restore/reorder the viewport,
        // so an index-based lookup can hit the same cached item again. Always choose the first
        // visible content whose stable content key has not been tested in this session.
        repeat(5) { scrollRound ->
            status.text = if (scrollRound == 0) {
                "Locating new item ${seq + 1}/${config.count}…"
            } else {
                "Finding new content · scroll $scrollRound/4…"
            }

            val root = rootInActiveWindow
            // The creator header/tab row scrolls off-screen.  After START the mode and safe
            // content viewport are locked for the whole session; never require the header again.
            val content = ContentBounds(lockedContentTop, lockedContentBottom)
            val candidates = mediaCandidates(root, content, sessionMode ?: return)
            val candidate = candidates.firstOrNull { it.key !in testedContentKeys }
            if (candidate != null) {
                currentTitle = candidate.title.take(160)
                testedContentKeys += candidate.key
                seq++
                t0 = System.currentTimeMillis()
                status.text = "Item $seq/${config.count} · CLICKING…"
                val clicked = clickNode(candidate.node)
                if (!clicked) {
                    t0 = 0L
                    seq--
                    testedContentKeys.remove(candidate.key)
                    status.text = "Click failed · tap RETRY"
                    return
                }
                status.text = "Video $seq/${config.count} · AUTO DETECTING…"
                scope.launch {
                    val thisSeq = seq
                    delay(150)
                    var readyHits = 0
                    while (running && seq == thisSeq && System.currentTimeMillis() - t0 < config.timeoutMs) {
                        delay(120)
                        val elapsed = System.currentTimeMillis() - t0
                        // Do not count navigation/UI chrome as loaded.  Require a real watch/Shorts
                        // player signature and evidence that playback has started.  Three consecutive
                        // hits suppress transition-animation false positives.
                        readyHits = if (elapsed >= 250 && isPlaybackActuallyStarted(rootInActiveWindow, sessionMode)) readyHits + 1 else 0
                        if (readyHits >= 2) {
                            completeAttempt("PASS", "AUTO", status)
                            return@launch
                        }
                    }
                    if (running && seq == thisSeq && t0 > 0) completeAttempt("TIMEOUT", "AUTO", status)
                }
                return
            }

            // All currently visible cards have already been tested. Scroll the creator grid/list
            // to expose new content, wait for YouTube to settle, then scan again.
            if (scrollRound < 4) {
                status.text = "Visible items already tested · scrolling for new content…"
                if (!scrollCreatorContent(content)) {
                    status.text = "No new media item found · unable to scroll · tap RETRY"
                    return
                }
                autoScrollCount++
                delay(1200)
            }
        }
        status.text = "No new media item found after scrolling · tap RETRY"
    }

    private fun completeAttempt(result: String, detection: String, status: TextView) {
        val start = t0
        if (start <= 0) return
        t0 = 0
        scope.launch {
            val now = System.currentTimeMillis()
            val snap = withContext(Dispatchers.IO) { snapshot() }
            repo.append(file!!, VideoLoadingSample(seq, currentTitle, start, if (result == "PASS") now else 0,
                if (result == "PASS") now - start else null, result, detection, snap))
            status.text = when (result) {
                "PASS" -> "Video $seq · ${now-start} ms · $detection · RETURNING…"
                "AD" -> "Video $seq · AD skipped · not counted as success · RETURNING…"
                else -> "Video $seq · TIMEOUT · RETURNING…"
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(config.returnWaitMs)
            next(status)
        }
    }

    private fun completeSemiAttempt(result: String, detection: String, status: TextView?, performBack: Boolean) {
        val startWall = t0
        val startElapsed = semiT0ElapsedMs
        val source = semiT0Source
        if (startWall <= 0L || startElapsed <= 0L || file == null) return
        t0 = 0L
        semiT0ElapsedMs = 0L
        semiT0Source = ""
        semiSawPlayback = false
        semiPendingClickMs = 0L
        semiPendingClickElapsedMs = 0L
        semiPendingTitle = ""
        if (performBack) semiIgnorePlaybackUntilList = true
        scope.launch {
            val loadedWall = System.currentTimeMillis()
            val loadedElapsed = SystemClock.elapsedRealtime()
            val delay = (loadedElapsed - startElapsed).coerceAtLeast(0L)
            val accurate = source == "ACCESSIBILITY_CLICK" || source == "OVERLAY_TOUCH_HIGH" || source == "OVERLAY_RECOVERED_TAP" || source == "OVERLAY_CONFIRMED_BY_LOADED"
            val storedResult = when {
                result == "AD" -> "AD"
                accurate -> "PASS"
                else -> "LOW_ACCURACY"
            }
            val snap = withContext(Dispatchers.IO) { snapshot() }
            repo.append(file!!, VideoLoadingSample(
                sequence = seq,
                title = currentTitle,
                startMs = startWall,
                loadedMs = loadedWall,
                delayMs = delay,
                result = storedResult,
                detection = detection,
                snapshot = snap,
                startElapsedMs = startElapsed,
                loadedElapsedMs = loadedElapsed,
                t0Source = source
            ))
            status?.text = when (storedResult) {
                "PASS" -> "SEMI · #$seq ${delay} ms · saved · tap next video"
                "AD" -> "SEMI · #$seq AD · excluded · tap next video"
                else -> "SEMI · #$seq ${delay} ms · LOW accuracy · excluded"
            }
            if (performBack) {
                status?.text = "SEMI · #$seq ${delay} ms · saved · RETURNING…"
                removeSemiTouchCapture()
                // Exactly ONE Back. A timed second Back is unsafe: YouTube may still expose the old
                // watch-page tree while the first Back is already navigating, so the second Back can
                // jump past the creator video list to Home. The returning guard blocks all events
                // until a stable non-playback page is observed.
                performGlobalAction(GLOBAL_ACTION_BACK)

                var stableNonPlayback = 0
                var waitedMs = 0L
                while (waitedMs < 10_000L && stableNonPlayback < 3 && running) {
                    delay(180)
                    waitedMs += 180L
                    if (!looksLikePlaybackPage(rootInActiveWindow)) stableNonPlayback++ else stableNonPlayback = 0
                }

                if (stableNonPlayback >= 3 && running) {
                    delay(350)
                    semiIgnorePlaybackUntilList = false
                    semiPendingClickMs = 0L
                    semiPendingClickElapsedMs = 0L
                    semiPendingTitle = ""
                    semiLastGestureWallMs = 0L
                    semiLastGestureElapsedMs = 0L
                    semiLastGestureTitle = ""
                    semiLastPlaybackPage = looksLikePlaybackPage(rootInActiveWindow)
                    status?.text = "SEMI · ready · tap the next YouTube video"
                    installSemiTouchCapture()
                } else if (running) {
                    // Do not issue a second automatic Back. Let the tester press Android Back once;
                    // keep the guard active so that manual return can never become a new attempt.
                    status?.text = "SEMI · return not confirmed · press Android Back once"
                    scope.launch {
                        var manualStable = 0
                        repeat(80) {
                            delay(200)
                            if (!running || !semiIgnorePlaybackUntilList) return@launch
                            if (!looksLikePlaybackPage(rootInActiveWindow)) manualStable++ else manualStable = 0
                            if (manualStable >= 3) {
                                semiIgnorePlaybackUntilList = false
                                semiPendingClickMs = 0L
                                semiPendingClickElapsedMs = 0L
                                semiPendingTitle = ""
                                semiLastGestureWallMs = 0L
                                semiLastGestureElapsedMs = 0L
                                semiLastGestureTitle = ""
                                semiLastPlaybackPage = looksLikePlaybackPage(rootInActiveWindow)
                                status?.text = "SEMI · ready · tap the next YouTube video"
                                installSemiTouchCapture()
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopTest(state: String, status: TextView) {
        // STOP must be idempotent and must always provide visible feedback.  Do not let a report
        // finalization or RecordingService failure make the overlay look as if the button did
        // nothing.
        if (!running && file == null) {
            status.text = "YouTube Test · already stopped"
            return
        }
        running = false
        t0 = 0L
        semiSawPlayback = false
        semiPendingClickMs = 0L
        semiPendingClickElapsedMs = 0L
        semiT0ElapsedMs = 0L
        semiT0Source = ""
        semiPendingTitle = ""
        semiIgnorePlaybackUntilList = false
        semiLastPlaybackPage = false
        removeSemiTouchCapture()

        val f = file
        file = null
        runCatching {
            if (f != null) {
                repo.finish(
                    f,
                    0,
                    System.currentTimeMillis(),
                    state,
                    RecordingState.status.value.latestPath
                )
            }
        }
        runCatching {
            if (recordingStarted) {
                stopService(Intent(this, RecordingService::class.java))
                recordingStarted = false
            }
        }
        runCatching { repo.disarm() }
        status.text = "YouTube Test · $state · results saved"
    }

    private fun dismissOverlay() {
        removeSemiTouchCapture()
        clockJob?.cancel()
        clockJob = null
        val view = overlay ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        if (overlay === view) overlay = null
        overlayStatus = null
    }

    private fun installSemiTouchCapture() {
        if (!running || !config.semiAuto || t0 > 0L || semiCaptureOverlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val main = overlay ?: return
        val mainLp = overlayLp ?: return
        val capture = View(this).apply { setBackgroundColor(0x01000000) }
        val cp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val density = resources.displayMetrics.density
        // Semi-auto gesture classifier: allow normal finger jitter without mistaking a tap for a scroll.
        // Real scrolling is detected from the maximum displacement during the whole gesture, not only UP-DOWN.
        // YouTube often interprets a short 30-60dp finger drift as a tap. Be conservative about
        // calling something a scroll: only a clearly intentional movement is replayed as scrolling.
        val tapSlopPx = 96f * density
        val longPressMs = 650L
        var downX = 0f
        var downY = 0f
        var downElapsed = 0L
        var multiTouch = false
        var maxDistanceFromDown = 0f
        val points = ArrayList<Pair<Float, Float>>()

        capture.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    downElapsed = SystemClock.elapsedRealtime()
                    // Do not treat ACTION_DOWN as a recoverable media tap yet. A real scroll must
                    // never become T0 later just because YouTube eventually navigates. We only
                    // create a recovery candidate after ACTION_UP when the gesture itself looks tap-like.
                    semiLastGestureElapsedMs = 0L
                    semiLastGestureWallMs = 0L
                    semiLastGestureTitle = ""
                    multiTouch = false
                    maxDistanceFromDown = 0f
                    points.clear()
                    points.add(e.rawX to e.rawY)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mdx = e.rawX - downX
                    val mdy = e.rawY - downY
                    maxDistanceFromDown = maxOf(maxDistanceFromDown, kotlin.math.sqrt(mdx * mdx + mdy * mdy))
                    if (points.isEmpty() || kotlin.math.abs(points.last().first - e.rawX) > 3f || kotlin.math.abs(points.last().second - e.rawY) > 3f) {
                        points.add(e.rawX to e.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    points.clear()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    points.add(e.rawX to e.rawY)
                    val upElapsed = SystemClock.elapsedRealtime()
                    val duration = (upElapsed - downElapsed).coerceAtLeast(1L)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                    maxDistanceFromDown = maxOf(maxDistanceFromDown, distance)
                    val inMediaArea = isSemiMediaTapArea(e.rawX, e.rawY)
                    val clickTargetAtDown = if (!multiTouch && isSemiMediaTapArea(downX, downY)) {
                        findClickableNodeAt(rootInActiveWindow, downX.toInt(), downY.toInt())
                    } else null
                    val hardScrollPx = 165f * density
                    val netDistance = kotlin.math.sqrt(dx * dx + dy * dy)
                    val tapLikeWithClickableTarget = clickTargetAtDown != null &&
                        maxDistanceFromDown <= hardScrollPx && netDistance <= hardScrollPx && duration < 520L
                    val isTap = !multiTouch && duration < longPressMs &&
                        (maxDistanceFromDown <= tapSlopPx || tapLikeWithClickableTarget)

                    if (isTap && inMediaArea && !isPointInsideMiniPlayer(rootInActiveWindow, downX.toInt(), downY.toInt())) {
                        // T0 is the user's real tap-up time.  Remove the capture layer, then replay
                        // the same tap into YouTube.  No Accessibility click event is required.
                        // Use ACTION_DOWN as the user's real click instant. Classification waits until ACTION_UP,
                        // but T0 must not be shifted later by the classification/replay work.
                        val wall = System.currentTimeMillis() - duration
                        val elapsed = downElapsed
                        seq++
                        currentTitle = "Manual media $seq"
                        t0 = wall
                        semiT0ElapsedMs = elapsed
                        semiT0Source = "OVERLAY_TOUCH_HIGH"
                        semiSawPlayback = false
                        // This gesture is now committed as the active attempt. Do not leave it in
                        // the recovery cache, otherwise the Back transition after LOADED can reuse
                        // the stale touch and create a duplicate attempt.
                        semiLastGestureWallMs = 0L
                        semiLastGestureElapsedMs = 0L
                        semiLastGestureTitle = ""
                        overlayStatus?.text = "SEMI · #$seq T0 TOUCH · tap LOADED at first frame"
                        // First try Accessibility ACTION_CLICK on the actual YouTube node under
                        // the user's finger. This is much more reliable than dispatchGesture for the
                        // first tap after an accessibility overlay is removed. Gesture replay remains
                        // as the fallback for thumbnails that do not expose a clickable node.
                        val clickTarget = clickTargetAtDown ?: findClickableNodeAt(rootInActiveWindow, e.rawX.toInt(), e.rawY.toInt())
                        removeSemiTouchCapture()
                        scope.launch {
                            delay(90)
                            val nodeClicked = runCatching { clickTarget?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true }.getOrDefault(false)
                            if (!nodeClicked) {
                                delay(90)
                                if (!dispatchTap(e.rawX, e.rawY)) {
                                    delay(180)
                                    if (!dispatchTap(e.rawX, e.rawY)) {
                                        // Do not leave a phantom attempt when Android rejected every replay method.
                                        t0 = 0L
                                        semiT0ElapsedMs = 0L
                                        semiT0Source = ""
                                        seq = (seq - 1).coerceAtLeast(0)
                                        overlayStatus?.text = "SEMI · tap delivery failed · tap video again"
                                        installSemiTouchCapture()
                                    }
                                }
                            }
                        }
                    } else if (isTap && inMediaArea && isPointInsideMiniPlayer(rootInActiveWindow, downX.toInt(), downY.toInt())) {
                        // A persistent YouTube mini-player is not a new test item. Replay the tap so
                        // the UI still behaves naturally, but never arm T0 / increment Attempt.
                        removeSemiTouchCapture()
                        overlayStatus?.text = "SEMI · mini-player tap ignored · no Attempt"
                        scope.launch {
                            delay(70)
                            dispatchTap(e.rawX, e.rawY)
                            delay(180)
                            if (running && config.semiAuto && t0 == 0L && !looksLikePlaybackPage(rootInActiveWindow)) {
                                installSemiTouchCapture()
                                overlayStatus?.text = "SEMI · ready · tap the next YouTube video"
                            }
                        }
                    } else {
                        // Scroll/refresh/long-press/navigation taps must never start an attempt.
                        // Replay the user's gesture to YouTube and re-arm capture afterwards.
                        removeSemiTouchCapture()
                        if (multiTouch) {
                            overlayStatus?.text = "SEMI · multi-touch ignored · ready"
                            scope.launch { delay(120); installSemiTouchCapture() }
                        } else {
                            val why = when {
                                maxDistanceFromDown > tapSlopPx -> "scroll"
                                duration >= longPressMs -> "long press"
                                !inMediaArea -> "navigation tap"
                                else -> "gesture"
                            }
                            // A confirmed scroll/long-press/navigation gesture is never allowed to
                            // become T0 later. In v0.9.3.0.6 the old scroll ACTION_DOWN could be
                            // recovered several seconds later when a video page appeared, making T0
                            // earlier than the user's real tap. Clear it unconditionally here.
                            semiLastGestureWallMs = 0L
                            semiLastGestureElapsedMs = 0L
                            semiLastGestureTitle = ""
                            semiPendingClickMs = 0L
                            semiPendingClickElapsedMs = 0L
                            semiPendingTitle = ""
                            overlayStatus?.text = "SEMI · $why · no T0"
                            if (why == "scroll") {
                                semiReplayingScroll = true
                                semiScrollGuardUntilElapsedMs = SystemClock.elapsedRealtime() + duration + 700L
                                replaySafeVerticalScroll(points.toList(), duration) {
                                    semiReplayingScroll = false
                                    // Keep only a very short settle guard, and do not place the touch layer
                                    // back on top until that guard has expired. This removes the overlap where
                                    // a genuine post-scroll tap could be interpreted as part of the scroll.
                                    semiScrollGuardUntilElapsedMs = SystemClock.elapsedRealtime() + 90L
                                    scope.launch {
                                        delay(110)
                                        if (running && config.semiAuto && t0 == 0L && !looksLikePlaybackPage(rootInActiveWindow)) {
                                            installSemiTouchCapture()
                                            overlayStatus?.text = "SEMI · ready · tap the next YouTube video"
                                        }
                                    }
                                }
                            } else {
                                replayGesture(points.toList(), duration) {
                                    if (running && config.semiAuto && t0 == 0L && !looksLikePlaybackPage(rootInActiveWindow)) {
                                        installSemiTouchCapture()
                                        overlayStatus?.text = "SEMI · ready · tap the next YouTube video"
                                    }
                                }
                            }
                        }
                    }
                    true
                }
                else -> true
            }
        }

        // Keep the control window above the transparent capture layer.
        runCatching { wm.removeView(main) }
        runCatching { wm.addView(capture, cp) }
            .onFailure {
                runCatching { wm.addView(main, mainLp) }
                return
            }
        semiCaptureOverlay = capture
        runCatching { wm.addView(main, mainLp) }
    }

    private fun isSemiMediaTapArea(x: Float, y: Float): Boolean {
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels
        if (x < 0f || x > w.toFloat() || y < 0f || y > h.toFloat()) return false

        // Prefer the creator content bounds captured at START.  Fall back to a conservative
        // YouTube content window that excludes the top app bar and bottom navigation.
        val fallback = safeContentBounds()
        val top = (lockedContentTop.takeIf { it > 0 } ?: fallback.top).coerceAtLeast((72 * resources.displayMetrics.density).toInt())
        val bottom = (lockedContentBottom.takeIf { it > top } ?: fallback.bottom)
            .coerceAtMost(h - (72 * resources.displayMetrics.density).toInt())
        return y.toInt() in top..bottom
    }

    private fun isPointInsideMiniPlayer(root: AccessibilityNodeInfo?, x: Int, y: Int): Boolean {
        if (root == null) return false
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        var hit = false
        fun walk(n: AccessibilityNodeInfo) {
            if (hit) return
            val r = Rect(); n.getBoundsInScreen(r)
            if (!r.isEmpty && r.contains(x, y)) {
                val labels = (nodeLabel(n) + " | " + descendantLabels(n).joinToString(" | ")).lowercase()
                val explicitMini = labels.contains("miniplayer") || labels.contains("mini player") ||
                    labels.contains("close player") || labels.contains("expand player") ||
                    labels.contains("迷你播放器") || labels.contains("关闭播放器") || labels.contains("展开播放器")
                // Some YouTube builds expose the mini-player only as a compact lower-right player
                // containing Pause/Play + Close. Geometry is used only together with player controls,
                // so ordinary creator thumbnails are not filtered out.
                val playerControls = (labels.contains("pause") || labels.contains("play") || labels.contains("暂停") || labels.contains("播放")) &&
                    (labels.contains("close") || labels.contains("关闭"))
                val compactLowerPlayer = r.centerY() > (screenH * 0.62f).toInt() &&
                    r.width() < (screenW * 0.72f).toInt() && r.height() < (screenH * 0.38f).toInt()
                if (explicitMini || (playerControls && compactLowerPlayer)) { hit = true; return }
                for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
            }
        }
        walk(root)
        return hit
    }

    private fun isMiniPlayerNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val labels = descendantLabels(node).joinToString(" | ").lowercase()
        if (labels.contains("miniplayer") || labels.contains("mini player") ||
            labels.contains("close player") || labels.contains("expand player") ||
            labels.contains("迷你播放器") || labels.contains("关闭播放器") || labels.contains("展开播放器")) return true
        // Also inspect a few ancestors: on some YouTube versions the touched child is unlabeled
        // while the mini-player container carries the accessibility description.
        var p = node.parent
        repeat(3) {
            val a = p ?: return@repeat
            val t = (nodeLabel(a) + " | " + descendantLabels(a).joinToString(" | ")).lowercase()
            if (t.contains("miniplayer") || t.contains("mini player") || t.contains("close player") ||
                t.contains("expand player") || t.contains("迷你播放器") || t.contains("关闭播放器")) return true
            p = a.parent
        }
        return false
    }

    /**
     * Replay a confirmed vertical scroll on a safe central X instead of the user's exact path.
     * Exact replay can graze YouTube's persistent mini-player and turn a swipe into a click.
     * We preserve direction/distance while keeping the gesture in the creator content area.
     */
    private fun replaySafeVerticalScroll(points: List<Pair<Float, Float>>, durationMs: Long, onDone: () -> Unit) {
        if (points.size < 2) { onDone(); return }
        val dm = resources.displayMetrics
        val fallback = safeContentBounds()
        val top = (lockedContentTop.takeIf { it > 0 } ?: fallback.top) + (28f * dm.density).toInt()
        val bottom = (lockedContentBottom.takeIf { it > top } ?: fallback.bottom) - (96f * dm.density).toInt()
        if (bottom <= top) { replayGesture(points, durationMs, onDone); return }
        val deltaY = points.last().second - points.first().second
        val minSwipe = 90f * dm.density
        if (kotlin.math.abs(deltaY) < minSwipe) { onDone(); return }
        val x = dm.widthPixels * 0.5f
        val startY = if (deltaY < 0f) bottom.toFloat() else top.toFloat()
        val maxTravel = (bottom - top).toFloat() * 0.78f
        val travel = kotlin.math.min(kotlin.math.abs(deltaY), maxTravel)
        val endY = (startY + if (deltaY < 0f) -travel else travel).coerceIn(top.toFloat(), bottom.toFloat())
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val duration = durationMs.coerceIn(120L, 900L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { scope.launch { delay(70); onDone() } }
            override fun onCancelled(gestureDescription: GestureDescription?) { scope.launch { delay(70); onDone() } }
        }, null)
        if (!accepted) scope.launch { delay(duration + 90); onDone() }
    }

    private fun replayGesture(points: List<Pair<Float, Float>>, durationMs: Long, onDone: () -> Unit) {
        if (points.isEmpty()) {
            onDone()
            return
        }
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val duration = durationMs.coerceIn(40L, 1200L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        val accepted = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                scope.launch { delay(80); onDone() }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                scope.launch { delay(80); onDone() }
            }
        }, null)
        if (!accepted) scope.launch { delay(duration + 80); onDone() }
    }

    private fun removeSemiTouchCapture() {
        val v = semiCaptureOverlay ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        semiCaptureOverlay = null
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun findClickableNodeAt(root: AccessibilityNodeInfo?, x: Int, y: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun walk(n: AccessibilityNodeInfo) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty && r.contains(x, y)) {
                if (n.isClickable && !isMiniPlayerNode(n)) {
                    val area = r.width().toLong() * r.height().toLong()
                    if (r.width() > 80 && r.height() > 48 && area < bestArea) {
                        best = n
                        bestArea = area
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
            }
        }
        walk(root)
        return best
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(4) {
            val n = cur ?: return@repeat
            val r = Rect(); n.getBoundsInScreen(r)
            // Do not climb into a huge page/header container. A real video row/card should
            // remain reasonably bounded inside the content area.
            if (n.isClickable && r.width() > 120 && r.height() in 60..700) return n
            cur = n.parent
        }
        return null
    }

    private data class ContentBounds(val top: Int, val bottom: Int)

    private fun nodeLabel(n: AccessibilityNodeInfo): String =
        ((n.contentDescription ?: n.text) ?: "").toString().trim()

    private fun isVideosLabel(t: String): Boolean {
        val v = t.trim()
        return v.equals("Videos", true) || v == "视频" || v == "影片"
    }

    private fun isCreatorTabLabel(t: String): Boolean {
        val v = t.trim()
        return v.equals("Videos", true) || v == "视频" || v == "影片" ||
            v.equals("Shorts", true) || v == "短视频" ||
            v.equals("Playlists", true) || v.equals("Playlist", true) || v == "播放列表" ||
            v.equals("Live", true) || v == "直播" ||
            v.equals("Podcasts", true) || v.equals("Podcast", true) || v == "播客" ||
            v.equals("Courses", true) || v == "课程" || v.equals("Posts", true) || v == "帖子"
    }

    private fun detectCreatorMode(root: AccessibilityNodeInfo?): CreatorMode? {
        if (root == null) return null
        var videosSelected = false
        var shortsSelected = false
        fun walk(n: AccessibilityNodeInfo) {
            val t = nodeLabel(n)
            if (isVideosLabel(t) && (n.isSelected || n.isFocused)) videosSelected = true
            if ((t.equals("Shorts", true) || t == "短视频") && (n.isSelected || n.isFocused)) shortsSelected = true
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        if (shortsSelected) return CreatorMode.SHORTS
        if (videosSelected) return CreatorMode.VIDEOS
        // YouTube builds often omit selected/focused. Infer from visible card geometry only at START.
        val b = creatorContentBounds(root) ?: return null
        val w = resources.displayMetrics.widthPixels
        var narrow = 0; var wide = 0
        fun geom(n: AccessibilityNodeInfo) {
            if (n.isClickable) {
                val r=Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.top >= b.top && r.bottom <= b.bottom && r.height() >= 140) {
                    if (r.width() in (w*.20f).toInt()..(w*.55f).toInt()) narrow++
                    if (r.width() >= (w*.55f).toInt()) wide++
                }
            }
            for(i in 0 until n.childCount) n.getChild(i)?.let(::geom)
        }
        geom(root)
        return if (narrow >= 2 && narrow > wide) CreatorMode.SHORTS else CreatorMode.VIDEOS
    }

    /**
     * Find the creator content area without depending on YouTube exposing the selected-tab state.
     * Several YouTube builds do not mark Videos/Shorts as AccessibilityNodeInfo.isSelected.
     * We therefore use the creator-tab row only as a safe top boundary, then classify media cards
     * by geometry below it. This supports both normal Videos and Shorts grids.
     */
    private fun creatorContentBounds(root: AccessibilityNodeInfo?): ContentBounds? {
        if (root == null) return null
        val dm = resources.displayMetrics
        val tabRects = mutableListOf<Rect>()
        fun walk(n: AccessibilityNodeInfo) {
            val t = nodeLabel(n)
            val r = Rect(); n.getBoundsInScreen(r)
            if (isCreatorTabLabel(t) && !r.isEmpty &&
                r.top in (dm.heightPixels * 0.20f).toInt()..(dm.heightPixels * 0.82f).toInt()) {
                tabRects += Rect(r)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        if (tabRects.isEmpty()) return null
        val tabBottom = tabRects.maxOf { it.bottom }
        val top = (tabBottom + 8).coerceAtLeast((dm.heightPixels * 0.22f).toInt())
        val bottom = (dm.heightPixels * 0.91f).toInt()
        return if (top < bottom) ContentBounds(top, bottom) else null
    }

    private fun safeContentBounds(): ContentBounds {
        val h = resources.displayMetrics.heightPixels
        return ContentBounds((h * 0.16f).toInt(), (h * 0.91f).toInt())
    }

    private fun inferModeFromVisibleGeometry(root: AccessibilityNodeInfo?, bounds: ContentBounds): CreatorMode? {
        if (root == null) return null
        val w = resources.displayMetrics.widthPixels
        var narrow = 0
        var wide = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isClickable) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.top >= bounds.top && r.bottom <= bounds.bottom && r.height() >= 100) {
                    if (r.width() in (w * 0.20f).toInt()..(w * 0.55f).toInt()) narrow++
                    if (r.width() >= (w * 0.55f).toInt()) wide++
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return when {
            narrow >= 3 && narrow > wide -> CreatorMode.SHORTS
            wide >= 1 -> CreatorMode.VIDEOS
            else -> null
        }
    }

    private fun looksLikePlaybackPage(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var hit = false
        fun walk(n: AccessibilityNodeInfo) {
            val t = nodeLabel(n).lowercase()
            if (t.contains("comments") || t.contains("share") || t.contains("pause") ||
                t.contains("评论") || t.contains("分享") || t.contains("暂停") ||
                t.contains("fullscreen") || t.contains("全屏")) hit = true
            if (!hit) for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return hit
    }

    private fun looksLikeCreatorPage(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var mediaCount = 0
        val b = safeContentBounds()
        val w = resources.displayMetrics.widthPixels
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isClickable) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (!r.isEmpty && r.top >= b.top && r.bottom <= b.bottom && r.height() >= 100 &&
                    r.width() >= (w * 0.20f).toInt()) mediaCount++
            }
            if (mediaCount < 3) for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return mediaCount >= 3
    }

    private fun isPotentialSemiMediaTrigger(node: AccessibilityNodeInfo): Boolean {
        val r = Rect(); node.getBoundsInScreen(r)
        val dm = resources.displayMetrics
        if (r.isEmpty) return false
        // Creator cards can sit above/below the old fixed content bounds after scrolling.  Only
        // exclude system/top chrome and YouTube bottom navigation here; playback transition will
        // be the final confirmation that this click was really a media item.
        if (r.centerY() < (dm.heightPixels * 0.10f).toInt() ||
            r.centerY() > (dm.heightPixels * 0.93f).toInt()) return false
        val text = (nodeLabel(node) + " " + descendantLabels(node).joinToString(" ")).lowercase()
        val blocked = listOf(
            "subscribe", "subscriptions", "share", "comments", "comment", "like", "search",
            "sort", "more", "home", "library", "you", "settings", "cast", "notifications",
            "订阅", "分享", "评论", "点赞", "搜索", "排序", "更多", "首页", "我的", "设置", "投屏", "通知"
        )
        if (blocked.any { text.contains(it) }) return false
        return true
    }

    private data class MediaCandidate(
        val node: AccessibilityNodeInfo,
        val rect: Rect,
        val key: String,
        val title: String
    )

    private fun descendantLabels(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 5) return
            val t = nodeLabel(n)
            if (t.isNotBlank()) out += t
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(node, 0)
        return out.distinct().take(12)
    }

    /**
     * Separate geometry profiles are used for normal Videos rows and Shorts tiles.  Do not
     * require the thumbnail itself to expose text: on several YouTube builds the clickable card
     * has no label while its title/metadata live in descendants or siblings.
     */
    private fun mediaCandidates(root: AccessibilityNodeInfo?, bounds: ContentBounds, mode: CreatorMode): List<MediaCandidate> {
        if (root == null) return emptyList()
        val screenW = resources.displayMetrics.widthPixels
        val rejected = listOf(
            "subscribe", "subscriptions", "community", "instagram", "more", "sort", "search",
            "home", "library", "you", "channel", "links", "videos", "shorts", "live", "playlist",
            "订阅", "社区", "更多", "排序", "搜索", "首页", "我的", "链接", "频道", "视频", "直播", "播放列表"
        )
        val found = mutableListOf<MediaCandidate>()
        val seenRects = mutableSetOf<String>()

        fun addCandidate(click: AccessibilityNodeInfo) {
            val cr = Rect(); click.getBoundsInScreen(cr)
            if (cr.isEmpty || cr.top < bounds.top || cr.bottom > bounds.bottom) return
            val videoRow = cr.width() >= (screenW * 0.35f).toInt() && cr.height() in 100..760
            val shortTile = cr.width() in (screenW * 0.20f).toInt()..(screenW * 0.55f).toInt() && cr.height() >= 170
            if (mode == CreatorMode.VIDEOS && !videoRow) return
            if (mode == CreatorMode.SHORTS && !shortTile) return
            if (cr.width() > (screenW * 0.96f).toInt() && cr.height() < 120) return

            val labels = descendantLabels(click)
            val joined = labels.joinToString(" | ").trim()
            val lower = joined.lowercase()
            // YouTube mini-player survives Back and is clickable. Never treat it as creator media.
            if (lower.contains("miniplayer") || lower.contains("mini player") ||
                lower.contains("close player") || lower.contains("expand player") ||
                lower.contains("迷你播放器") || lower.contains("关闭播放器")) return
            if (joined.isNotBlank() && rejected.any { bad -> joined.equals(bad, true) }) return
            val rectKey = "${cr.left / 16}:${cr.top / 16}:${cr.right / 16}:${cr.bottom / 16}"
            if (!seenRects.add(rectKey)) return

            // Content identity must not depend on screen Y because the same card moves after a
            // scroll. Prefer YouTube's text/description. Geometry is only a last-resort key.
            val normalized = labels.joinToString("|") { it.lowercase().replace(Regex("\\s+"), " ").trim() }
            val key = if (normalized.isNotBlank()) {
                "txt:${normalized.take(300)}"
            } else {
                "geom:${cr.width() / 20}:${cr.height() / 20}:${cr.left / 20}:scroll$autoScrollCount"
            }
            val title = labels.firstOrNull { it.length >= 3 } ?: "Media ${seq + 1}"
            found += MediaCandidate(click, Rect(cr), key, title)
        }

        fun walk(n: AccessibilityNodeInfo) {
            if (n.isClickable) addCandidate(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)

        return found.sortedWith(compareBy<MediaCandidate> { it.rect.top }.thenBy { it.rect.left }).take(40)
    }

    private fun scrollCreatorContent(bounds: ContentBounds): Boolean {
        val root = rootInActiveWindow
        fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            val r = Rect(); n.getBoundsInScreen(r)
            if (n.isScrollable && r.height() > resources.displayMetrics.heightPixels * 0.35f) return n
            for (i in 0 until n.childCount) {
                val found = findScrollable(n.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val scrollable = findScrollable(root)
        if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) return true

        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val startY = (bounds.bottom - 40).coerceAtMost((dm.heightPixels * 0.86f).toInt()).toFloat()
        val endY = (dm.heightPixels * 0.34f).toFloat()
        if (startY <= endY) return false
        val path = Path().apply { moveTo(x, startY); lineTo(x, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 520))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun isPlaybackActuallyStarted(root: AccessibilityNodeInfo?, mode: CreatorMode?): Boolean {
        if (root == null || root.packageName?.toString() != "com.google.android.youtube") return false
        var hasShortsChrome = false
        var hasWatchChrome = false
        var playbackEvidence = false
        var loading = false
        fun walk(n: AccessibilityNodeInfo) {
            val t = nodeLabel(n).lowercase()
            if (t.contains("loading") || t.contains("buffering") || t.contains("加载") || t.contains("缓冲")) loading = true
            if (t.contains("comments") || t.contains("share") || t.contains("评论") || t.contains("分享")) hasShortsChrome = true
            if (t.contains("full screen") || t.contains("fullscreen") || t.contains("全屏") ||
                t.contains("more videos") || t.contains("更多视频")) hasWatchChrome = true
            // A Pause control is the strongest accessibility evidence that YouTube's player is
            // actively playing. Time/progress descriptions are a fallback used by some builds.
            if (t == "pause" || t.contains("pause video") || t == "暂停" || t.contains("暂停视频") ||
                Regex("\\b\\d{1,2}:\\d{2}\\s*/\\s*\\d{1,2}:\\d{2}\\b").containsMatchIn(t)) {
                playbackEvidence = true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        if (loading || !playbackEvidence) return false
        return when (mode) {
            CreatorMode.SHORTS -> hasShortsChrome
            CreatorMode.VIDEOS -> hasWatchChrome || hasShortsChrome
            null -> false
        }
    }

    private suspend fun snapshot(): PingNetworkSnapshot {
        val sims = runCatching { CellularRepository(this).readAllSims() }.getOrDefault(emptyList())
        val id = SubscriptionManager.getDefaultDataSubscriptionId()
        val s = sims.firstOrNull { it.subscriptionId == id } ?: sims.firstOrNull()
        val c = s?.servingCell
        val l = LocationStore.latest.value
        return PingNetworkSnapshot(
            subscriptionId = s?.subscriptionId ?: -1, simSlot = s?.simSlotIndex ?: -1,
            operator = c?.operator ?: "--", rat = c?.rat ?: "--", displayRat = c?.displayRat ?: "--",
            rsrp = c?.rsrp ?: "--", rsrq = c?.rsrq ?: "--", sinr = c?.sinr ?: "--", rssi = c?.rssi ?: "--",
            band = c?.band ?: "--", pci = c?.pci ?: "--", arfcn = c?.arfcn ?: "--",
            latitude = l.latitude.toDoubleOrNull(), longitude = l.longitude.toDoubleOrNull()
        )
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        clockJob?.cancel()
        clockJob = null
        scope.cancel()
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        super.onDestroy()
    }
}
