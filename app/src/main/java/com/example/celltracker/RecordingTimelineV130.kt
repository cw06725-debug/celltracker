package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Best-effort recording/video-clock correlation. MediaRecorder doesn't expose the first frame
 * wall-clock timestamp, so do NOT label this frame-accurate. All samples use monotonic time
 * as well as wall clock. This lets a future codec PTS anchor improve the mapping without
 * changing the CSV format. In-app playback uses the exact same mapper as the exported CSV.
 */
object RecordingTimelineV130 {
    data class Anchor(val elapsedMs: Long, val wallMs: Long)
    data class Session(val start: Anchor, val end: Anchor, val videoDurationMs: Long, val anchors: List<Anchor>) {
        val durationElapsedMs: Long get() = (end.elapsedMs - start.elapsedMs).coerceAtLeast(1L)
        val durationDiscrepancyMs: Long get() = abs(videoDurationMs - durationElapsedMs)
        fun wallAt(videoPositionMs: Long): Long {
            val videoLength = videoDurationMs.coerceAtLeast(1L)
            val elapsedAt = start.elapsedMs + (videoPositionMs.coerceIn(0L, videoLength).toDouble() * durationElapsedMs / videoLength).toLong()
            val available = (listOf(start) + anchors + end).distinctBy { it.elapsedMs }.sortedBy { it.elapsedMs }
            val index = available.indexOfLast { it.elapsedMs <= elapsedAt }
            if (index < 0) return start.wallMs + elapsedAt - start.elapsedMs
            // Nearest sampled clock anchors avoid accumulating wall clock drift. If the wall
            // clock jumps while recording, the CSV follows the actual observed system time.
            val anchor = available[index]
            return anchor.wallMs + (elapsedAt - anchor.elapsedMs)
        }
    }

    fun clockFormat(epoch: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS XXX", Locale.US).format(Date(epoch))
    fun subtitleTime(ms: Long): String {
        val value = ms.coerceAtLeast(0L)
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", value / 3600000L,
            (value / 60000L) % 60L, (value / 1000L) % 60L, value % 1000L)
    }
    fun csv(session: Session, videoName: String, mappingType: String = "ESTIMATED"): String = buildString {
        // Header first, so Excel/WPS can import it directly without skipping metadata rows.
        appendLine("VideoFile,VideoPosition,VideoOffsetMs,LocalTime,EpochMs,MappingType,DurationMismatchMs")
        val videoField = "\"" + videoName.replace("\"", "\"\"") + "\""
        val length = session.videoDurationMs.coerceAtLeast(0L)
        for (offset in 0L..(length / 1000L)) {
            val ms = offset * 1000L
            val time = session.wallAt(ms)
            append(videoField).append(',').append(subtitleTime(ms).replace(',', '.')).append(',').append(ms).append(',')
            append('"').append(clockFormat(time)).append('"').append(',').append(time)
            append(",").append(mappingType).append(",").append(session.durationDiscrepancyMs).appendLine()
        }
        if (length % 1000L != 0L) {
            val time = session.wallAt(length)
            append(videoField).append(',').append(subtitleTime(length).replace(',', '.')).append(',').append(length).append(',')
            append('"').append(clockFormat(time)).append('"').append(',').append(time)
            append(",").append(mappingType).append(",").append(session.durationDiscrepancyMs).appendLine()
        }
    }
    fun srt(session: Session): String = buildString {
        val total = session.videoDurationMs.coerceAtLeast(0L)
        var sec = 0L
        var seq = 1
        while (sec < total) {
            val end = (sec + 1000L).coerceAtMost(total)
            appendLine(seq++)
            appendLine("${subtitleTime(sec)} --> ${subtitleTime(end)}")
            appendLine(clockFormat(session.wallAt(sec)))
            appendLine()
            sec = end
        }
    }
    fun save(context: Context, videoName: String, videoUri: Uri, session: Session, directory: String, mappingType: String = "ESTIMATED"): Pair<Uri, Uri> {
        require(session.videoDurationMs > 0) { "Video duration invalid" }
        val basename = videoName.removeSuffix(".mp4")
        fun publish(name: String, mime: String, body: String): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.Downloads.RELATIVE_PATH, directory)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Failed to create $name")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    ?: error("Failed to open $name")
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw e
            }
        }
        val csv = publish("${basename}_TimeMap.csv", "text/csv", csv(session, videoName, mappingType))
        val srt = try { publish("$basename.srt", "application/x-subrip", srt(session)) }
            catch (e: Exception) { context.contentResolver.delete(csv, null, null); throw e }
        val js = JSONArray()
        session.anchors.forEach { js.put(JSONObject().put("elapsed", it.elapsedMs).put("wall", it.wallMs)) }
        context.getSharedPreferences("ct_recording_timeline", Context.MODE_PRIVATE).edit()
            .putString("uri", videoUri.toString()).putString("name", videoName)
            .putLong("startElapsed", session.start.elapsedMs).putLong("startWall", session.start.wallMs)
            .putLong("stopElapsed", session.end.elapsedMs).putLong("stopWall", session.end.wallMs)
            .putLong("duration", session.videoDurationMs).putString("anchors", js.toString())
            .putString("csvUri", csv.toString()).putString("srtUri", srt.toString())
            .putString("mappingProvenance", if(mappingType.startsWith("EXTERNAL")) mappingType else "")
            .apply()
        return csv to srt
    }
    fun last(context: Context): Pair<Uri, Session>? {
        val prefs = context.getSharedPreferences("ct_recording_timeline", Context.MODE_PRIVATE)
        val uri = prefs.getString("uri", "")?.takeIf { it.isNotBlank() } ?: return null
        val start = Anchor(prefs.getLong("startElapsed", -1L), prefs.getLong("startWall", -1L))
        val end = Anchor(prefs.getLong("stopElapsed", -1L), prefs.getLong("stopWall", -1L))
        val duration = prefs.getLong("duration", -1L)
        if (start.elapsedMs < 0 || start.wallMs < 0 || end.elapsedMs <= start.elapsedMs || duration <= 0L) return null
        val anchors = runCatching {
            JSONArray(prefs.getString("anchors", "[]")).let { json ->
                List(json.length()) { i -> json.getJSONObject(i).let { Anchor(it.getLong("elapsed"), it.getLong("wall")) } }
            }
        }.getOrDefault(emptyList())
        return Uri.parse(uri) to Session(start, end, duration, anchors)
    }
}
