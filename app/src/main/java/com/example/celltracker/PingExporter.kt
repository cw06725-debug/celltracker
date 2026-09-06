package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PingExporter {
    fun export(context: Context, sourcePath: String): ExportResult {
        val source = File(sourcePath)
        require(source.exists()) { "Ping result file not found" }
        val detail = PingRepository(context).loadDetail(sourcePath)
        val base = source.nameWithoutExtension
        val csvUri = saveText(context, source.name, "text/csv", source.readText()).toString()
        val htmlName = "${base}_summary.html"
        val htmlUri = saveText(context, htmlName, "text/html", buildHtml(detail)).toString()
        val xlsxName = "${base}_report.xlsx"
        val xlsxUri = saveBytes(
            context, xlsxName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            buildXlsx(source, detail)
        ).toString()
        val kmlName = "${base}_track.kml"
        val kmlUri = saveText(context, kmlName, "application/vnd.google-earth.kml+xml", buildKml(detail)).toString()
        return ExportResult(
            message = "Ping export successful · CSV + Summary + Excel + KML",
            exportedFileUris = listOf(csvUri),
            summaryUri = htmlUri,
            summaryName = htmlName,
            excelUri = xlsxUri,
            excelName = xlsxName,
            kmlUri = kmlUri,
            kmlName = kmlName
        )
    }

    private fun buildHtml(detail: PingDetail): String {
        val item = detail.item
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        fun latency(value: Double?) = value?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--"
        fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
        return buildString {
            append("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Ping Summary</title><style>body{font-family:sans-serif;margin:18px;color:#1f2937}h1{font-size:24px}.card{border:1px solid #ddd;border-radius:12px;padding:14px;margin:10px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px}.k{color:#6b7280;font-size:12px}.v{font-size:17px;font-weight:600;margin-top:3px}table{border-collapse:collapse;width:100%;font-size:13px;display:block;overflow-x:auto}th,td{border-bottom:1px solid #e5e7eb;padding:8px;text-align:left;white-space:nowrap}th{background:#f8fafc}.event{font-weight:700;color:#b91c1c}</style></head><body>""")
            append("<h1>CellTracker Ping Test Summary</h1><div class='card grid'>")
            listOf(
                "Task Name" to item.taskName, "Host" to item.host,
                "Started" to format.format(Date(item.startedAt)), "Ended" to format.format(Date(item.endedAt)),
                "Duration" to formatDuration(item.durationMs), "Status" to item.status,
                "Sent" to item.packetCount.toString(), "Received" to item.receivedCount.toString(),
                "Success Rate" to String.format(Locale.US, "%.1f%%", item.successRate),
                "Packet Loss" to String.format(Locale.US, "%.1f%%", item.packetLossRate),
                "Avg / Min / Max" to "${latency(item.averageLatencyMs)} / ${latency(item.minLatencyMs)} / ${latency(item.maxLatencyMs)}",
                "P50 / P90 / P95" to "${latency(item.p50LatencyMs)} / ${latency(item.p90LatencyMs)} / ${latency(item.p95LatencyMs)}",
                "High threshold" to latency(item.highLatencyThresholdMs),
                "HIGH_PING" to item.highPingCount.toString(), "PING_TIMEOUT" to item.timeoutEventCount.toString()
            ).forEach { (key, value) -> append("<div><div class='k'>${esc(key)}</div><div class='v'>${esc(value)}</div></div>") }
            append("</div><h2>Auto Events</h2><div class='card'><table><thead><tr><th>Sequence</th><th>Time</th><th>Event</th><th>RTT</th><th>Operator</th><th>RAT</th><th>RSRP</th><th>SINR</th><th>PCI</th><th>Location</th></tr></thead><tbody>")
            val events = detail.samples.filter { it.eventType.isNotBlank() }
            if (events.isEmpty()) append("<tr><td colspan='10'>No automatic events</td></tr>")
            events.forEach { sample ->
                val s = sample.snapshot
                append("<tr><td>${sample.sequence}</td><td>${esc(format.format(Date(sample.timestampMs)))}</td><td class='event'>${esc(sample.eventType)}</td><td>${latency(sample.latencyMs)}</td><td>${esc(s.operator)}</td><td>${esc(s.displayRat)}</td><td>${esc(s.rsrp)}</td><td>${esc(s.sinr)}</td><td>${esc(s.pci)}</td><td>${s.latitude ?: "--"}, ${s.longitude ?: "--"}</td></tr>")
            }
            append("</tbody></table></div></body></html>")
        }
    }

    private fun buildKml(detail: PingDetail): String {
        fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val located = detail.samples.filter { it.snapshot.latitude != null && it.snapshot.longitude != null }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document>")
            append("<name>${esc(detail.item.taskName)}</name>")
            append("<Style id=\"track\"><LineStyle><color>ffffa500</color><width>4</width></LineStyle></Style>")
            append("<Style id=\"high\"><IconStyle><color>ff00a5ff</color><scale>1.2</scale></IconStyle></Style>")
            append("<Style id=\"timeout\"><IconStyle><color>ff0000ff</color><scale>1.3</scale></IconStyle></Style>")
            if (located.isNotEmpty()) {
                append("<Placemark><name>Ping Track</name><styleUrl>#track</styleUrl><LineString><tessellate>1</tessellate><coordinates>")
                located.forEach { append("${it.snapshot.longitude},${it.snapshot.latitude},0 ") }
                append("</coordinates></LineString></Placemark>")
            }
            located.filter { it.eventType.isNotBlank() }.forEach { sample ->
                val s = sample.snapshot
                val style = if (sample.eventType == "PING_TIMEOUT") "timeout" else "high"
                val description = listOf(
                    "Time: ${format.format(Date(sample.timestampMs))}",
                    "Source: ${sample.eventSource}", "RTT: ${sample.latencyMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: "Timeout"}",
                    "Operator: ${s.operator}", "RAT: ${s.displayRat}", "Band: ${s.band}", "CA / EN-DC: ${s.carrierAggregation}",
                    "RSRP: ${s.rsrp}", "RSRQ: ${s.rsrq}", "SINR: ${s.sinr}", "PCI: ${s.pci}", "ARFCN: ${s.arfcn}"
                ).joinToString("\n")
                append("<Placemark><name>${esc(sample.eventType)}</name><styleUrl>#$style</styleUrl><description>${esc(description)}</description><Point><coordinates>${s.longitude},${s.latitude},0</coordinates></Point></Placemark>")
            }
            append("</Document></kml>")
        }
    }

    private fun buildXlsx(source: File, detail: PingDetail): ByteArray {
        val rawRows = source.readLines().filter { it.isNotBlank() }.map(PingRepository::parseCsvLine)
        val item = detail.item
        fun number(value: Double?) = value?.let { String.format(Locale.US, "%.3f", it) } ?: ""
        val summary = listOf(
            listOf("CellTracker Ping Test Summary"), listOf("Task Name", item.taskName), listOf("Host", item.host),
            listOf("Started", item.startedAt.toString()), listOf("Ended", item.endedAt.toString()), listOf("Duration ms", item.durationMs.toString()),
            listOf("Status", item.status), listOf("Sent", item.packetCount.toString()), listOf("Received", item.receivedCount.toString()),
            listOf("Success Rate %", String.format(Locale.US, "%.3f", item.successRate)), listOf("Packet Loss %", String.format(Locale.US, "%.3f", item.packetLossRate)),
            listOf("Average RTT ms", number(item.averageLatencyMs)), listOf("Minimum RTT ms", number(item.minLatencyMs)), listOf("Maximum RTT ms", number(item.maxLatencyMs)),
            listOf("P50 ms", number(item.p50LatencyMs)), listOf("P90 ms", number(item.p90LatencyMs)), listOf("P95 ms", number(item.p95LatencyMs)),
            listOf("High Latency Threshold ms", number(item.highLatencyThresholdMs)), listOf("HIGH_PING count", item.highPingCount.toString()),
            listOf("PING_TIMEOUT count", item.timeoutEventCount.toString()), listOf("Recording Path", item.recordingPath.orEmpty())
        )
        val eventHeader = listOf("sequence", "timestamp", "event_source", "event_type", "latency_ms", "consecutive_failures", "subscription_id", "sim_slot", "operator", "display_rat", "rsrp", "rsrq", "sinr", "rssi", "band", "pci", "arfcn", "latitude", "longitude")
        val eventRows = mutableListOf(eventHeader)
        detail.samples.filter { it.eventType.isNotBlank() }.forEach { sample ->
            val s = sample.snapshot
            eventRows += listOf(sample.sequence.toString(), sample.timestampMs.toString(), sample.eventSource, sample.eventType, number(sample.latencyMs),
                sample.consecutiveFailures.toString(), s.subscriptionId.toString(), (s.simSlot + 1).toString(), s.operator, s.displayRat,
                s.rsrp, s.rsrq, s.sinr, s.rssi, s.band, s.pci, s.arfcn, s.latitude?.toString().orEmpty(), s.longitude?.toString().orEmpty())
        }
        return simpleXlsx(listOf("Summary" to summary, "Ping Samples" to rawRows, "Events" to eventRows))
    }

    internal fun simpleXlsx(sheets: List<Pair<String, List<List<String>>>>): ByteArray {
        fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun colName(index: Int): String {
            var value = index + 1
            var result = ""
            while (value > 0) { val remainder = (value - 1) % 26; result = ('A'.code + remainder).toChar() + result; value = (value - 1) / 26 }
            return result
        }
        fun sheetXml(rows: List<List<String>>) = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                append("<row r=\"${rowIndex + 1}\">")
                row.forEachIndexed { columnIndex, value ->
                    val style = if (rowIndex == 0) 1 else 0
                    append("<c r=\"${colName(columnIndex)}${rowIndex + 1}\" t=\"inlineStr\" s=\"$style\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            val overrides = sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }
            add("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>$overrides<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>")
            add("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            val sheetDefs = sheets.mapIndexed { index, pair -> "<sheet name=\"${xml(pair.first)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>" }.joinToString("")
            add("xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>$sheetDefs</sheets></workbook>")
            val relations = sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }
            add("xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">$relations<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>")
            add("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/><xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs></styleSheet>")
            sheets.forEachIndexed { index, pair -> add("xl/worksheets/sheet${index + 1}.xml", sheetXml(pair.second)) }
        }
        return output.toByteArray()
    }

    private fun saveText(context: Context, name: String, mime: String, content: String) = saveBytes(context, name, mime, content.toByteArray())

    private fun saveBytes(context: Context, name: String, mime: String, bytes: ByteArray): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CellTracker")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Unable to create export file")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Unable to write export file")
        return uri
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000L
        return String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }
}
