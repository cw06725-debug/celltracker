package com.example.celltracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared test grouping: a test item may contribute several metric IDs. */
data class ExperienceTestItemV130(val id: String, val label: String, val metrics: Set<String>)

object ExperienceTestCatalogV130 {
    val all = listOf(
        ExperienceTestItemV130("MFT_PING", "MFT Ping Test", setOf("MFT_PING_AVG_MS", "MFT_PING_SUCCESS")),
        ExperienceTestItemV130("MFT_VIDEO", "MFT Short Video Loading", setOf("MFT_SHORT_VIDEO_AVG_MS")),
        ExperienceTestItemV130("SPEEDTEST", "Speedtest DL / UL", setOf("SPEEDTEST_DL_MBPS", "SPEEDTEST_UL_MBPS")),
        ExperienceTestItemV130("PING", "CellTracker Ping Test", setOf("PING_AVG_MS", "PING_SUCCESS")),
        ExperienceTestItemV130("TIKTOK_UPLOAD", "TikTok Video Upload", setOf("TIKTOK_UPLOAD_AVG_MS", "TIKTOK_UPLOAD_MAX_MS")),
        ExperienceTestItemV130("TIKTOK_BROWSE", "TikTok Video Browsing", setOf("TIKTOK_STALLS", "TIKTOK_STALL_SEC")),
        ExperienceTestItemV130("YOUTUBE", "YouTube Video Loading", setOf("YOUTUBE_AVG_MS")),
        ExperienceTestItemV130("WHATSAPP_SEND", "WhatsApp Image Send", setOf("WHATSAPP_SEND_AVG_MS")),
        ExperienceTestItemV130("WHATSAPP_CALL", "WhatsApp Call", setOf("WHATSAPP_CALL_SUCCESS")),
        ExperienceTestItemV130("VOLTE", "VoLTE Call", setOf("VOLTE_SUCCESS")),
        ExperienceTestItemV130("VOLTE_LONG", "VoLTE Long Duration Call", setOf("VOLTE_LONG_SUCCESS")),
        ExperienceTestItemV130("CALL_SETUP", "CellTracker Call Setup", setOf("CALL_SUCCESS", "CALL_SETUP_AVG_MS")),
        ExperienceTestItemV130("RECOVERY", "Weak Coverage Recovery", setOf("RECOVERY_MS"))
    )
    fun forMetric(metricId: String): ExperienceTestItemV130? = all.firstOrNull { metricId in it.metrics }
}

data class ExperienceRunGroupV130(
    val key: String,
    val role: String,
    val date: String,
    val model: String,
    val deviceId: String,
    val originSession: String,
    val source: String,
    val timeLabel: String,
    val testItems: List<String>,
    val rows: List<ExperienceObservation>
)

/** Report-selection is independent of test execution and the Bluetooth call-control link. */
object ExperienceReportSelectionV130 {
    fun candidates(rows: List<ExperienceObservation>, from: String, to: String, selectedTestIds: Set<String>): List<ExperienceObservation> {
        if (from > to || selectedTestIds.isEmpty()) return emptyList()
        val allowed = ExperienceTestCatalogV130.all.filter { it.id in selectedTestIds }.flatMap { it.metrics }.toSet()
        return rows.filter { it.date.length == 10 && it.date >= from && it.date <= to && it.metricId in allowed }
    }

    fun runKey(row: ExperienceObservation): String =
        listOf(row.role, row.date, row.session, row.deviceId, row.source, row.runId).joinToString("\u001f")

    fun groupRuns(rows: List<ExperienceObservation>): List<ExperienceRunGroupV130> = rows
        .groupBy(::runKey)
        .map { (key, grouped) ->
            val first = grouped.first()
            ExperienceRunGroupV130(
                key, first.role, first.date, first.deviceModel, first.deviceId,
                first.session, first.source,
                SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(first.recordedAt)),
                grouped.mapNotNull { ExperienceTestCatalogV130.forMetric(it.metricId)?.label }.distinct(), grouped
            )
        }.sortedWith(compareByDescending<ExperienceRunGroupV130> { it.date }
            .thenBy { it.role }.thenByDescending { it.rows.maxOfOrNull(ExperienceObservation::recordedAt) ?: 0L })

    /** Never silently collapse retries or two phones into one DUT/REF comparison slot. */
    fun conflict(selected: List<ExperienceObservation>): String? {
        if (selected.isEmpty()) return "Select at least one report"
        if (selected.any { it.role !in ExperienceAnalyzer.roles }) return "Unknown DUT/REF role in selected reports"
        val duplicate = selected.groupBy { it.role to it.metricId }
            .entries.firstOrNull { (_, v) -> v.size > 1 }
        if (duplicate != null) return "Multiple runs for ${duplicate.key.first} / ${duplicate.key.second}; select one report per role and metric before analysis."
        val mixedIdentity = selected.groupBy { it.role }.entries.firstOrNull { (_, v) ->
            val knownIds = v.map { it.deviceId }.filter { it.isNotBlank() }.distinct()
            val knownModels = v.map { it.deviceModel }.filter { it.isNotBlank() }.distinct()
            knownIds.size > 1 || knownModels.size > 1
        }
        if (mixedIdentity != null) return "${mixedIdentity.key} matches multiple device identities/models. Choose one device for each role."
        val mixedDate = selected.map { it.date }.distinct()
        if (mixedDate.size > 1) return "The selection spans multiple dates. Generate separate daily reports for each date."
        // Different test items may legitimately use different operators or RATs in a day.
        // The comparison engine must mark non-matching DUT/REF conditions N/A, not merge them.
        return null
    }
}
