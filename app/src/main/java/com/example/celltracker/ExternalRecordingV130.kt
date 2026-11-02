package com.example.celltracker

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** System/OEM screen recordings. These carry no CellTracker first-frame clock anchor.
 * Import is explicitly marked ESTIMATED; the selected start is always editable and confirmed.
 */
object ExternalRecordingV130 {
    data class Candidate(val uri: Uri, val durationMs: Long, val suggestedStartMs: Long?,
                         val source: String, val metadataTimeMs: Long?)

    private fun parseEmbeddedDate(s: String?): Long? {
        if(s.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[.SSS]X",Locale.US)).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
    }
    fun parseLocalTime(s: String): Long? = runCatching {
        LocalDateTime.parse(s.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull()
    fun formatLocalTime(ms: Long): String = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(java.util.Date(ms))

    fun read(context: Context, uri: Uri, testStartMs: Long = 0, testEndMs: Long = 0): Candidate {
        val retriever = MediaMetadataRetriever()
        val (duration, embedded) = try {
            retriever.setDataSource(context,uri)
            Pair(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                parseEmbeddedDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)))
        } finally { retriever.release() }
        require(duration > 0L) { "Could not read video duration" }
        // DATE_ADDED / DATE_MODIFIED refer to media indexing and file writes, NOT a trusted capture start.
        // An embedded date can also mean modification time. Prefer matching a user-recorded test window.
        val start = when {
            embedded != null && testStartMs > 0 && abs(embedded-testStartMs) < 120000L -> embedded
            testStartMs > 0L && testEndMs >= testStartMs && abs((testEndMs-testStartMs)-duration) < 120000L -> testStartMs
            embedded != null -> embedded
            testEndMs > 0L -> testEndMs-duration
            else -> null
        }
        val source = when {
            start == null -> "NO_TIMESTAMP · user confirmation required"
            embedded != null && start == embedded -> "EMBEDDED_DATE · approximate, not frame-accurate"
            testStartMs > 0 && start == testStartMs -> "TEST_SESSION_START · approximate"
            else -> "TEST_SESSION_END_MINUS_DURATION · approximate"
        }
        return Candidate(uri,duration,start,source,embedded)
    }
    data class NearbyVideo(val uri: Uri, val name: String, val durationMs: Long,
                           val approximateStartMs: Long, val reason: String, val gapMs: Long)

    /** Search only when the user grants READ_MEDIA_VIDEO. Media dates are ranking hints, not anchors. */
    fun nearbyVideos(context: Context, testStartMs: Long, testEndMs: Long): List<NearbyVideo> {
        require(testStartMs > 0L && testEndMs >= testStartMs) { "Start and finish the test window first" }
        val store=MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val fields=arrayOf(MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,MediaStore.Video.Media.DATE_ADDED)
        val matches= mutableListOf<NearbyVideo>()
        context.contentResolver.query(store,fields,null,null,"${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            var inspected=0
            while(cursor.moveToNext() && inspected++<250) {
                val duration=cursor.getLong(2)
                if(duration <= 0L)continue
                val dateTaken=cursor.getLong(3)
                val modified=cursor.getLong(4)*1000L
                val added=cursor.getLong(5)*1000L
                val guesses=listOfNotNull(
                    if(dateTaken>0)Triple(dateTaken,"VIDEO_DATE_TAKEN (unverified)",abs(dateTaken-testStartMs)) else null,
                    if(modified>0)Triple(modified-duration,"DATE_MODIFIED minus duration (low confidence)",abs(modified-duration-testStartMs)) else null,
                    if(added>0)Triple(added-duration,"DATE_ADDED minus duration (low confidence)",abs(added-duration-testStartMs)) else null
                )
                val best=guesses.minByOrNull { it.third } ?: continue
                if(best.third>30*60000L) continue
                val id=cursor.getLong(0)
                matches+=NearbyVideo(android.content.ContentUris.withAppendedId(store,id),
                    cursor.getString(1).orEmpty(),duration,best.first,best.second,best.third)
            }
        }
        return matches.sortedBy { it.gapMs }.take(12)
    }

    fun save(context: Context, candidate: Candidate, confirmedStartMs: Long, kind: ManualStallKindV130,
             role: String, task: String, appVersion: String,
             operator: String, rat: String, band: String, pci: String, rsrp: String, sinr: String) {
        require(confirmedStartMs > 0 && candidate.durationMs > 0)
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(confirmedStartMs))
        val session = RecordingTimelineV130.Session(
            RecordingTimelineV130.Anchor(0L,confirmedStartMs),
            RecordingTimelineV130.Anchor(candidate.durationMs, confirmedStartMs+candidate.durationMs),
            candidate.durationMs, emptyList())
        RecordingTimelineV130.save(context,"External_${kind.name}_${confirmedStartMs}.mp4",candidate.uri, session,
            "${android.os.Environment.DIRECTORY_DOWNLOADS}/CellTracker/$date/ExternalTimeline", "EXTERNAL_VIDEO_ESTIMATED")
        context.getSharedPreferences("ct_recording_timeline",Context.MODE_PRIVATE).edit()
            .putString("reviewVideoUri", candidate.uri.toString())
            .putString("reviewKind",kind.name).putString("reviewRole",role).putString("reviewTask",task)
            .putString("reviewAppVersion",appVersion).putString("reviewOperator",operator)
            .putString("reviewRat",rat).putString("reviewBand",band).putString("reviewPci",pci)
            .putString("reviewRsrp",rsrp).putString("reviewSinr",sinr)
            .putString("reviewedEvents","[]").putString("lastReviewExportSignature", "")
            .putString("mappingProvenance","EXTERNAL_VIDEO_ESTIMATED|"+candidate.source)
            .apply()
    }
}
