package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


enum class CsvExportMode { SEPARATE_BY_SIM, COMBINED }

data class ExportResult(
    val message: String,
    val exportedFileUris: List<String>,
    val summaryUri: String? = null,
    val summaryName: String? = null
) {
    val primaryFileUri: String? get() = summaryUri ?: exportedFileUris.firstOrNull()
    val primaryMimeType: String get() = if (summaryUri != null) "text/html" else "text/csv"
}

object CsvExporter {
    fun exportLatest(context: Context, sourcePath: String, mode: CsvExportMode): ExportResult {
        val source = File(sourcePath)
        require(source.exists()) { "Recording file not found" }

        val rawUris = when (mode) {
            CsvExportMode.COMBINED -> {
                listOf(saveToDownloads(context, source.name, "text/csv", source.readText()).toString())
            }
            CsvExportMode.SEPARATE_BY_SIM -> exportSeparate(context, source)
        }

        // Keep the raw CSV untouched for analysis, and create a human-friendly summary
        // report next to it. This gives the export a real "summary page" without breaking
        // existing CSV workflows or requiring a heavy spreadsheet library on Android.
        val summaryName = "${source.nameWithoutExtension}_summary.html"
        val summaryUri = saveToDownloads(
            context,
            summaryName,
            "text/html",
            buildSummaryHtml(source)
        ).toString()

        val rawDescription = if (mode == CsvExportMode.SEPARATE_BY_SIM) {
            "${rawUris.size} SIM CSV files"
        } else {
            "1 CSV file"
        }
        return ExportResult(
            message = "Export successful · $rawDescription + summary",
            exportedFileUris = rawUris,
            summaryUri = summaryUri,
            summaryName = summaryName
        )
    }

    private fun exportSeparate(context: Context, source: File): List<String> {
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
        return grouped.map { (slot, rows) ->
            val operator = sanitize(operators[slot] ?: "SIM$slot")
            val name = "${base}_SIM${sanitize(slot)}_${operator}.csv"
            val content = buildString {
                appendLine(header)
                rows.forEach { appendLine(it) }
            }
            saveToDownloads(context, name, "text/csv", content).toString()
        }
    }

    private fun saveToDownloads(context: Context, displayName: String, mimeType: String, content: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CellTracker")
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create exported file")
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
            ?: error("Unable to open export destination")
        return uri
    }

    private data class SummaryRow(
        val timestamp: String,
        val simSlot: String,
        val operator: String,
        val rat: String,
        val band: String,
        val rsrp: String,
        val rsrq: String,
        val sinr: String,
        val pci: String,
        val arfcn: String,
        val latitude: String,
        val longitude: String,
        val issueType: String,
        val note: String,
        val dataNetwork: String
    )

    private fun buildSummaryHtml(source: File): String {
        val lines = source.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return basicSummaryHtml(source.name, emptyList())

        val header = parseCsvLine(lines.first())
        val index = header.withIndex().associate { it.value to it.index }
        fun field(fields: List<String>, name: String): String = fields.getOrNull(index[name] ?: -1).orEmpty()

        val rows = lines.drop(1).map { line ->
            val f = parseCsvLine(line)
            SummaryRow(
                timestamp = field(f, "timestamp"),
                simSlot = field(f, "sim_slot"),
                operator = field(f, "operator"),
                rat = field(f, "display_rat").ifBlank { field(f, "rat") },
                band = field(f, "band"),
                rsrp = field(f, "rsrp"),
                rsrq = field(f, "rsrq"),
                sinr = field(f, "sinr"),
                pci = field(f, "pci"),
                arfcn = field(f, "arfcn"),
                latitude = field(f, "latitude"),
                longitude = field(f, "longitude"),
                issueType = field(f, "event_type"),
                note = field(f, "event_note"),
                dataNetwork = field(f, "data_network")
            )
        }
        return basicSummaryHtml(source.name, rows)
    }

    private fun basicSummaryHtml(sourceName: String, rows: List<SummaryRow>): String {
        val started = rows.firstOrNull()?.timestamp.orEmpty()
        val ended = rows.lastOrNull()?.timestamp.orEmpty()
        val events = rows.filter { it.issueType.isNotBlank() }
        val issueCounts = events.groupingBy { it.issueType }.eachCount().entries.sortedByDescending { it.value }
        val simGroups = rows.groupBy { it.simSlot.ifBlank { "?" } }.toSortedMap()
        val dataNets = rows.map { it.dataNetwork }.filter { it.isNotBlank() && it != "--" }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }

