package com.example.celltracker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds a system/OEM screen recording that overlaps a CellTracker test window.
 * This intentionally does not start/stop the OEM recorder; it only associates an
 * already-created system recording with the matching test report.
 */
object SystemScreenRecordingLinker {
    data class Match(
        val uri: String,
        val name: String,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val relativePath: String
    )

    private const val PREF = "system_screen_recording_links_v1"

    fun hasVideoAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED

    fun linked(context: Context, reportKey: String): Match? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val uri = p.getString("$reportKey.uri", null)?.takeIf { it.isNotBlank() } ?: return null
        return Match(
            uri = uri,
            name = p.getString("$reportKey.name", "System screen recording").orEmpty(),
            startMs = p.getLong("$reportKey.start", 0L),
            endMs = p.getLong("$reportKey.end", 0L),
            durationMs = p.getLong("$reportKey.duration", 0L),
            relativePath = p.getString("$reportKey.path", "").orEmpty()
        )
    }

    fun resolve(context: Context, reportKey: String, testStartMs: Long, testEndMs: Long): Match? {
        linked(context, reportKey)?.let { return it }
        if (!hasVideoAccess(context) || testStartMs <= 0L) return null
        val end = testEndMs.takeIf { it > testStartMs } ?: System.currentTimeMillis()
        val found = findBest(context, testStartMs, end) ?: return null
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("$reportKey.uri", found.uri)
            .putString("$reportKey.name", found.name)
            .putLong("$reportKey.start", found.startMs)
            .putLong("$reportKey.end", found.endMs)
            .putLong("$reportKey.duration", found.durationMs)
            .putString("$reportKey.path", found.relativePath)
            .apply()
        return found
    }

    fun mediaAddedMs(context: Context, uriString: String): Long {
        return runCatching {
            context.contentResolver.query(
                Uri.parse(uriString),
                arrayOf(MediaStore.MediaColumns.DATE_ADDED), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) * 1000L else 0L } ?: 0L
        }.getOrDefault(0L)
    }

    private fun findBest(context: Context, testStartMs: Long, testEndMs: Long): Match? {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE
        )
        val candidates = mutableListOf<Pair<Match, Double>>()
        val windowStart = testStartMs - 15_000L
        val windowEnd = testEndMs + 120_000L
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Video.Media.DATE_ADDED} >= ?",
                arrayOf(((testStartMs - 10 * 60_000L) / 1000L).toString()),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idI = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val pathI = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                val addedI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val modifiedI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val takenI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val durationI = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeI = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameI).orEmpty()
                    val rel = c.getString(pathI).orEmpty()
                    val hay = "$name $rel".lowercase()
                    if (hay.contains("celltracker")) continue
                    if (!(hay.contains("screen") || hay.contains("record") || hay.contains("录屏") || hay.contains("录制"))) continue
                    val size = c.getLong(sizeI)
                    if (size < 100_000L) continue
                    val duration = c.getLong(durationI).coerceAtLeast(0L)
                    if (duration < 1_000L) continue
                    val added = c.getLong(addedI) * 1000L
                    val modified = c.getLong(modifiedI) * 1000L
                    val taken = c.getLong(takenI)
                    val possible = mutableListOf<Pair<Long, Long>>()
                    if (taken > 0L) possible += taken to (taken + duration)
                    if (added > 0L) possible += added to (added + duration)
                    if (modified > 0L) possible += (modified - duration).coerceAtLeast(0L) to modified
                    var bestStart = 0L
                    var bestEnd = 0L
                    var bestScore = Double.NEGATIVE_INFINITY
                    possible.forEach { (s, e) ->
                        val overlap = (min(e, windowEnd) - max(s, windowStart)).coerceAtLeast(0L)
                        if (overlap <= 0L) return@forEach
                        val testDuration = (testEndMs - testStartMs).coerceAtLeast(1L)
                        val coverage = overlap.toDouble() / testDuration.toDouble()
                        val startDelta = abs(s - testStartMs).toDouble()
                        val endDelta = abs(e - testEndMs).toDouble()
                        val score = coverage * 1000.0 - startDelta / 10_000.0 - endDelta / 30_000.0
                        if (score > bestScore) {
                            bestScore = score
                            bestStart = s
                            bestEnd = e
                        }
                    }
                    if (bestScore.isFinite()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(idI))
                        candidates += Match(uri.toString(), name, bestStart, bestEnd, duration, rel) to bestScore
                    }
                }
            }
        }
        return candidates.maxByOrNull { it.second }?.first
    }
}
