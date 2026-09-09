package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlin.math.ceil

object WhatsAppSendExporter {
    fun export(c: Context, path: String): ExportResult {
        val d = WhatsAppSendRepository(c).load(path)
        val src = File(path)
        val base = src.nameWithoutExtension
        val csvUri = save(c, src.name, "text/csv", src.readBytes(), d.startedAt).toString()
        val htmlName = "${base}_summary.html"
        val htmlUri = save(c, htmlName, "text/html", html(d).toByteArray(), d.startedAt).toString()
        val xlsxName = "${base}_report.xlsx"
        val rows = src.readLines().filter { it.isNotBlank() }.map { parse(it) }
        val values = d.samples.map { it.delayMs }.sorted()

        fun percentile(x: Double): Long? {
            if (values.isEmpty()) return null
            val index = ceil((values.size - 1) * x).toInt().coerceIn(0, values.lastIndex)
            return values[index]
        }

        val summary = listOf(
            listOf("CellTracker WhatsApp Image Send"),
            listOf("Status", d.status),
            listOf("Samples", d.samples.size.toString()),
            listOf("Average ms", if (values.isNotEmpty()) String.format(Locale.US, "%.0f", values.average()) else ""),
            listOf("Median ms", percentile(0.5)?.toString().orEmpty()),
            listOf("P90 ms", percentile(0.9)?.toString().orEmpty()),
            listOf("P95 ms", percentile(0.95)?.toString().orEmpty()),
            listOf("Min ms", values.minOrNull()?.toString().orEmpty()),
            listOf("Max ms", values.maxOrNull()?.toString().orEmpty()),
            listOf("Recording Path", d.path)
        )
        val xlsx = PingExporter.simpleXlsx(listOf("Summary" to summary, "WhatsApp Send" to rows))
        val xlsxUri = save(c, xlsxName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx, d.startedAt).toString()
        return ExportResult(
            "WhatsApp Image Send report exported · HTML + Excel + CSV",
            listOf(csvUri), htmlUri, htmlName, xlsxUri, xlsxName
        )
    }

    private fun html(d: WhatsAppSendDetail): String {
        val values = d.samples.map { it.delayMs }.sorted()
        fun percentile(x: Double): Long? {
            if (values.isEmpty()) return null
            val index = ceil((values.size - 1) * x).toInt().coerceIn(0, values.lastIndex)
            return values[index]
        }
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
        val averageText = if (values.isNotEmpty()) String.format(Locale.US, "%.0f ms", values.average()) else "--"
        val medianText = percentile(.5)?.let { "$it ms" } ?: "--"
        val p90Text = percentile(.9)?.let { "$it ms" } ?: "--"
        val p95Text = percentile(.95)?.let { "$it ms" } ?: "--"
        return buildString {
            append("<html><head><meta name='viewport' content='width=device-width'>")
            append("<style>body{font-family:sans-serif;margin:18px}table{border-collapse:collapse;width:100%;display:block;overflow:auto}td,th{padding:8px;border-bottom:1px solid #ddd;white-space:nowrap}.card{padding:12px;border:1px solid #ddd;border-radius:12px;margin:10px 0}</style>")
            append("</head><body><h1>WhatsApp Image Send</h1>")
            append("<div class='card'>Samples ${d.samples.size}<br>")
            append("Average $averageText · Median $medianText · P90 $p90Text · P95 $p95Text · Min ${values.minOrNull()?.let{"$it ms"}?:"--"} · Max ${values.maxOrNull()?.let{"$it ms"}?:"--"}</div>")
            append("<table><tr><th>#</th><th>Send Delay</th><th>T0 Send Time</th><th>T1 Sent Time</th><th>T0 Source</th><th>RAT</th><th>RSRP</th><th>RSRQ</th><th>SINR</th><th>RSSI</th><th>Band</th><th>PCI</th><th>ARFCN</th><th>Operator</th><th>SIM</th></tr>")
            d.samples.forEach { s ->
                append("<tr><td>${s.sequence}</td><td>${s.delayMs} ms</td><td>${fmtTime(s.t0Ms)}</td><td>${fmtTime(s.t1Ms)}</td><td>${escape(s.t0Source)}</td><td>${escape(s.snapshot.displayRat)}</td><td>${escape(s.snapshot.rsrp)}</td><td>${escape(s.snapshot.rsrq)}</td><td>${escape(s.snapshot.sinr)}</td><td>${escape(s.snapshot.rssi)}</td><td>${escape(s.snapshot.band)}</td><td>${escape(s.snapshot.pci)}</td><td>${escape(s.snapshot.arfcn)}</td><td>${escape(s.snapshot.operator)}</td><td>${s.snapshot.simSlot + 1}</td></tr>")
            }
            append("</table></body></html>")
        }
    }

    private fun fmtTime(ms: Long): String =
        if (ms > 0L) java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date(ms)) else "--"

    private fun save(c: Context, name: String, mime: String, bytes: ByteArray, startedAt: Long): android.net.Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, ReportStorage.relativePath("WhatsApp", startedAt))
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            c.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return uri
        }
        throw IllegalStateException("Android 10+ required")
    }

    private fun parse(s: String): List<String> {
        val out = mutableListOf<String>()
        val buffer = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"' && quoted && i + 1 < s.length && s[i + 1] == '"') {
                buffer.append('"'); i++
            } else if (ch == '"') quoted = !quoted
            else if (ch == ',' && !quoted) { out += buffer.toString(); buffer.setLength(0) }
            else buffer.append(ch)
            i++
        }
        out += buffer.toString()
        return out
    }
}
