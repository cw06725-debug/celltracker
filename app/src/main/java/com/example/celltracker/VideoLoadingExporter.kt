package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlin.math.ceil

object VideoLoadingExporter {
    fun export(c: Context, path: String): ExportResult {
        val d = VideoLoadingRepository(c).load(path)
        val src = File(path)
        val base = src.nameWithoutExtension
        val csvUri = save(c, src.name, "text/csv", src.readBytes()).toString()
        val htmlName = "${base}_summary.html"
        val htmlUri = save(c, htmlName, "text/html", html(d).toByteArray()).toString()
        val xlsxName = "${base}_report.xlsx"
        val rows = src.readLines().filter { it.isNotBlank() }.map { parse(it) }
        val ok = d.samples.mapNotNull { it.delayMs }.sorted()

        fun percentile(x: Double): Long? {
            if (ok.isEmpty()) return null
            val index = ceil((ok.size - 1) * x).toInt().coerceIn(0, ok.lastIndex)
            return ok[index]
        }

        val summary = listOf(
            listOf("CellTracker YouTube Video Page Loading"),
            listOf("Status", d.status),
            listOf("Attempts", d.samples.size.toString()),
            listOf("Success", d.samples.count { sample -> sample.result == "PASS" }.toString()),
            listOf("Timeout", d.samples.count { sample -> sample.result == "TIMEOUT" }.toString()),
            listOf("Average ms", if (ok.isNotEmpty()) String.format(Locale.US, "%.0f", ok.average()) else ""),
            listOf("Median ms", percentile(0.5)?.toString().orEmpty()),
            listOf("P90 ms", percentile(0.9)?.toString().orEmpty()),
            listOf("P95 ms", percentile(0.95)?.toString().orEmpty()),
            listOf("Min ms", ok.minOrNull()?.toString().orEmpty()),
            listOf("Max ms", ok.maxOrNull()?.toString().orEmpty()),
            listOf("Recording Path", d.recordingPath.orEmpty())
        )
        val xlsx = PingExporter.simpleXlsx(listOf("Summary" to summary, "Video Loading" to rows))
        val xlsxUri = save(c, xlsxName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx).toString()
        return ExportResult(
            "YouTube Video Loading report exported · HTML + Excel + CSV",
            listOf(csvUri), htmlUri, htmlName, xlsxUri, xlsxName
        )
    }

    private fun html(d: VideoLoadingDetail): String {
        val values = d.samples.mapNotNull { it.delayMs }.sorted()
        fun percentile(x: Double): Long? {
            if (values.isEmpty()) return null
            val index = ceil((values.size - 1) * x).toInt().coerceIn(0, values.lastIndex)
            return values[index]
        }
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

        val successCount = d.samples.count { sample -> sample.result == "PASS" }
        val timeoutCount = d.samples.count { sample -> sample.result == "TIMEOUT" }
        val averageText = if (values.isNotEmpty()) String.format(Locale.US, "%.0f ms", values.average()) else "--"
        val p90Text = percentile(0.9)?.let { "$it ms" } ?: "--"
        val p95Text = percentile(0.95)?.let { "$it ms" } ?: "--"

        return buildString {
            append("<html><head><meta name='viewport' content='width=device-width'>")
            append("<style>body{font-family:sans-serif;margin:18px}table{border-collapse:collapse;width:100%;display:block;overflow:auto}td,th{padding:8px;border-bottom:1px solid #ddd;white-space:nowrap}.card{padding:12px;border:1px solid #ddd;border-radius:12px;margin:10px 0}</style>")
            append("</head><body><h1>YouTube Video Page Loading</h1>")
            append("<div class='card'>Attempts ${d.samples.size} · Success $successCount · Timeout $timeoutCount<br>")
            append("Average $averageText · P90 $p90Text · P95 $p95Text</div>")
            append("<table><tr><th>#</th><th>Title</th><th>Delay</th><th>Result</th><th>Detection</th><th>RAT</th><th>RSRP</th><th>SINR</th><th>PCI</th></tr>")
            d.samples.forEach { sample ->
                val delayText = sample.delayMs?.toString() ?: "--"
                append("<tr><td>${sample.sequence}</td><td>${escape(sample.title)}</td><td>$delayText ms</td><td>${sample.result}</td><td>${sample.detection}</td><td>${escape(sample.snapshot.displayRat)}</td><td>${escape(sample.snapshot.rsrp)}</td><td>${escape(sample.snapshot.sinr)}</td><td>${escape(sample.snapshot.pci)}</td></tr>")
            }
            append("</table></body></html>")
        }
    }

    private fun save(c: Context, name: String, mime: String, bytes: ByteArray): android.net.Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CellTracker")
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
                buffer.append('"')
                i++
            } else if (ch == '"') {
                quoted = !quoted
            } else if (ch == ',' && !quoted) {
                out += buffer.toString()
                buffer.setLength(0)
            } else {
                buffer.append(ch)
            }
            i++
        }
        out += buffer.toString()
        return out
    }
}
