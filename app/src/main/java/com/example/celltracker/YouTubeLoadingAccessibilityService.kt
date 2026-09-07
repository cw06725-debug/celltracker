package com.example.celltracker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
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

    override fun onServiceConnected() {
        repo = VideoLoadingRepository(this)
        config = repo.loadConfig()
        if (repo.isArmed()) showOverlay()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
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
        val row = LinearLayout(this)
        val start = Button(this).apply { text = "START" }
        val loaded = Button(this).apply { text = "MANUAL LOADED"; visibility = View.GONE }
        val stop = Button(this).apply { text = "STOP"; visibility = View.GONE }
        row.addView(start); row.addView(loaded); row.addView(stop)
        box.addView(header); box.addView(status); box.addView(row)

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
            status.text = "STARTING…"
            val accepted = startTest(status)
            if (accepted) {
                start.visibility = View.GONE
                loaded.visibility = View.VISIBLE
                stop.visibility = View.VISIBLE
            }
        }
        loaded.setOnClickListener {
            if (running && t0 > 0) completeAttempt("PASS", "MANUAL", status)
            else status.text = "Nothing is loading · MANUAL LOADED ignored"
        }
        stop.setOnClickListener { stopTest("Stopped", status) }
        wm.addView(box, lp)
        overlay = box
    }

    private fun startTest(status: TextView): Boolean {
        if (rootInActiveWindow?.packageName?.toString() != "com.google.android.youtube") {
            status.text = "START failed · open YouTube creator Videos page"
            return false
        }
        config = repo.loadConfig()
        file = repo.create(System.currentTimeMillis())
        running = true; seq = 0
        if (config.autoRecord && !RecordingState.status.value.isRecording) {
            val sub = SubscriptionManager.getDefaultDataSubscriptionId()
            ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java)
                .putExtra(RecordingService.EXTRA_SUBSCRIPTION_ID, sub)
                .putExtra(RecordingService.EXTRA_TASK_NAME, "YouTube_Video_Loading"))
            recordingStarted = true
        }
        status.text = "Started · locating Video #1…"
        scope.launch { delay(500); next(status) }
        return true
    }

    private suspend fun next(status: TextView) {
        if (!running) return
        if (seq >= config.count) { stopTest("Completed", status); return }
        if (rootInActiveWindow?.packageName?.toString() != "com.google.android.youtube") {
            status.text = "Paused · waiting for YouTube Videos page"
            return
        }
        delay(config.returnWaitMs)
        status.text = "Locating Video ${seq + 1}/${config.count}…"
        val candidates = videoCandidates(rootInActiveWindow)
        if (candidates.isEmpty()) {
            status.text = "Cannot find video item · return to creator Videos tab"
            return
        }
        val raw = candidates.getOrNull(seq.coerceAtMost(candidates.lastIndex)) ?: candidates.last()
        val node = clickableNode(raw) ?: raw
        currentTitle = (raw.contentDescription ?: raw.text ?: "Video ${seq + 1}").toString().take(160)
        seq++
        t0 = System.currentTimeMillis()
        status.text = "Video $seq/${config.count} · CLICKING…"
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            t0 = 0L; seq--
            status.text = "Click failed · reposition list and tap START again"
            running = false
            return
        }
        status.text = "Video $seq/${config.count} · AUTO DETECTING…"
        scope.launch {
            val thisSeq = seq
            delay(900)
            var readyHits = 0
            while (running && seq == thisSeq && System.currentTimeMillis() - t0 < config.timeoutMs) {
                delay(300)
                readyHits = if (isVideoPageReady(rootInActiveWindow)) readyHits + 1 else 0
                if (readyHits >= 3) {
                    completeAttempt("PASS", "AUTO", status)
                    return@launch
                }
            }
            if (running && seq == thisSeq && t0 > 0) completeAttempt("TIMEOUT", "AUTO", status)
        }
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
            status.text = if (result == "PASS") "Video $seq · ${now-start} ms · $detection · RETURNING…" else "Video $seq · TIMEOUT · RETURNING…"
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(config.returnWaitMs)
            next(status)
        }
    }

    private fun stopTest(state: String, status: TextView) {
        if (!running && file == null) return
        running = false; t0 = 0
        val f = file; file = null
        if (f != null) repo.finish(f, 0, System.currentTimeMillis(), state, RecordingState.status.value.latestPath)
        if (recordingStarted) { stopService(Intent(this, RecordingService::class.java)); recordingStarted = false }
        repo.disarm()
        status.text = "YouTube Test · $state · open CellTracker for results"
    }

    private fun clickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(5) { if (cur?.isClickable == true) return cur; cur = cur?.parent }
        return null
    }

    private fun videoCandidates(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            val text = ((n.contentDescription ?: n.text) ?: "").toString()
            val r = Rect(); n.getBoundsInScreen(r)
            val looksLikeVideo = text.isNotBlank() && r.width() > 180 && r.height() > 60 &&
                !text.contains("Home", true) && !text.contains("Shorts", true) && !text.contains("Subscriptions", true)
            if (looksLikeVideo && (n.isClickable || clickableNode(n) != null)) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return out.distinctBy { (it.contentDescription ?: it.text ?: "").toString() }.take(30)
    }

    private fun isVideoPageReady(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || root.packageName?.toString() != "com.google.android.youtube") return false
        var meaningful = 0; var actions = 0; var loading = false
        fun walk(n: AccessibilityNodeInfo) {
            val t = ((n.contentDescription ?: n.text) ?: "").toString()
            if (t.contains("loading", true) || t.contains("buffering", true)) loading = true
            if (t.length > 3) meaningful++
            if (t.contains("like", true) || t.contains("share", true) || t.contains("comments", true) || t.contains("subscribe", true)) actions++
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return !loading && meaningful >= 5 && actions >= 1
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
        scope.cancel()
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        super.onDestroy()
    }
}
