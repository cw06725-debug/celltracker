package com.example.celltracker

import kotlin.math.abs
import java.util.Locale

/** Pure, platform-independent comparison rules. No implicit network/root-cause claims. */
enum class ExperienceDirection { LOWER_IS_BETTER, HIGHER_IS_BETTER }
enum class ExperienceDeltaUnit { PERCENT, ABSOLUTE, PERCENT_POINTS }
enum class ExperienceVerdict { PASS, OBSERVE, FAIL, NA }

data class ExperienceMetric(
    val id: String, val label: String, val unit: String,
    val direction: ExperienceDirection, val deltaUnit: ExperienceDeltaUnit
)

object ExperienceMetrics {
    val all = listOf(
        ExperienceMetric("SPEEDTEST_DL_MBPS", "Speedtest download", "Mbps", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("SPEEDTEST_UL_MBPS", "Speedtest upload", "Mbps", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("PING_AVG_MS", "Ping average latency", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("PING_SUCCESS", "Ping success rate", "%", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT_POINTS),
        ExperienceMetric("YOUTUBE_AVG_MS", "YouTube average loading", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("TIKTOK_UPLOAD_AVG_MS", "TikTok average upload", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("TIKTOK_UPLOAD_MAX_MS", "TikTok maximum upload", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("TIKTOK_STALLS", "TikTok stall count", "count", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.ABSOLUTE),
        ExperienceMetric("TIKTOK_STALL_SEC", "TikTok total stall duration", "s", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.ABSOLUTE),
        ExperienceMetric("WHATSAPP_SEND_AVG_MS", "WhatsApp image send average", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("CALL_SUCCESS", "CellTracker Call Setup success", "%", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT_POINTS),
        ExperienceMetric("VOLTE_SUCCESS", "VoLTE call setup success", "%", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT_POINTS),
        ExperienceMetric("WHATSAPP_CALL_SUCCESS", "WhatsApp call setup success", "%", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT_POINTS),
        ExperienceMetric("VOLTE_LONG_SUCCESS", "VoLTE long call completion", "%", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT_POINTS),
        ExperienceMetric("CALL_SETUP_AVG_MS", "Call setup latency", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("MFT_PING_AVG_MS", "MFT Ping mean latency", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("MFT_SHORT_VIDEO_AVG_MS", "MFT short-video loading", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT),
        ExperienceMetric("MFT_PING_SUCCESS", "MFT Ping success", "%", ExperienceDirection.HIGHER_IS_BETTER, ExperienceDeltaUnit.PERCENT_POINTS),
        ExperienceMetric("RECOVERY_MS", "Network recovery time", "ms", ExperienceDirection.LOWER_IS_BETTER, ExperienceDeltaUnit.PERCENT)
    )
    fun find(id: String) = all.firstOrNull { it.id == id }
}

data class ExperienceRule(
    val metricId: String, val enabled: Boolean = false,
    val observeAt: Double = 5.0, val failAt: Double = 10.0,
    val minAttempts: Int = 1
)

data class ExperienceObservation(
    val id: String, val session: String, val date: String, val scenario: String,
    val operator: String, val rat: String, val role: String,
    val metricId: String, val value: Double, val attempts: Int,
    val source: String, val dataOrigin: String, val recordedAt: Long,
    val band: String = "", val pci: String = "", val rsrp: String = "", val sinr: String = "",
    val deviceModel: String = "", val deviceId: String = "", val runId: String = ""
)

data class ExperienceDecision(
    val metricId: String, val dutRole: String, val dutValue: Double?, val refValue: Double?,
    val difference: Double?, val differenceUnit: ExperienceDeltaUnit,
    val automated: ExperienceVerdict, val reason: String,
    val evidenceIds: List<String>, val ruleSnapshot: ExperienceRule?,
    val finalVerdict: ExperienceVerdict, val overrideReason: String = "", val bugId: String = ""
)

object ExperienceAnalyzer {
    val roles = listOf("DUT1", "DUT2", "REF1", "REF2")
    fun compare(
        observations: List<ExperienceObservation>, rules: List<ExperienceRule>,
        session: String, overrides: Map<String, Triple<ExperienceVerdict, String, String>> = emptyMap()
    ): List<ExperienceDecision> {
        val selected = observations.filter { it.session == session && it.role in roles }
        val out = mutableListOf<ExperienceDecision>()
        for (metric in ExperienceMetrics.all) for (dutRole in listOf("DUT1", "DUT2")) {
            val rule = rules.firstOrNull { it.metricId == metric.id }
            val dut = selected.filter { it.role == dutRole && it.metricId == metric.id }.maxByOrNull { it.recordedAt }
            val refs = selected.filter { it.role.startsWith("REF") && it.metricId == metric.id }
                .groupBy { it.role }.values.mapNotNull { it.maxByOrNull { r -> r.recordedAt } }
            var verdict = ExperienceVerdict.NA
            var gap: Double? = null
            var refValue: Double? = null
            var reason = "DUT or REF measurement missing"
            val validRefs = if (dut == null) emptyList() else refs.filter { r ->
                r.date == dut.date && r.scenario.equals(dut.scenario, true) &&
                    r.operator.equals(dut.operator, true) && r.rat.equals(dut.rat, true) &&
                    r.date.isNotBlank() && r.scenario.isNotBlank() && r.operator.isNotBlank() && r.rat.isNotBlank()
            }
            if (dut != null && validRefs.isNotEmpty()) {
                refValue = validRefs.map { it.value }.average()
                val worse = if (metric.direction == ExperienceDirection.LOWER_IS_BETTER)
                    dut.value - refValue else refValue - dut.value
                gap = when (metric.deltaUnit) {
                    ExperienceDeltaUnit.PERCENT -> if (abs(refValue) > 0.0000001) 100 * worse / abs(refValue) else null
                    ExperienceDeltaUnit.ABSOLUTE, ExperienceDeltaUnit.PERCENT_POINTS -> worse
                }
                val min = rule?.minAttempts?.coerceAtLeast(1) ?: 1
                when {
                    dut.dataOrigin.contains("RAT_DECLARED") || validRefs.any{it.dataOrigin.contains("RAT_DECLARED")} -> {
                        verdict = ExperienceVerdict.OBSERVE
                        reason = "RAT supplied from setup rather than verified network snapshot; confirm comparable RAT before FAIL"
                    }
                    dut.attempts < min || validRefs.any { it.attempts < min } -> {
                        verdict = ExperienceVerdict.OBSERVE
                        reason = "Insufficient attempts for rule (minimum $min)"
                    }
                    gap == null -> {
                        reason = "Relative difference undefined because REF is zero"
                    }
                    rule == null || !rule.enabled -> {
                        verdict = ExperienceVerdict.OBSERVE
                        reason = "No enabled, approved threshold; review manually"
                    }
                    rule.failAt < rule.observeAt || rule.observeAt < 0.0 || !rule.failAt.isFinite() || !rule.observeAt.isFinite() -> {
                        reason = "Threshold configuration invalid"
                    }
                    gap >= rule.failAt -> {
                        verdict = ExperienceVerdict.FAIL
                        reason = "Worse than REF mean by ${"%.2f".format(Locale.US, gap)} ${metric.deltaUnit}; FAIL threshold ${rule.failAt}"
                    }
                    gap >= rule.observeAt -> {
                        verdict = ExperienceVerdict.OBSERVE
                        reason = "Worse than REF mean by ${"%.2f".format(Locale.US, gap)} ${metric.deltaUnit}; OBSERVE threshold ${rule.observeAt}"
                    }
                    else -> {
                        verdict = ExperienceVerdict.PASS
                        reason = "Difference below configured OBSERVE threshold; reference is mean of comparable REF devices"
                    }
                }
            } else if (dut != null && refs.isNotEmpty()) reason = "DUT / REF operator, RAT, date or scenario not comparable"
            val key = "$session|${metric.id}|$dutRole"
            val override = overrides[key]?.takeIf { it.second.isNotBlank() }
            out += ExperienceDecision(metric.id, dutRole, dut?.value, refValue, gap, metric.deltaUnit,
                verdict, reason, (listOfNotNull(dut?.id) + validRefs.map { it.id }), rule,
                override?.first ?: verdict, override?.second.orEmpty(), override?.third.orEmpty())
        }
        return out
    }
}

/** Preliminary radio context; never promotes correlation to a confirmed cause. */
object ExperienceNetworkHintsV130 {
    fun describe(decision: ExperienceDecision, measurements: List<ExperienceObservation>): String {
        val involved=decision.evidenceIds.mapNotNull { id->measurements.firstOrNull{it.id==id} }
        val dut=involved.firstOrNull{it.role==decision.dutRole} ?: return "No network snapshot for DUT"
        val refs=involved.filter{it.role.startsWith("REF")}
        if(refs.isEmpty())return "No matched REF network snapshot"
        val notes=mutableListOf<String>()
        for(ref in refs) {
            if(dut.band.isNotBlank() && ref.band.isNotBlank() && dut.band!=ref.band)
                notes+="${ref.role} Band differs: DUT ${dut.band}, REF ${ref.band}"
            if(dut.pci.isNotBlank() && ref.pci.isNotBlank() && dut.pci!=ref.pci)
                notes+="${ref.role} PCI differs: DUT ${dut.pci}, REF ${ref.pci}"
            val dRsrp=dut.rsrp.toDoubleOrNull();val rRsrp=ref.rsrp.toDoubleOrNull()
            if(dRsrp!=null&&rRsrp!=null)notes+="${ref.role} RSRP delta DUT-REF = ${"%.1f".format(Locale.US,dRsrp-rRsrp)} dB"
            val dSinr=dut.sinr.toDoubleOrNull();val rSinr=ref.sinr.toDoubleOrNull()
            if(dSinr!=null&&rSinr!=null)notes+="${ref.role} SINR delta DUT-REF = ${"%.1f".format(Locale.US,dSinr-rSinr)} dB"
        }
        if(notes.isEmpty())return "Radio evidence unavailable or identical fields; no radio conclusion"
        return notes.distinct().joinToString("; ")+". Context only; compare event-time samples before modem/RF diagnosis."
    }
}
