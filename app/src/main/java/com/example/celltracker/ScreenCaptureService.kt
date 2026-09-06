package com.example.celltracker

import android.app.*
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var density = 0

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_INIT -> initProjection(intent)
            ACTION_CAPTURE_MARK -> captureAndMark(intent)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun initProjection(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("CellTracker screenshot ready")
            .setContentText("Screenshots are captured only when you mark an issue")
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
                val task = sanitize(RecordingState.status.value.taskName.ifBlank { "Untitled" })
                val app = sanitize(foregroundAppLabel().ifBlank { "Screen" })
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

    private fun foregroundAppLabel(): String {
        return runCatching {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            val events = usm.queryEvents(end - 30_000L, end)
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTime = Long.MIN_VALUE
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
                val pkg = event.packageName.orEmpty()
                if (isForeground && pkg.isNotBlank() && pkg != packageName &&
                    pkg != "com.android.systemui" && event.timeStamp >= latestTime) {
                    latestPackage = pkg
                    latestTime = event.timeStamp
                }
            }
            val pkg = latestPackage ?: run {
                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, end - 30_000L, end)
                stats.filter { it.packageName != packageName && it.packageName != "com.android.systemui" }
                    .maxByOrNull { it.lastTimeUsed }?.packageName
            } ?: return@runCatching "Screen"
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString().ifBlank { "Screen" }
        }.getOrDefault("Screen")
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
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "celltracker_capture"
        const val NOTIFICATION_ID = 1002
        @Volatile var isReady: Boolean = false
    }
}
