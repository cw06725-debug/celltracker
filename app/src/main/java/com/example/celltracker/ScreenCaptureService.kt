package com.example.celltracker

import android.app.*
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.os.Process
import android.provider.Settings
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var density = 0
    private var visualAiCollectorActive = false
    private var visualAiSessionStartedAt = 0L
    private var visualAiFrameCount = 0
    private var visualAiLastFrameElapsed = 0L
    private var visualAiAttempt = 0
    private var visualAiT0Elapsed = 0L
    private var visualAiPlayOkElapsed = 0L
    private var visualAiRecsOkElapsed = 0L
    private var visualAiCaptureJob: Job? = null
    private var visualAiOverlay: android.view.View? = null
    private var visualAiOverlayStatus: TextView? = null
    private var visualAiSessionDir: File? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> initProjection(intent)
            ACTION_CAPTURE_MARK -> captureAndMark(intent)
            ACTION_START_VISUAL_AI -> startVisualAiCollector()
            ACTION_STOP_VISUAL_AI -> stopVisualAiCollector()
            ACTION_VISUAL_AI_T0 -> markVisualAiT0()
            ACTION_VISUAL_AI_PLAY_OK -> markVisualAiPlayOk()
            ACTION_VISUAL_AI_RECS_OK -> markVisualAiRecsOk()
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun initProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) return

        val collectorRequested = intent.getBooleanExtra(EXTRA_START_VISUAL_AI, false)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (collectorRequested) "CellTracker Visual AI Collector" else "CellTracker screenshot ready")
            .setContentText(if (collectorRequested) "Collecting YouTube PLAYER + RECS training frames" else "Screenshots are captured only when you mark an issue")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        density = metrics.densityDpi
        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                isReady = false
                virtualDisplay?.release(); virtualDisplay = null
                reader?.close(); reader = null
            }
        }, android.os.Handler(mainLooper))
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "CellTrackerCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null
        )
        isReady = projection != null
        if (collectorRequested && isReady) startVisualAiCollector()
    }

    private fun startVisualAiCollector() {
        val r = reader ?: return
        visualAiCaptureJob?.cancel()
        visualAiCollectorActive = true
        visualAiSessionStartedAt = System.currentTimeMillis()
        visualAiFrameCount = 0
        visualAiLastFrameElapsed = 0L
        visualAiAttempt = 0
        visualAiT0Elapsed = 0L
        visualAiPlayOkElapsed = 0L
        visualAiRecsOkElapsed = 0L
        collectorFrameCount = 0
        collectorAttempt = 0
        collectorPhase = "UNLABELED"
        collectorSaveFailures = 0
        collectorLastPath = ""
        collectorExportPath = ""
        collectorExportStatus = ""
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(visualAiSessionStartedAt))
        val session = SimpleDateFormat("HHmmss", Locale.US).format(Date(visualAiSessionStartedAt))
        visualAiSessionDir = File(getExternalFilesDir(null), "VisualAI/$date/session_$session").apply { mkdirs() }
        collectorActive = true

        // Do not depend on ImageReader callbacks. Some Samsung/Android builds keep the
        // MediaProjection alive but never deliver the listener callback reliably. Poll the
        // latest buffer from an IO coroutine instead; this is also how the existing mark
        // screenshot path has proven stable.
        r.setOnImageAvailableListener(null, null)
        showVisualAiOverlay()

        visualAiCaptureJob = scope.launch {
            delay(250)
            while (isActive && visualAiCollectorActive && projection != null) {
                val cycleStart = android.os.SystemClock.elapsedRealtime()
                val image = runCatching { r.acquireLatestImage() }.getOrNull()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width
                        val full = Bitmap.createBitmap(
                            width + rowPadding / pixelStride,
                            height,
                            Bitmap.Config.ARGB_8888
                        )
                        full.copyPixelsFromBuffer(buffer)
                        val screen = Bitmap.createBitmap(full, 0, 0, width, height)
                        full.recycle()

                        visualAiFrameCount++
                        collectorFrameCount = visualAiFrameCount
                        val relative = System.currentTimeMillis() - visualAiSessionStartedAt
                        saveVisualAiRois(screen, visualAiSessionStartedAt, visualAiFrameCount, relative)
                        screen.recycle()
                        updateVisualAiOverlay()
                    } catch (_: Throwable) {
                        collectorSaveFailures++
                        updateVisualAiOverlay()
                    } finally {
                        runCatching { image.close() }
                    }
                }

                // Target roughly 2 fps. If no fresh frame was available, retry sooner so startup
                // does not sit at Frames: 0 for seconds.
                val spent = android.os.SystemClock.elapsedRealtime() - cycleStart
                delay(if (image == null) 120L else (500L - spent).coerceAtLeast(80L))
            }
        }
    }

    private fun showVisualAiOverlay() {
        if (!Settings.canDrawOverlays(this) || visualAiOverlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0xDD202124.toInt())
        }
        val status = TextView(this).apply {
            text = "AI F:0 A:0"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(4, 0, 4, 0)
        }
        visualAiOverlayStatus = status
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.9f))

        fun addButton(label: String, weight: Float = 1f, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                textSize = 10f
                minHeight = 0
                minWidth = 0
                setPadding(2, 0, 2, 0)
                setOnClickListener { action() }
            }
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight))
        }
        addButton("T0") { markVisualAiT0() }
        addButton("PLAY") { markVisualAiPlayOk() }
        addButton("RECS") { markVisualAiRecsOk() }
        addButton("STOP", 0.8f) { stopVisualAiCollector() }

        val barHeight = (52f * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            barHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            x = 0
            y = 0
        }
        runCatching {
            wm.addView(bar, lp)
            visualAiOverlay = bar
        }
    }

    private fun updateVisualAiOverlay() {
        val text = "AI F:${collectorFrameCount} A:${collectorAttempt} ${collectorPhase}"
        android.os.Handler(mainLooper).post {
            visualAiOverlayStatus?.text = text
        }
    }

    private fun removeVisualAiOverlay() {
        val v = visualAiOverlay ?: return
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) }
        visualAiOverlay = null
        visualAiOverlayStatus = null
    }

    private fun markVisualAiT0() {
        visualAiAttempt++
        visualAiT0Elapsed = android.os.SystemClock.elapsedRealtime()
        visualAiPlayOkElapsed = 0L
        visualAiRecsOkElapsed = 0L
        collectorAttempt = visualAiAttempt
        collectorPhase = "LOADING"
        appendVisualAiLabel("T0")
        updateVisualAiOverlay()
    }

    private fun markVisualAiPlayOk() {
        if (visualAiT0Elapsed <= 0L) return
        visualAiPlayOkElapsed = android.os.SystemClock.elapsedRealtime()
        collectorPhase = "PLAY_OK"
        appendVisualAiLabel("PLAY_OK")
        updateVisualAiOverlay()
    }

    private fun markVisualAiRecsOk() {
        if (visualAiT0Elapsed <= 0L) return
        visualAiRecsOkElapsed = android.os.SystemClock.elapsedRealtime()
        collectorPhase = "RECS_OK"
        appendVisualAiLabel("RECS_OK")
        updateVisualAiOverlay()
    }

    private fun appendVisualAiLabel(label: String) {
        val sessionMs = visualAiSessionStartedAt
        if (sessionMs <= 0L) return
        runCatching {
            val dir = visualAiSessionDir ?: run {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sessionMs))
                val session = SimpleDateFormat("HHmmss", Locale.US).format(Date(sessionMs))
                File(getExternalFilesDir(null), "VisualAI/$date/session_$session").apply { mkdirs() }
            }
            val f = File(dir, "labels.csv")
            if (!f.exists()) f.appendText("attempt,label,wall_ms,elapsed_ms,from_t0_ms\n")
            val nowWall = System.currentTimeMillis()
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            val fromT0 = if (visualAiT0Elapsed > 0L) nowElapsed - visualAiT0Elapsed else -1L
            f.appendText("$visualAiAttempt,$label,$nowWall,$nowElapsed,$fromT0\n")
        }
    }

    private fun stopVisualAiCollector() {
        visualAiCollectorActive = false
        collectorActive = false
        visualAiCaptureJob?.cancel()
        visualAiCaptureJob = null
        reader?.setOnImageAvailableListener(null, null)
        removeVisualAiOverlay()
        val dir = visualAiSessionDir
        if (dir != null && dir.exists()) {
            collectorExportStatus = "EXPORTING"
            scope.launch {
                val result = exportVisualAiSessionZip(dir, visualAiSessionStartedAt)
                if (result.isNotBlank()) {
                    collectorExportPath = result
                    collectorExportStatus = "EXPORTED"
                } else {
                    collectorExportStatus = "EXPORT_FAILED"
                }
            }
        }
    }

    private fun exportVisualAiSessionZip(dir: File, sessionMs: Long): String {
        if (Build.VERSION.SDK_INT < 29) return ""
        return runCatching {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sessionMs))
            val session = SimpleDateFormat("HHmmss", Locale.US).format(Date(sessionMs))
            val displayName = "CellTracker_VisualAI_${date}_$session.zip"
            val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/CellTracker/VisualAI/$date"

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching ""
            var success = false
            try {
                contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        val base = dir.absolutePath.trimEnd(File.separatorChar) + File.separator
                        dir.walkTopDown().filter { it.isFile }.forEach { file ->
                            val name = file.absolutePath.removePrefix(base).replace(File.separatorChar, '/')
                            zip.putNextEntry(ZipEntry(name))
                            file.inputStream().use { input -> input.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    success = true
                }
                if (success) {
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    "$relativePath/$displayName"
                } else {
                    contentResolver.delete(uri, null, null)
                    ""
                }
            } catch (_: Throwable) {
                contentResolver.delete(uri, null, null)
                ""
            }
        }.getOrDefault("")
    }

    private fun saveVisualAiRois(screen: Bitmap, sessionMs: Long, frame: Int, relativeMs: Long) {
        val dir = visualAiSessionDir ?: run {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sessionMs))
            val session = SimpleDateFormat("HHmmss", Locale.US).format(Date(sessionMs))
            File(getExternalFilesDir(null), "VisualAI/$date/session_$session").apply { mkdirs() }
        }

        val w = screen.width
        val h = screen.height
        fun crop(l: Float, t: Float, r: Float, b: Float): Bitmap {
            val x1 = (w*l).toInt().coerceIn(0,w-1)
            val y1 = (h*t).toInt().coerceIn(0,h-1)
            val x2 = (w*r).toInt().coerceIn(x1+1,w)
            val y2 = (h*b).toInt().coerceIn(y1+1,h)
            return Bitmap.createBitmap(screen,x1,y1,x2-x1,y2-y1)
        }
        val fromT0 = if (visualAiT0Elapsed > 0L) (android.os.SystemClock.elapsedRealtime() - visualAiT0Elapsed).coerceAtLeast(0L) else -1L
        val attemptPart = if (visualAiAttempt > 0) "A${visualAiAttempt.toString().padStart(3,'0')}" else "A000"
        val phase = when {
            visualAiT0Elapsed <= 0L -> "UNLABELED"
            visualAiRecsOkElapsed > 0L -> "READY"
            visualAiPlayOkElapsed > 0L -> "PLAY_OK"
            else -> "LOADING"
        }
        val prefix = "${attemptPart}_${phase}_F${frame.toString().padStart(4,'0')}_T0+${fromT0}ms"
        val player = crop(.02f,.07f,.98f,.55f)
        val recs = crop(.02f,.43f,.98f,.96f)
        FileOutputStream(File(dir, "${prefix}_PLAYER.jpg")).use { player.compress(Bitmap.CompressFormat.JPEG, 84, it) }
        FileOutputStream(File(dir, "${prefix}_RECS.jpg")).use { recs.compress(Bitmap.CompressFormat.JPEG, 84, it) }
        player.recycle(); recs.recycle()
        collectorLastPath = dir.absolutePath
    }

    private fun captureAndMark(intent: Intent) {
        val subId = intent.getIntExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, -1)
        val eventType = intent.getStringExtra(RecordingService.EXTRA_EVENT_TYPE).orEmpty().ifBlank { "General" }
        val eventNote = intent.getStringExtra(RecordingService.EXTRA_EVENT_NOTE).orEmpty()
        if (!isReady || reader == null) {
            sendMark(subId, eventType, eventNote, "")
            return
        }
        scope.launch {
            delay(180)
            var image = reader?.acquireLatestImage()
            var attempts = 0
            while (image == null && attempts < 10) {
                delay(80)
                image = reader?.acquireLatestImage()
                attempts++
            }
            if (image == null) {
                sendMark(subId, eventType, eventNote, "")
                return@launch
            }
            val path = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                bitmap.recycle()
                val dir = File(getExternalFilesDir(null), "screenshots").apply { mkdirs() }
                val task = sanitize(resolveRecordingTaskName().ifBlank { "Untitled" })
                val app = sanitize(foregroundAppLabel())
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val file = File(dir, "${task}_${app}_$stamp.png")
                FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
                cropped.recycle()
                file.absolutePath
            } catch (_: Exception) {
                ""
            } finally {
                image.close()
            }
            sendMark(subId, eventType, eventNote, path)
        }
    }


    /**
     * Resolve the current task name independently of the Activity/ViewModel lifecycle.
     * Overlay-started recordings may continue while the main UI is backgrounded, so
     * screenshot naming must not rely only on the in-memory RecordingState value.
     */
    private fun resolveRecordingTaskName(): String {
        RecordingState.status.value.taskName.trim().takeIf { it.isNotBlank() }?.let { return it }
        val prefs = getSharedPreferences("celltracker_recording", MODE_PRIVATE)
        prefs.getString("active_task_name", null)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val path = RecordingState.status.value.latestPath
            ?: prefs.getString("active_path", null)
            ?: prefs.getString("latest_path", null)
        return path?.let { taskNameFromRecordingPath(it) }.orEmpty()
    }

    private fun taskNameFromRecordingPath(path: String): String {
        val base = File(path).nameWithoutExtension.removePrefix("CellTracker_").removePrefix("CellTracker")
        val match = Regex("^(.*)_(DualSIM|SIM\\d+|SIM)_\\d{8}_\\d{6}$").matchEntire(base)
        return match?.groupValues?.getOrNull(1).orEmpty().trim('_').replace('_', ' ')
    }

    private fun foregroundAppLabel(): String {
        if (!hasUsageAccess(this)) return "UnknownApp"
        return runCatching {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            // A tested app may remain foreground for minutes without emitting another resume
            // event. Start narrow for accuracy, then widen until a valid external app is found.
            val windows = longArrayOf(2 * 60_000L, 15 * 60_000L, 2 * 60 * 60_000L, 24 * 60 * 60_000L)
            var foregroundPackage: String? = null
            for (window in windows) {
                foregroundPackage = latestForegroundPackage(usm, end - window, end)
                if (foregroundPackage != null) break
            }
            val pkg = foregroundPackage ?: latestUsageStatsPackage(usm, end - 24 * 60 * 60_000L, end)
                ?: return@runCatching "UnknownApp"
            applicationLabelOrPackageSuffix(pkg)
        }.getOrDefault("UnknownApp")
    }

    private fun latestForegroundPackage(usm: UsageStatsManager, begin: Long, end: Long): String? {
        val events = usm.queryEvents(begin, end)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTime = Long.MIN_VALUE
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            val pkg = event.packageName.orEmpty()
            if (isForeground && isEligibleForegroundPackage(pkg) && event.timeStamp >= latestTime) {
                latestPackage = pkg
                latestTime = event.timeStamp
            }
        }
        return latestPackage
    }

    private fun latestUsageStatsPackage(usm: UsageStatsManager, begin: Long, end: Long): String? {
        return usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, end)
            .asSequence()
            .filter { isEligibleForegroundPackage(it.packageName) }
            .maxByOrNull { stats ->
                if (Build.VERSION.SDK_INT >= 29) maxOf(stats.lastTimeUsed, stats.lastTimeVisible)
                else stats.lastTimeUsed
            }
            ?.packageName
    }

    private fun isEligibleForegroundPackage(pkg: String): Boolean {
        return pkg.isNotBlank() && pkg != packageName && pkg !in EXCLUDED_FOREGROUND_PACKAGES
    }

    private fun applicationLabelOrPackageSuffix(pkg: String): String {
        val label = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info).toString().trim()
        }.getOrNull()
        return label?.takeIf { it.isNotBlank() }
            ?: pkg.substringAfterLast('.').takeIf { it.isNotBlank() }
            ?: "UnknownApp"
    }

    private fun sendMark(subId: Int, eventType: String, eventNote: String, screenshotPath: String) {
        val mark = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_MARK
            putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, subId)
            putExtra(RecordingService.EXTRA_EVENT_TYPE, eventType)
            putExtra(RecordingService.EXTRA_EVENT_NOTE, eventNote)
            putExtra(RecordingService.EXTRA_SCREENSHOT_PATH, screenshotPath)
        }
        ContextCompat.startForegroundService(this, mark)
    }

    private fun sanitize(v: String) = v.replace(Regex("[\\/:*?\"<>|\\r\\n]+"), "_").replace(Regex("\\s+"), "_").trim('_').take(48)

    override fun onDestroy() {
        isReady = false
        visualAiCollectorActive = false
        collectorActive = false
        visualAiCaptureJob?.cancel()
        removeVisualAiOverlay()
        virtualDisplay?.release(); virtualDisplay = null
        reader?.close(); reader = null
        projection?.stop(); projection = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CellTracker screenshots", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_INIT = "com.example.celltracker.CAPTURE_INIT"
        const val ACTION_CAPTURE_MARK = "com.example.celltracker.CAPTURE_MARK"
        const val ACTION_STOP = "com.example.celltracker.CAPTURE_STOP"
        const val ACTION_START_VISUAL_AI = "com.example.celltracker.VISUAL_AI_START"
        const val ACTION_STOP_VISUAL_AI = "com.example.celltracker.VISUAL_AI_STOP"
        const val ACTION_VISUAL_AI_T0 = "com.example.celltracker.VISUAL_AI_T0"
        const val ACTION_VISUAL_AI_PLAY_OK = "com.example.celltracker.VISUAL_AI_PLAY_OK"
        const val ACTION_VISUAL_AI_RECS_OK = "com.example.celltracker.VISUAL_AI_RECS_OK"
        const val EXTRA_START_VISUAL_AI = "start_visual_ai"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "celltracker_capture"
        const val NOTIFICATION_ID = 1002
        @Volatile var isReady: Boolean = false
        @Volatile var collectorActive: Boolean = false
        @Volatile var collectorFrameCount: Int = 0
        @Volatile var collectorSaveFailures: Int = 0
        @Volatile var collectorLastPath: String = ""
        @Volatile var collectorAttempt: Int = 0
        @Volatile var collectorPhase: String = "UNLABELED"
        @Volatile var collectorExportPath: String = ""
        @Volatile var collectorExportStatus: String = ""

        private val EXCLUDED_FOREGROUND_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )

        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
