package com.example.celltracker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Path
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
            if (running) {
                if (t0 > 0L) {
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
                    stop.visibility = View.VISIBLE
                }
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
        testedContentKeys.clear()
        autoScrollCount = 0
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
            val content = creatorContentBounds(root)
            if (content == null) {
                status.text = "Creator content area not found · stay on Videos or Shorts tab and tap RETRY"
                return
            }
            val candidates = mediaCandidates(root, content)
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

    private fun isCreatorTabLabel(t: String): Boolean {
        val v = t.trim()
        return v.equals("Videos", true) || v == "视频" || v == "影片" ||
            v.equals("Shorts", true) || v == "短视频" ||
            v.equals("Playlists", true) || v.equals("Playlist", true) || v == "播放列表" ||
            v.equals("Live", true) || v == "直播" ||
            v.equals("Podcasts", true) || v.equals("Podcast", true) || v == "播客" ||
            v.equals("Courses", true) || v == "课程" || v.equals("Posts", true) || v == "帖子"
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
    private fun mediaCandidates(root: AccessibilityNodeInfo?, bounds: ContentBounds): List<MediaCandidate> {
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
            val videoRow = cr.width() >= (screenW * 0.55f).toInt() && cr.height() in 90..720
            val shortTile = cr.width() in (screenW * 0.20f).toInt()..(screenW * 0.55f).toInt() && cr.height() >= 170
            if (!videoRow && !shortTile) return
            if (cr.width() > (screenW * 0.96f).toInt() && cr.height() < 120) return

            val labels = descendantLabels(click)
            val joined = labels.joinToString(" | ").trim()
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
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        val startY = (bounds.bottom - 40).coerceAtMost((dm.heightPixels * 0.86f).toInt()).toFloat()
        val endY = (bounds.top + (bounds.bottom - bounds.top) * 0.28f).toFloat()
        if (startY <= endY) return false
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
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

    private fun isVideoPageReady(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || root.packageName?.toString() != "com.google.android.youtube") return false
        var meaningful = 0; var actions = 0; var loading = false
        fun walk(n: AccessibilityNodeInfo) {
            val t = ((n.contentDescription ?: n.text) ?: "").toString()
            if (t.contains("loading", true) || t.contains("buffering", true) || t.contains("加载") || t.contains("缓冲")) loading = true
            if (t.length > 3) meaningful++
            if (t.contains("like", true) || t.contains("share", true) || t.contains("comments", true) || t.contains("subscribe", true) ||
                t.contains("点赞") || t.contains("喜欢") || t.contains("分享") || t.contains("评论") || t.contains("订阅")) actions++
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
