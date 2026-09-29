package com.example.celltracker

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import android.os.ParcelFileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.2.27 intentionally keeps the v1.2.21 recording path and test behavior.
 * The only addition here is detailed diagnostics so an OEM-specific screen-capture
 * failure can be localized without changing YouTube/TikTok event timing logic.
 */
class TestScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var outputUri: Uri? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var recorderStarted = false
    @Volatile private var stopping = false

    private var diagnosticFile: File? = null
    private var diagnosticStartedAt = 0L
    private val diagnosticLock = Any()

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
            "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now)) + ".mp4"
        startDiagnostics(name, now, resultCode, true)
        diag("START requested; v1.2.21 MediaRecorder path")

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
                ?: error("Unable to create screen recording output")
            outputPfd = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: error("Unable to open screen recording output")
            diag("MediaStore output created uri=$outputUri")

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = (metrics.widthPixels / 2) * 2
            val height = (metrics.heightPixels / 2) * 2
            diag("Display raw=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi}; capture=${width}x${height}")

            recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            recorder!!.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(8_000_000)
                setOutputFile(outputPfd!!.fileDescriptor)
                prepare()
            }
            diag("MediaRecorder.prepare OK encoder=H264 size=${width}x${height} fps=30 bitrate=8000000")

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

            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, data)
            diag("MediaProjection created=${projection != null}")
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    diag("MediaProjection.Callback.onStop received")
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
            diag("VirtualDisplay created=true name=CellTrackerTestRecording ${width}x${height} dpi=${metrics.densityDpi}")

            recorder!!.start()
            recorderStarted = true
            diag("MediaRecorder.start OK")

            isRecording = true
            currentName = name
            currentUri = outputUri.toString()
            currentStartedAt = now
            lastError = ""
            diag("Recording state ACTIVE")
        } catch (t: Throwable) {
            lastError = "Screen recording start failed: ${t.message ?: t.javaClass.simpleName}"
            diagThrowable("START FAILED", t)
            cleanupFailedStart()
        }
    }

    private fun stopRecording() {
        if (stopping) return
        if (!isRecording && !recorderStarted) {
            stopSelf()
            return
        }
        stopping = true
        isRecording = false
        val uri = outputUri
        diag("STOP requested recorderStarted=$recorderStarted")

        var stopOk = true
        if (recorderStarted) {
            runCatching { recorder?.stop() }
                .onSuccess { diag("MediaRecorder.stop OK") }
                .onFailure {
                    stopOk = false
                    lastError = "MediaRecorder.stop failed: ${it.message ?: it.javaClass.simpleName}"
                    diagThrowable("MediaRecorder.stop FAILED", it)
                }
        }
        recorderStarted = false
        runCatching { recorder?.release() }
        recorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        runCatching { outputPfd?.close() }
        outputPfd = null

        if (Build.VERSION.SDK_INT >= 29 && uri != null) {
            runCatching {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            }
        }

        if (uri != null) inspectPublishedVideo(uri)

        lastName = currentName
        lastUri = currentUri
        lastStartedAt = currentStartedAt
        currentName = ""
        currentUri = ""
        currentStartedAt = 0L
        outputUri = null
        stopping = false
        diag("FINAL stopOk=$stopOk lastError=${lastError.ifBlank { "<none>" }} lastUri=$lastUri")
        publishDiagnostics()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun inspectPublishedVideo(uri: Uri) {
        val bytes = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        var durationMs = -1L
        var width = -1
        var height = -1
        runCatching {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(this, uri)
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
                width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: -1
                height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: -1
            } finally {
                mmr.release()
            }
        }.onFailure { diagThrowable("Metadata validation FAILED", it) }
        diag("Validation bytes=$bytes durationMs=$durationMs width=$width height=$height")
        if (bytes in 0..8191 || durationMs <= 0 || width <= 0 || height <= 0) {
            lastError = "Screen recording invalid/empty: bytes=$bytes durationMs=$durationMs size=${width}x${height}"
            diag("INVALID RECORDING: $lastError")
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
        runCatching { outputPfd?.close() }
        outputPfd = null
        outputUri?.let { runCatching { contentResolver.delete(it, null, null) } }
        outputUri = null
        currentName = ""
        currentUri = ""
        currentStartedAt = 0L
        diag("cleanupFailedStart complete")
        publishDiagnostics()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRecording || recorderStarted) stopRecording()
        super.onDestroy()
    }

    private fun startDiagnostics(recordingName: String, startedAt: Long, resultCode: Int, dataPresent: Boolean) {
        diagnosticStartedAt = startedAt
        val dir = File(getExternalFilesDir(null), "screen_recording_diagnostics").apply { mkdirs() }
        val stem = recordingName.removeSuffix(".mp4")
        diagnosticFile = File(dir, "${stem}_diagnostic.txt").also {
            runCatching {
                it.writeText(
                    "CellTracker Screen Recording Diagnostic\n" +
                        "recording=$recordingName\n" +
                        "startedAt=$startedAt\n" +
                        "manufacturer=${Build.MANUFACTURER}\n" +
                        "brand=${Build.BRAND}\n" +
                        "model=${Build.MODEL}\n" +
                        "device=${Build.DEVICE}\n" +
                        "product=${Build.PRODUCT}\n" +
                        "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}\n" +
                        "build=${Build.DISPLAY}\n" +
                        "resultCode=$resultCode dataPresent=$dataPresent\n" +
                        "----------------------------------------\n"
                )
            }
        }
        diag("Diagnostics initialized")
        val avc = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
                .joinToString(" | ") { info ->
                    if (Build.VERSION.SDK_INT >= 29) "${info.name}[sw=${info.isSoftwareOnly},vendor=${info.isVendor}]" else info.name
                }
        }.getOrElse { "<codec enumeration failed: ${it.message}>" }
        diag("Available AVC encoders: $avc")
    }

    private fun diag(message: String) {
        val elapsed = if (diagnosticStartedAt > 0L) System.currentTimeMillis() - diagnosticStartedAt else -1L
        val line = String.format(Locale.US, "%+07dms [%s] %s", elapsed, Thread.currentThread().name, message)
        Log.i("CTScreenRec", line)
        synchronized(diagnosticLock) { runCatching { diagnosticFile?.appendText(line + "\n") } }
    }

    private fun diagThrowable(prefix: String, t: Throwable) {
        diag("$prefix: ${t.javaClass.name}: ${t.message}")
        synchronized(diagnosticLock) { runCatching { diagnosticFile?.appendText(t.stackTraceToString() + "\n") } }
    }

    private fun publishDiagnostics() {
        val file = diagnosticFile ?: return
        if (!file.exists()) return
        val startedAt = diagnosticStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        val displayName = file.name
        val uri = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Downloads.RELATIVE_PATH, ReportStorage.relativePath("Screen Recording Diagnostics", startedAt))
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Unable to create diagnostic output")
            try {
                contentResolver.openOutputStream(outUri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: error("Unable to open diagnostic output")
                if (Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }
                outUri
            } catch (t: Throwable) {
                runCatching { contentResolver.delete(outUri, null, null) }
                throw t
            }
        }.onFailure { Log.e("CTScreenRec", "Publish diagnostic failed", it) }.getOrNull()
        if (uri != null) {
            lastDiagnosticUri = uri.toString()
            lastDiagnosticName = displayName
            lastDiagnosticPathHint = ReportStorage.relativePath("Screen Recording Diagnostics", startedAt) + "/" + displayName
        }
        diagnosticFile = null
        diagnosticStartedAt = 0L
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
        @Volatile var lastDiagnosticUri = ""
        @Volatile var lastDiagnosticName = ""
        @Volatile var lastDiagnosticPathHint = ""
    }
}
