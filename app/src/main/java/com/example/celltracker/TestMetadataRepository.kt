package com.example.celltracker

import android.content.Context

data class TestMetadata(
    val scenario: String = "Mobility",
    val operator: String = "Zong",
    val rat: String = "5G",
    val task: String = "YouTube Video Loading",
    val location: String = ""
) {
    fun displayName(): String = listOf(scenario, operator, rat, task, location)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("[\\\\/:*?\"<>|]+"), "-")
        .replace(Regex("\\s+"), " ")
        .trim()
}

data class TestMetadataOptions(
    val scenarios: List<String> = listOf("Static", "Mobility"),
    val operators: List<String> = listOf("Zong", "Jazz", "Telenor", "Ufone"),
    val rats: List<String> = listOf("5G", "4G"),
    val tasks: List<String> = listOf(
        "VoLTE Call Setup",
        "VoLTE Long Call",
        "WhatsApp Image Send",
        "YouTube Video Loading"
    )
)

class TestMetadataRepository(private val context: Context) {
    private val p = context.getSharedPreferences("celltracker_test_metadata", Context.MODE_PRIVATE)

    private fun read(key: String, defaults: List<String>): List<String> =
        p.getString(key, null)
            ?.split("\u001f")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: defaults

    fun options(): TestMetadataOptions {
        val d = TestMetadataOptions()
        return TestMetadataOptions(
            scenarios = read("scenarios", d.scenarios),
            operators = read("operators", d.operators),
            rats = read("rats", d.rats),
            tasks = read("tasks", d.tasks)
        )
    }

    fun saveOptions(o: TestMetadataOptions) {
        p.edit()
            .putString("scenarios", o.scenarios.joinToString("\u001f"))
            .putString("operators", o.operators.joinToString("\u001f"))
            .putString("rats", o.rats.joinToString("\u001f"))
            .putString("tasks", o.tasks.joinToString("\u001f"))
            .apply()
    }

    fun last(): TestMetadata {
        val o = options()
        return TestMetadata(
            scenario = p.getString("scenario", o.scenarios.first()) ?: o.scenarios.first(),
            operator = p.getString("operator", o.operators.first()) ?: o.operators.first(),
            rat = p.getString("rat", o.rats.first()) ?: o.rats.first(),
            task = p.getString("task", "YouTube Video Loading") ?: "YouTube Video Loading",
            location = p.getString("location", "") ?: ""
        )
    }

    fun saveLast(m: TestMetadata) {
        p.edit()
            .putString("scenario", m.scenario)
            .putString("operator", m.operator)
            .putString("rat", m.rat)
            .putString("task", m.task)
            .putString("location", m.location)
            .apply()
    }
}
