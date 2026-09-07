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

    override fun onServiceConnected() {
        activeInstance = this
        repo = VideoLoadingRepository(this)
        config = repo.loadConfig()
        if (repo.isArmed()) showOverlay()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Fallback: if the test was armed after the accessibility service connected,
        // make sure the overlay appears as soon as YouTube generates an event.
        if (::repo.isInitialized && repo.isArmed() && overlay == null) showOverlay()
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
        val root = rootInActiveWindow
        if (creatorVideosBounds(root) == null) {
            status.text = "Please switch to creator Videos tab · no click performed"
            return
        }
        val candidates = videoCandidates(root)
        if (candidates.isEmpty()) {
            status.text = "No safe video item found · no click performed"
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

    private fun isWrongCreatorTab(t: String): Boolean {
        val v = t.trim()
        return v.equals("Shorts", true) || v == "短视频" ||
            v.equals("Playlists", true) || v.equals("Playlist", true) || v == "播放列表" ||
            v.equals("Live", true) || v == "直播" ||
            v.equals("Podcasts", true) || v.equals("Podcast", true) || v == "播客" ||
            v.equals("Courses", true) || v == "课程" || v.equals("Posts", true) || v == "帖子"
    }

    /**
     * Require the creator's normal Videos tab.  This is intentionally conservative:
     * when the active tab cannot be proven to be Videos we refuse to click anything.
     */
    private fun creatorVideosBounds(root: AccessibilityNodeInfo?): ContentBounds? {
        if (root == null) return null
        val screen = resources.displayMetrics
        var videos: AccessibilityNodeInfo? = null
        var selectedWrong = false
        fun walk(n: AccessibilityNodeInfo) {
            val t = nodeLabel(n)
            val r = Rect(); n.getBoundsInScreen(r)
            // Creator tabs live in the upper/middle part of the page, never in the bottom nav.
            if (r.top < (screen.heightPixels * 0.78f).toInt()) {
                if (isVideosLabel(t) && (n.isSelected || n.isClickable)) videos = n
                if (isWrongCreatorTab(t) && n.isSelected) selectedWrong = true
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        if (selectedWrong) return null
        val v = videos ?: return null
        // Prefer an explicit selected state. Some YouTube builds don't expose it, so when no
        // competing tab is selected we still use the Videos tab row as a safe content boundary.
        val r = Rect(); v.getBoundsInScreen(r)
        if (r.isEmpty) return null
        val top = (r.bottom + 16).coerceAtLeast((screen.heightPixels * 0.22f).toInt())
        val bottom = (screen.heightPixels * 0.90f).toInt()
        return if (top < bottom) ContentBounds(top, bottom) else null
    }

    private fun videoCandidates(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()
        val bounds = creatorVideosBounds(root) ?: return emptyList()
        val screenW = resources.displayMetrics.widthPixels
        val rejected = listOf(
            "subscribe", "subscriptions", "community", "instagram", "more", "sort", "search",
            "订阅", "社区", "更多", "排序", "搜索", "首页", "我的", "shorts", "播放列表"
        )
        val found = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        fun walk(n: AccessibilityNodeInfo) {
            val text = nodeLabel(n)
            val r = Rect(); n.getBoundsInScreen(r)
            val click = if (n.isClickable) n else clickableNode(n)
            if (text.isNotBlank() && click != null &&
                r.top >= bounds.top && r.bottom <= bounds.bottom &&
                r.width() >= 120 && r.height() >= 40 &&
                rejected.none { text.contains(it, true) }) {
                val cr = Rect(); click.getBoundsInScreen(cr)
                // Reject tab/header-wide buttons and tiny overflow/menu controls.
                if (cr.top >= bounds.top && cr.bottom <= bounds.bottom &&
                    cr.width() >= 160 && cr.height() in 60..700 &&
                    !(cr.width() > (screenW * 0.92f).toInt() && cr.height() < 100)) {
                    found += n to cr
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        // Collapse multiple text descendants that belong to the same video card, then order by
        // what the user sees on screen rather than Accessibility tree traversal order.
        return found
            .sortedWith(compareBy<Pair<AccessibilityNodeInfo, Rect>> { it.second.top }.thenBy { it.second.left })
            .distinctBy { p ->
                val r = p.second
                "${r.left / 24}:${r.top / 24}:${r.right / 24}:${r.bottom / 24}"
            }
            .map { it.first }
            .take(20)
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
        if (activeInstance === this) activeInstance = null
        scope.cancel()
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlay = null
        super.onDestroy()
    }
}
