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
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class TestScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var tempFile: File? = null
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
            // Record to a real app-owned file first. A few OEM MediaRecorder implementations
            // silently write only a ~3 KB MP4 header when the output target is a MediaStore fd.
            // Publishing to Downloads happens only after stop() and validation succeed.
            val tmpDir = File(getExternalFilesDir(null), "screen_recording_tmp").apply { mkdirs() }
            tempFile = File(tmpDir, name).also { if (it.exists()) it.delete() }

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
                val pixels = width.toLong() * height.toLong()
                setVideoEncodingBitRate(if (pixels >= 700_000L) 3_500_000 else 2_500_000)
                setOutputFile(tempFile!!.absolutePath)
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
            currentUri = ""
            currentStartedAt = now
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

        val file = tempFile
        var publishedUri: Uri? = null
        if (stopOk && file != null && validateRecording(file)) {
            publishedUri = runCatching { publishRecording(file, currentName, currentStartedAt) }
                .onFailure { lastError = "Screen recording publish failed: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
            stopOk = publishedUri != null
        } else if (stopOk) {
            stopOk = false
            val bytes = file?.length() ?: 0L
            lastError = "Screen recording invalid or empty (${bytes} bytes). This device did not provide video frames."
        }

        runCatching { file?.delete() }
        tempFile = null

        if (stopOk && publishedUri != null) {
            lastName = currentName
            lastUri = publishedUri.toString()
            lastStartedAt = currentStartedAt
        } else {
            lastName = ""
            lastUri = ""
            lastStartedAt = 0L
        }
        currentName = ""
        currentUri = ""
        currentStartedAt = 0L
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun validateRecording(file: File): Boolean {
        if (!file.exists() || file.length() < 64 * 1024L) return false
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                duration >= 250L && width > 0 && height > 0
            } finally {
                retriever.release()
            }
        }.getOrDefault(false)
    }

    private fun publishRecording(file: File, name: String, startedAt: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Downloads.RELATIVE_PATH, ReportStorage.relativePath("Screen Recordings", startedAt))
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create screen recording output")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("Unable to open screen recording output")
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
            return uri
        } catch (t: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            throw t
        }
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
        runCatching { tempFile?.delete() }
        tempFile = null
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
     * Use a conservative 720p-class portrait/landscape size. Some OEM AVC encoders advertise
     * larger sizes but MediaRecorder then starts without ever emitting frames. 720p-class output
     * is enough for report calibration and is broadly supported by hardware encoders.
     */
    private fun compatibleCaptureSize(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val safeW = rawWidth.coerceAtLeast(320)
        val safeH = rawHeight.coerceAtLeast(320)
        val longSide = maxOf(safeW, safeH).toFloat()
        val shortSide = minOf(safeW, safeH).toFloat()
        val scale = min(1f, min(1280f / longSide, 720f / shortSide))
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
