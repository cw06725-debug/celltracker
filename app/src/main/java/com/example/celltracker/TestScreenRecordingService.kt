package com.example.celltracker

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.min
import kotlin.math.roundToInt

class TestScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputUri: Uri? = null
    // Keep the ParcelFileDescriptor alive for the entire recording. Holding only
    // FileDescriptor lets some OEMs/GC close the underlying fd early, producing a
    // tiny (about 3 KB), 0x0/00:00 MP4 even though MediaRecorder.start() succeeded.
    private var outputPfd: ParcelFileDescriptor? = null
    private var recorderStarted = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Test screen recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> startRecording(intent)
            STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (isRecording || recorderStarted) return

        val resultCode = intent.getIntExtra(CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(DATA)
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            lastError = "Screen capture permission was not granted"
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        val name = safe(intent.getStringExtra(NAME).orEmpty().ifBlank { "CellTracker_Test" }) +
            "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date(now)) + ".mp4"

        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Downloads.RELATIVE_PATH, ReportStorage.relativePath("Screen Recordings", now))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create screen recording file")
            outputPfd = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: error("Unable to open screen recording file")

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val (width, height) = compatibleCaptureSize(metrics.widthPixels, metrics.heightPixels)

            recorder = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder!!.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                // Native 2K+/20:9 resolutions fail or silently produce no frames on
                // a number of OEM encoders. Use a conservative bitrate for the capped size.
                val pixels = width.toLong() * height.toLong()
                setVideoEncodingBitRate(if (pixels >= 1_500_000L) 6_000_000 else 4_000_000)
                setOutputFile(outputPfd!!.fileDescriptor)
                setOnErrorListener { _, what, extra ->
                    lastError = "MediaRecorder error ($what/$extra)"
                    Handler(Looper.getMainLooper()).post { stopRecording() }
                }
                prepare()
            }

            val notification = NotificationCompat.Builder(this, CH)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("CellTracker screen recording")
                .setContentText(name)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NID, notification)
            }

            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, Handler(mainLooper))

            virtualDisplay = projection?.createVirtualDisplay(
                "CellTrackerTestRecording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface,
                null,
                null
            ) ?: error("Unable to create screen capture display")

            recorder!!.start()
            recorderStarted = true
            isRecording = true
            currentName = name
            currentUri = outputUri.toString()
            currentStartedAt = System.currentTimeMillis()
            lastError = ""
        } catch (t: Throwable) {
            lastError = "Screen recording start failed: ${t.message ?: t.javaClass.simpleName}"
            cleanupFailedStart()
        }
    }

    private fun stopRecording() {
        if (!isRecording && !recorderStarted) {
            stopSelf()
            return
        }
        isRecording = false

        var stopOk = true
        if (recorderStarted) {
            runCatching { recorder?.stop() }.onFailure {
                stopOk = false
                lastError = "Screen recording produced no valid frames: ${it.message ?: it.javaClass.simpleName}"
            }
        }
        recorderStarted = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }

        // MediaRecorder needs the fd until stop() has finished. Close it only now.
        runCatching { outputPfd?.close() }
        outputPfd = null

        if (Build.VERSION.SDK_INT >= 29) {
            outputUri?.let { uri ->
                runCatching {
                    contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
            }
        }

        if (stopOk) {
            lastName = currentName
            lastUri = currentUri
            lastStartedAt = currentStartedAt
        }
        currentName = ""
        currentUri = ""
        currentStartedAt = 0L
        outputUri = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun cleanupFailedStart() {
        isRecording = false
        recorderStarted = false
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        runCatching { outputPfd?.close() }
        outputPfd = null
        outputUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
        outputUri = null
        currentName = ""
        currentUri = ""
        currentStartedAt = 0L
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (isRecording || recorderStarted) stopRecording()
        super.onDestroy()
    }

    /**
     * Some devices expose 1220x2712, 1260x2800, 1440x3200, etc. Their H.264
     * MediaRecorder encoder often cannot accept the native display size even though prepare/start
     * succeeds. Cap the long side to 1920 and short side to 1080, preserve aspect ratio,
     * and align to 16 pixels for broad hardware encoder compatibility.
     */
    private fun compatibleCaptureSize(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val safeW = rawWidth.coerceAtLeast(320)
        val safeH = rawHeight.coerceAtLeast(320)
        val longSide = maxOf(safeW, safeH).toFloat()
        val shortSide = minOf(safeW, safeH).toFloat()
        val scale = min(1f, min(1920f / longSide, 1080f / shortSide))
        fun align16(value: Float): Int = ((value.roundToInt().coerceAtLeast(320)) / 16 * 16).coerceAtLeast(320)
        return align16(safeW * scale) to align16(safeH * scale)
    }

    private fun safe(s: String) = s
        .replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
        .replace(Regex("\\s+"), "_")
        .trim('_')
        .take(80)

    companion object {
        const val START = "com.example.celltracker.TEST_RECORD_START"
        const val STOP = "com.example.celltracker.TEST_RECORD_STOP"
        const val CODE = "code"
        const val DATA = "data"
        const val NAME = "name"
        const val CH = "celltracker_test_record"
        const val NID = 1011

        @Volatile var isRecording = false
        @Volatile var currentName = ""
        @Volatile var currentUri = ""
        @Volatile var lastName = ""
        @Volatile var lastUri = ""
        @Volatile var currentStartedAt = 0L
        @Volatile var lastStartedAt = 0L
        @Volatile var lastError = ""
    }
}
