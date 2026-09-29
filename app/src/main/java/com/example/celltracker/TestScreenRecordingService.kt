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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

class TestScreenRecordingService : Service() {
    private var projection: MediaProjection? = null
    private var codecRecorder: CodecRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var tempFile: File? = null
    private var recorderStarted = false
    @Volatile private var stoppingRecording = false
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
            "_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date(now)) + ".mp4"

        startDiagnostics(name, now, resultCode, data != null)
        diag("START requested; serviceStartId=n/a")

        try {
            val tmpDir = File(getExternalFilesDir(null), "screen_recording_tmp").apply { mkdirs() }
            tempFile = File(tmpDir, name).also { if (it.exists()) it.delete() }

            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val (width, height) = compatibleCaptureSize(metrics.widthPixels, metrics.heightPixels)
            diag("Display raw=${metrics.widthPixels}x${metrics.heightPixels} densityDpi=${metrics.densityDpi}; capture=${width}x${height}")

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
            diag("MediaProjection created=${projection != null}")
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    diag("MediaProjection.Callback.onStop received")
                    stopRecording()
                }
            }, Handler(mainLooper))

            // MediaCodec + MediaMuxer is used instead of MediaRecorder. Some OEM builds accept
            // MediaRecorder.start() but never deliver projection frames, leaving a ~3 KB MP4.
            // The lower-level surface encoder is more predictable and lets us verify actual output.
            codecRecorder = createCodecRecorder(tempFile!!, width, height)
            diag("CodecRecorder created")
            codecRecorder!!.start()
            diag("CodecRecorder started")

            virtualDisplay = projection?.createVirtualDisplay(
                "CellTrackerTestRecording",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                codecRecorder!!.inputSurface,
                null,
                null
            ) ?: error("Unable to create screen capture display")
            diag("VirtualDisplay created=true name=CellTrackerTestRecording ${width}x${height} dpi=${metrics.densityDpi}")

            recorderStarted = true
            isRecording = true
            currentName = name
            currentUri = ""
            currentStartedAt = now
            lastError = ""
            diag("Recording state ACTIVE; temp=${tempFile?.absolutePath}")
        } catch (t: Throwable) {
            lastError = "Screen recording start failed: ${t.message ?: t.javaClass.simpleName}"
            diagThrowable("START FAILED", t)
            cleanupFailedStart()
        }
    }

    private fun createCodecRecorder(file: File, width: Int, height: Int): CodecRecorder {
        // Try a few conservative AVC configurations. The first one is normally enough; the
        // lower variants cover devices whose vendor codec rejects a nominally supported size.
        val candidates = listOf(
            Triple(width, height, if (width.toLong() * height >= 450_000L) 1_800_000 else 1_200_000),
            Triple((width * 0.75f).roundToInt().coerceAtLeast(320) / 16 * 16,
                (height * 0.75f).roundToInt().coerceAtLeast(320) / 16 * 16, 1_200_000),
            Triple(if (width <= height) 480 else 854, if (width <= height) 854 else 480, 900_000)
        ).distinct()

        var last: Throwable? = null
        for ((w, h, bitrate) in candidates) {
            try {
                diag("Trying AVC config ${w}x${h} bitrate=$bitrate")
                return CodecRecorder(file, w, h, bitrate, ::diag)
            } catch (t: Throwable) {
                last = t
                diagThrowable("AVC config ${w}x${h} bitrate=$bitrate rejected", t)
                runCatching { file.delete() }
            }
        }
        throw IllegalStateException("No usable AVC encoder configuration", last)
    }

    private fun stopRecording() {
        if (stoppingRecording) return
        if (!isRecording && !recorderStarted) {
            stopSelf()
            return
        }
        stoppingRecording = true
        isRecording = false
        diag("STOP requested; recorderStarted=$recorderStarted tempBytes=${tempFile?.length() ?: -1L}")

        var stopOk = true
        if (recorderStarted) {
            runCatching { codecRecorder?.stopAndRelease() }.onFailure {
                stopOk = false
                lastError = "Screen recording produced no valid frames: ${it.message ?: it.javaClass.simpleName}"
                diagThrowable("ENCODER STOP FAILED", it)
            }.onSuccess {
                diag("Encoder stopped successfully; tempBytes=${tempFile?.length() ?: -1L}")
            }
        }
        recorderStarted = false
        codecRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }

        val file = tempFile
        var publishedUri: Uri? = null
        val validationOk = if (stopOk && file != null) validateRecording(file) else false
        diag("Validation result=$validationOk tempExists=${file?.exists() == true} tempBytes=${file?.length() ?: -1L}")
        if (stopOk && file != null && validationOk) {
            publishedUri = runCatching { publishRecording(file, currentName, currentStartedAt) }
                .onFailure { lastError = "Screen recording publish failed: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
            stopOk = publishedUri != null
            diag("Publish result=${publishedUri?.toString() ?: "FAILED"}")
        } else if (stopOk) {
            stopOk = false
            val bytes = file?.length() ?: 0L
            lastError = "Screen recording invalid or empty (${bytes} bytes). This device did not provide video frames."
            diag("INVALID RECORDING: $lastError")
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
        diag("FINAL stopOk=$stopOk lastError=${lastError.ifBlank { "<none>" }} lastUri=${lastUri.ifBlank { "<none>" }}")
        publishDiagnostics()
        currentName = ""
        currentUri = ""
        currentStartedAt = 0L
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stoppingRecording = false
        stopSelf()
    }

    private fun validateRecording(file: File): Boolean {
        if (!file.exists()) {
            diag("Validation: temp file missing")
            return false
        }
        if (file.length() < 64 * 1024L) {
            diag("Validation: file too small bytes=${file.length()}")
            return false
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                diag("Validation metadata durationMs=$duration video=${width}x${height} bytes=${file.length()}")
                duration >= 250L && width > 0 && height > 0
            } finally {
                retriever.release()
            }
        }.onFailure { diagThrowable("Validation metadata read failed", it) }.getOrDefault(false)
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
        diag("cleanupFailedStart()")
        isRecording = false
        recorderStarted = false
        stoppingRecording = false
        runCatching { codecRecorder?.abort() }
        codecRecorder = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        val oldProjection = projection
        projection = null
        runCatching { oldProjection?.stop() }
        runCatching { tempFile?.delete() }
        tempFile = null
        diag("FINAL startFailure lastError=${lastError.ifBlank { "<none>" }}")
        publishDiagnostics()
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
     * Use a conservative 540p-class portrait/landscape size. Some OEM AVC encoders advertise
     * larger sizes but MediaRecorder then starts without ever emitting frames. 540p-class output
     * is enough for report calibration and is broadly supported by hardware encoders.
     */
    private fun compatibleCaptureSize(rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        val safeW = rawWidth.coerceAtLeast(320)
        val safeH = rawHeight.coerceAtLeast(320)
        val longSide = maxOf(safeW, safeH).toFloat()
        val shortSide = minOf(safeW, safeH).toFloat()
        val scale = min(1f, min(960f / longSide, 540f / shortSide))
        fun align16(value: Float): Int = ((value.roundToInt().coerceAtLeast(320)) / 16 * 16).coerceAtLeast(320)
        return align16(safeW * scale) to align16(safeH * scale)
    }

    private class CodecRecorder(
        private val outputFile: File,
        width: Int,
        height: Int,
        bitrate: Int,
        private val logger: (String) -> Unit
    ) {
        private val codec: MediaCodec
        private val muxer: MediaMuxer
        val inputSurface: Surface
        @Volatile private var stopping = false
        @Volatile private var released = false
        @Volatile private var drainFailure: Throwable? = null
        private var trackIndex = -1
        private var muxerStarted = false
        private lateinit var drainThread: Thread
        private var outputBufferCount = 0L
        private var outputBytes = 0L
        private var keyFrameCount = 0L
        private var tryAgainCount = 0L
        private var firstPtsUs = -1L
        private var lastPtsUs = -1L
        private var encoderName = ""

        private fun createPreferredAvcEncoder(): MediaCodec {
            val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .filter { info ->
                    info.isEncoder && runCatching {
                        info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) }
                    }.getOrDefault(false)
                }
            // Prefer the AOSP/Google software encoder. Several OEM vendor AVC encoders accept a
            // MediaProjection surface but silently emit no frames, producing the familiar ~3 KB
            // MP4. For calibration video, a conservative software encode is more reliable and the
            // lower 540p-class resolution keeps CPU load reasonable.
            val preferred = infos.sortedBy { info ->
                val n = info.name.lowercase()
                when {
                    n.contains("c2.android") || n.contains("omx.google") -> 0
                    Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly -> 1
                    else -> 2
                }
            }
            var last: Throwable? = null
            for (info in preferred) {
                try {
                    encoderName = info.name
                    logger("Encoder selected=${info.name} software=${if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly else "unknown"} vendor=${if (Build.VERSION.SDK_INT >= 29) info.isVendor else "unknown"}")
                    return MediaCodec.createByCodecName(info.name)
                } catch (t: Throwable) {
                    last = t
                }
            }
            return runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                .onSuccess { encoderName = it.name; logger("Encoder selected by type=${it.name}") }
                .getOrElse { throw IllegalStateException("No AVC encoder available", last ?: it) }
        }

        init {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
            }
            codec = createPreferredAvcEncoder()
            logger("Encoder configure name=$encoderName size=${width}x${height} bitrate=$bitrate fps=20 iframe=1")
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = codec.createInputSurface()
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                logger("Encoder surface + muxer created output=${outputFile.absolutePath}")
            } catch (t: Throwable) {
                runCatching { codec.release() }
                throw t
            }
        }

        fun start() {
            codec.start()
            logger("MediaCodec.start OK name=$encoderName")
            drainThread = Thread({ drainLoop() }, "CellTrackerScreenEncoder").also { it.start() }
        }

        private fun drainLoop() {
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            tryAgainCount++
                            if (tryAgainCount == 1L || tryAgainCount % 500L == 0L) logger("Encoder no output yet; tryAgainCount=$tryAgainCount stopping=$stopping")
                            if (stopping) continue
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (muxerStarted) error("Encoder output format changed twice")
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                            logger("Output format changed; muxer started format=${codec.outputFormat}")
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null && info.size > 0 && muxerStarted) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, buffer, info)
                                outputBufferCount++
                                outputBytes += info.size.toLong()
                                if (firstPtsUs < 0L) firstPtsUs = info.presentationTimeUs
                                lastPtsUs = info.presentationTimeUs
                                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) keyFrameCount++
                                if (outputBufferCount == 1L || outputBufferCount % 100L == 0L) {
                                    logger("Encoded buffers=$outputBufferCount bytes=$outputBytes keyframes=$keyFrameCount ptsUs=$firstPtsUs..$lastPtsUs")
                                }
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(index, false)
                            if (eos) break
                        }
                    }
                }
            } catch (t: Throwable) {
                drainFailure = t
                logger("Drain loop failure=${t.javaClass.simpleName}: ${t.message}")
            } finally {
                logger("Drain loop exit buffers=$outputBufferCount bytes=$outputBytes keyframes=$keyFrameCount tryAgain=$tryAgainCount muxerStarted=$muxerStarted ptsUs=$firstPtsUs..$lastPtsUs")
            }
        }

        fun stopAndRelease() {
            if (released) return
            stopping = true
            runCatching { codec.signalEndOfInputStream() }.getOrElse { throw it }
            if (::drainThread.isInitialized) {
                drainThread.join(4000)
                logger("Drain join done alive=${drainThread.isAlive}")
            }
            val failure = drainFailure
            releaseInternal()
            if (failure != null) throw failure
            logger("stopAndRelease summary muxerStarted=$muxerStarted buffers=$outputBufferCount bytes=$outputBytes keyframes=$keyFrameCount ptsUs=$firstPtsUs..$lastPtsUs")
            if (!muxerStarted) error("Encoder produced no output format / no frames")
            if (outputBufferCount <= 0L || outputBytes <= 0L) error("Encoder produced output format but zero video samples")
        }

        fun abort() {
            if (released) return
            stopping = true
            releaseInternal()
        }

        private fun releaseInternal() {
            if (released) return
            released = true
            runCatching { inputSurface.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
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
        val line = String.format(java.util.Locale.US, "%+07dms [%s] %s", elapsed, Thread.currentThread().name, message)
        Log.i("CTScreenRec", line)
        synchronized(diagnosticLock) {
            runCatching { diagnosticFile?.appendText(line + "\n") }
        }
    }

    private fun diagThrowable(prefix: String, t: Throwable) {
        diag("$prefix: ${t.javaClass.name}: ${t.message}")
        synchronized(diagnosticLock) {
            runCatching {
                diagnosticFile?.appendText(t.stackTraceToString() + "\n")
            }
        }
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
            Log.i("CTScreenRec", "Diagnostic published: $lastDiagnosticPathHint")
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
