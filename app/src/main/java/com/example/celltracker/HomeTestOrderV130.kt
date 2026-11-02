package com.example.celltracker

import android.content.Context

/** Independent persistent order for the Home test launcher; never changes test/report IDs. */
object HomeTestOrderV130 {
    const val PREF = "celltracker_home_layout_v130"
    const val ORDER = "ordered_test_ids"
    val defaults = listOf("tiktok_upload", "tiktok_lag", "youtube", "whatsapp", "ping", "call",
        "network", "speedtest", "device_logs", "live", "gaming", "mtk_logger", "qti_logger")

    fun normalized(saved: List<String>, available: List<String>): List<String> {
        val unique = available.distinct()
        return (saved.filter { it in unique } + unique).distinct()
    }
    fun load(context: Context): List<String> = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString(ORDER, "").orEmpty().split(',').filter { it.isNotBlank() }.let { normalized(it, defaults) }
    fun persist(context: Context, ids: List<String>) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString(ORDER, normalized(ids, defaults).joinToString(",")).apply()
    /** Move an item to an index; stable IDs prevent a card from activating the wrong test. */
    fun moved(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices || to !in ids.indices || from == to) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }
}
