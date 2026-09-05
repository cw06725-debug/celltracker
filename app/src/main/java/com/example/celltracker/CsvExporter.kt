package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

enum class CsvExportMode { SEPARATE_BY_SIM, COMBINED }

object CsvExporter {
    fun exportLatest(context: Context, sourcePath: String, mode: CsvExportMode): String {
        val source = File(sourcePath)
        require(source.exists()) { "Recording file not found" }
        return when (mode) {
            CsvExportMode.COMBINED -> {
                saveToDownloads(context, source.name, source.readText())
                "Saved combined CSV to Downloads/CellTracker/${source.name}"
            }
            CsvExportMode.SEPARATE_BY_SIM -> exportSeparate(context, source)
        }
    }

    private fun exportSeparate(context: Context, source: File): String {
        val lines = source.readLines()
        require(lines.isNotEmpty()) { "Recording file is empty" }
        val header = lines.first()
        val headerFields = parseCsvLine(header)
        val slotIndex = headerFields.indexOf("sim_slot")
        val operatorIndex = headerFields.indexOf("operator")
        require(slotIndex >= 0) { "CSV does not contain sim_slot" }

        val grouped = linkedMapOf<String, MutableList<String>>()
        val operators = mutableMapOf<String, String>()
        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val fields = parseCsvLine(line)
            if (fields.size <= slotIndex) return@forEach
            val slot = fields[slotIndex].ifBlank { "unknown" }
            grouped.getOrPut(slot) { mutableListOf() }.add(line)
            val op = fields.getOrNull(operatorIndex)?.ifBlank { null }
            if (op != null) operators[slot] = op
        }
        require(grouped.isNotEmpty()) { "No samples found" }

        val base = source.nameWithoutExtension
        val names = grouped.map { (slot, rows) ->
            val operator = sanitize(operators[slot] ?: "SIM$slot")
            val name = "${base}_SIM${sanitize(slot)}_${operator}.csv"
            val content = buildString {
                appendLine(header)
                rows.forEach { appendLine(it) }
            }
            saveToDownloads(context, name, content)
            name
        }
        return "Saved ${names.size} SIM CSV files to Downloads/CellTracker"
    }

    private fun saveToDownloads(context: Context, displayName: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CellTracker")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create exported file")
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
            ?: error("Unable to open export destination")
    }

    private fun sanitize(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(32)

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"'); i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { result += current.toString(); current.clear() }
                else -> current.append(ch)
            }
            i++
        }
        result += current.toString()
        return result
    }
}
