package com.example.celltracker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

/** User-opt-in Speedtest text detection. Never performs clicks or reads non-Speedtest packages. */
data class SpeedtestUiCandidateV130(val download: Double, val upload: Double, val at: Long)

object SpeedtestUiParserV130 {
    private val numeric = Regex("(?i)\\b(download|upload)\\b\\s*[:=]?\\s*([0-9]{1,5}(?:[.,][0-9]{1,3})?)\\s*(G|M|K)bps\\b")
    fun parse(texts: List<String>, now: Long = System.currentTimeMillis()): SpeedtestUiCandidateV130? {
        val text = texts.take(100).joinToString(" ").replace(Regex("\\s+"), " ")
        val values = mutableMapOf<String, Double>()
        for (match in numeric.findAll(text)) {
            val value = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            val factor = when (match.groupValues[3].uppercase()) { "G" -> 1000.0; "K" -> 0.001; else -> 1.0 }
            if (value < 0 || value > 100000) continue
            values[match.groupValues[1].lowercase()] = value * factor
        }
        val d = values["download"] ?: return null
        val u = values["upload"] ?: return null
        return SpeedtestUiCandidateV130(d,u,now)
    }
}

object SpeedtestCaptureStoreV130 {
    @Volatile var armed: Boolean = false
    val result = MutableStateFlow<SpeedtestUiCandidateV130?>(null)
    fun arm() { armed = true; result.value = null }
    fun nextRound() { result.value = null }
    fun stop() { armed = false; result.value = null }
}

class SpeedtestResultsAccessibilityV130 : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!SpeedtestCaptureStoreV130.armed) return
        if (event?.packageName?.toString() !in SpeedtestReportV130.packages) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() !in SpeedtestReportV130.packages) return
        val texts = ArrayList<String>(80)
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || texts.size >= 100) return
            node.text?.toString()?.trim()?.takeIf { it.length in 1..70 }?.let { texts.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.length in 1..70 && it !in texts }?.let { texts.add(it) }
            for (index in 0 until minOf(node.childCount, 60)) walk(node.getChild(index), depth + 1)
        }
        walk(root, 0)
        // Store only two numeric speed values; do not save raw third-party screen text.
        SpeedtestUiParserV130.parse(texts)?.let { SpeedtestCaptureStoreV130.result.value = it }
    }
    override fun onInterrupt() { }
}
