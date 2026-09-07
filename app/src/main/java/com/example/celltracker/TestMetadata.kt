package com.example.celltracker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TestMetadata(
    val scenario: String = "Static",
    val operator: String = "Zong",
    val rat: String = "5G",
    val task: String = "YouTube Video Loading",
    val location: String = ""
) {
    fun displayName(includeTime: Boolean = true, now: Long = System.currentTimeMillis()): String {
        val parts = listOf(scenario, operator, rat, task, location).map { it.trim() }.filter { it.isNotBlank() }
        val base = parts.joinToString(" ").replace(Regex("[\\\\/:*?\"<>|]+"), "-").replace(Regex("\\s+"), " ").trim()
        return if (includeTime) "$base ${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))}" else base
    }
}

class TestMetadataRepository(context: Context) {
    private val p = context.getSharedPreferences("celltracker_test_metadata", Context.MODE_PRIVATE)
    fun scenarios() = list("scenarios", listOf("Static", "Mobility"))
    fun operators() = list("operators", listOf("Zong", "Jazz", "Telenor", "Ufone"))
    fun rats() = list("rats", listOf("5G", "4G"))
    fun tasks() = list("tasks", listOf("VoLTE Call Setup", "VoLTE Long Call", "WhatsApp Image Send", "YouTube Video Loading"))
    fun saveList(key: String, values: List<String>) { p.edit().putString(key, values.distinct().joinToString("\u001F")).apply() }
    fun last(taskDefault: String = "YouTube Video Loading") = TestMetadata(
        p.getString("last_scenario", scenarios().first()) ?: scenarios().first(),
        p.getString("last_operator", operators().first()) ?: operators().first(),
        p.getString("last_rat", rats().first()) ?: rats().first(),
        p.getString("last_task", taskDefault) ?: taskDefault,
        p.getString("last_location", "") ?: ""
    )
    fun saveLast(m: TestMetadata) { p.edit().putString("last_scenario",m.scenario).putString("last_operator",m.operator).putString("last_rat",m.rat).putString("last_task",m.task).putString("last_location",m.location).apply() }
    private fun list(key:String, defaults:List<String>):List<String> = p.getString(key,null)?.split("\u001F")?.filter{it.isNotBlank()}?.ifEmpty{defaults} ?: defaults
}