        fun avg(values: List<String>): String {
            val nums = values.mapNotNull { it.toDoubleOrNull() }
            return if (nums.isEmpty()) "--" else String.format(Locale.US, "%.1f", nums.average())
        }
        fun esc(v: String): String = v
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        return buildString {
            append("""<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>CellTracker Summary</title><style>body{font-family:sans-serif;margin:18px;color:#1f2937}h1{font-size:24px}h2{margin-top:26px;font-size:18px}.card{border:1px solid #ddd;border-radius:12px;padding:14px;margin:10px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:8px}.k{color:#6b7280;font-size:12px}.v{font-size:16px;font-weight:600}table{border-collapse:collapse;width:100%;font-size:13px;display:block;overflow-x:auto}th,td{border-bottom:1px solid #e5e7eb;padding:8px;text-align:left;white-space:nowrap}th{background:#f8fafc}.issue{font-weight:700}.muted{color:#6b7280}</style></head><body>""")
            append("<h1>CellTracker Recording Summary</h1>")
            append("<div class='card grid'>")
            append("<div><div class='k'>Source</div><div class='v'>${esc(sourceName)}</div></div>")
            append("<div><div class='k'>Started</div><div class='v'>${esc(started.ifBlank { "--" })}</div></div>")
            append("<div><div class='k'>Ended</div><div class='v'>${esc(ended.ifBlank { "--" })}</div></div>")
            append("<div><div class='k'>Samples</div><div class='v'>${rows.size}</div></div>")
            append("<div><div class='k'>Marked issues</div><div class='v'>${events.size}</div></div>")
            append("</div>")

            append("<h2>Issue Summary</h2><div class='card'>")
            if (issueCounts.isEmpty()) append("<span class='muted'>No marked issues</span>")
            else issueCounts.forEach { (issue, count) -> append("<div><span class='issue'>${esc(issue)}</span> × $count</div>") }
            append("</div>")

            append("<h2>Network Summary</h2><div class='card'><table><thead><tr><th>SIM</th><th>Operator</th><th>RAT</th><th>Band</th><th>RSRP Avg</th><th>RSRQ Avg</th><th>SINR Avg</th><th>PCI</th></tr></thead><tbody>")
            simGroups.forEach { (slot, simRows) ->
                val operator = simRows.map { it.operator }.firstOrNull { it.isNotBlank() }.orEmpty()
                val rats = simRows.map { it.rat }.filter { it.isNotBlank() }.distinct().joinToString(" / ")
                val bands = simRows.map { it.band }.filter { it.isNotBlank() && it != "--" }.distinct().take(8).joinToString(" / ")
                val pcis = simRows.map { it.pci }.filter { it.isNotBlank() && it != "--" }.distinct().take(8).joinToString(" / ")
                append("<tr><td>SIM ${esc(slot)}</td><td>${esc(operator)}</td><td>${esc(rats)}</td><td>${esc(bands)}</td><td>${avg(simRows.map { it.rsrp })} dBm</td><td>${avg(simRows.map { it.rsrq })} dB</td><td>${avg(simRows.map { it.sinr })} dB</td><td>${esc(pcis)}</td></tr>")
            }
            append("</tbody></table></div>")

            append("<h2>Data Network</h2><div class='card'>")
            if (dataNets.isEmpty()) append("<span class='muted'>--</span>")
            else dataNets.forEach { append("<div>${esc(it.key)} × ${it.value}</div>") }
            append("</div>")

            append("<h2>Marked Issue Details</h2><div class='card'><table><thead><tr><th>Time</th><th>Issue</th><th>SIM</th><th>Operator</th><th>RAT</th><th>Band</th><th>RSRP</th><th>RSRQ</th><th>SINR</th><th>PCI</th><th>ARFCN</th><th>Latitude</th><th>Longitude</th><th>Note</th></tr></thead><tbody>")
            if (events.isEmpty()) append("<tr><td colspan='14' class='muted'>No marked issues</td></tr>")
            else events.forEach { e ->
                append("<tr><td>${esc(e.timestamp)}</td><td class='issue'>${esc(e.issueType)}</td><td>SIM ${esc(e.simSlot)}</td><td>${esc(e.operator)}</td><td>${esc(e.rat)}</td><td>${esc(e.band)}</td><td>${esc(e.rsrp)}</td><td>${esc(e.rsrq)}</td><td>${esc(e.sinr)}</td><td>${esc(e.pci)}</td><td>${esc(e.arfcn)}</td><td>${esc(e.latitude)}</td><td>${esc(e.longitude)}</td><td>${esc(e.note)}</td></tr>")
            }
            append("</tbody></table></div>")
            append("</body></html>")
        }
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
