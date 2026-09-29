package com.example.celltracker

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

object VideoLoadingExporter {
    fun export(c: Context, path: String): ExportResult {
        val original = VideoLoadingRepository(c).load(path)
        val review = ReportReviewV1.loadYouTube(c,path,original.samples)
        val d = original
        // The per-attempt snapshot is convenient, but on some dual-SIM/OEM devices it can
        // briefly come from the wrong subscription or miss radio fields. Correlate the reviewed
        // attempt time with the continuous Network Recording and use that as a fallback/source
        // for operator/RAT/RSRP/RSRQ/SINR/Band/PCI/ARFCN.
        val recordingRows = loadRecordingRows(d.recordingPath)
        val src = File(path)
        val base = src.nameWithoutExtension
        val csvUri = save(c, src.name, "text/csv", src.readBytes(), d.startedAt).toString()
        val htmlName = "${base}_summary.html"
        val htmlUri = save(c, htmlName, "text/html", html(d,review,recordingRows).toByteArray(), d.startedAt).toString()
        val xlsxName = "${base}_report.xlsx"
        val rows = mutableListOf<List<String>>()
        rows += listOf("sequence","title","reviewed_t0","reviewed_t1","delay_ms","result","detection","operator","rat","rsrp","rsrq","sinr","band","pci","arfcn")
        original.samples.forEachIndexed { i,sample ->
            val rev=review.events.getOrNull(i)
            val eventMs = reviewedWallTime(sample.startMs, rev?.t0)
            val snap = enrichSnapshot(sample.snapshot, nearestRecordingSnapshot(recordingRows, eventMs, sample.snapshot.subscriptionId))
            rows += listOf(
                sample.sequence.toString(), sample.title, rev?.t0.orEmpty(), rev?.t1.orEmpty(),
                (if(rev?.valid==true) ReportReviewV1.durationMs(rev.t0,rev.t1) else null)?.toString().orEmpty(),
                if(rev?.valid==false) "INVALID" else sample.result, sample.detection, snap.operator, snap.displayRat,
                snap.rsrp, snap.rsrq, snap.sinr, snap.band, snap.pci, snap.arfcn
            )
        }
        val ok = review.events.filter { it.valid }.mapNotNull { ReportReviewV1.durationMs(it.t0,it.t1) }.sorted()

        fun percentile(x: Double): Long? {
            if (ok.isEmpty()) return null
            val index = ceil((ok.size - 1) * x).toInt().coerceIn(0, ok.lastIndex)
            return ok[index]
        }

        val summary = listOf(
            listOf("CellTracker YouTube Video Page Loading"),
            listOf("Status", d.status),
            listOf("Attempts", d.samples.size.toString()),
            listOf("Success", review.events.count { it.valid && ReportReviewV1.durationMs(it.t0,it.t1)!=null }.toString()),
            listOf("Timeout", d.samples.count { sample -> sample.result == "TIMEOUT" }.toString()),
            listOf("Advertisement", d.samples.count { sample -> sample.result == "AD" }.toString()),
            listOf("Average ms", if (ok.isNotEmpty()) String.format(Locale.US, "%.0f", ok.average()) else ""),
            listOf("Median ms", percentile(0.5)?.toString().orEmpty()),
            listOf("P90 ms", percentile(0.9)?.toString().orEmpty()),
            listOf("P95 ms", percentile(0.95)?.toString().orEmpty()),
            listOf("Min ms", ok.minOrNull()?.toString().orEmpty()),
            listOf("Max ms", ok.maxOrNull()?.toString().orEmpty()),
            listOf("Recording Path", d.recordingPath.orEmpty()),
            listOf("Screen Recording URI", d.screenRecordingUri.orEmpty()),
            listOf("Review Status", if(review.confirmed) "Confirmed" else "Draft / Not Confirmed"),
            listOf("Reviewed Valid Attempts", review.events.count { it.valid }.toString())
        )
        val xlsx = PingExporter.simpleXlsx(listOf("Summary" to summary, "Video Loading" to rows))
        val xlsxUri = save(c, xlsxName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx, d.startedAt).toString()
        return ExportResult(
            "YouTube Video Loading report exported · HTML + Excel + CSV",
            listOf(csvUri), htmlUri, htmlName, xlsxUri, xlsxName
        )
    }

    private fun html(d: VideoLoadingDetail, review:ReviewStateV1, recordingRows: List<TimedSnapshot>): String {
        val values = review.events.filter { it.valid }.mapNotNull { ReportReviewV1.durationMs(it.t0,it.t1) }.sorted()
        fun percentile(x: Double): Long? {
            if (values.isEmpty()) return null
            val index = ceil((values.size - 1) * x).toInt().coerceIn(0, values.lastIndex)
            return values[index]
        }
        fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")

        val successCount = review.events.count { it.valid && ReportReviewV1.durationMs(it.t0,it.t1)!=null }
        val timeoutCount = d.samples.count { sample -> sample.result == "TIMEOUT" }
        val adCount = d.samples.count { sample -> sample.result == "AD" }
        val averageText = if (values.isNotEmpty()) String.format(Locale.US, "%.0f ms", values.average()) else "--"
        val p90Text = percentile(0.9)?.let { "$it ms" } ?: "--"
        val p95Text = percentile(0.95)?.let { "$it ms" } ?: "--"

        return buildString {
            append("<html><head><meta name='viewport' content='width=device-width'>")
            append("<style>body{font-family:sans-serif;margin:18px}table{border-collapse:collapse;width:100%;display:block;overflow:auto}td,th{padding:8px;border-bottom:1px solid #ddd;white-space:nowrap}.card{padding:12px;border:1px solid #ddd;border-radius:12px;margin:10px 0}</style>")
            append("</head><body><h1>YouTube Video Page Loading</h1>")
            append("<div class='card'><b>Review:</b> ${if(review.confirmed)"Confirmed" else "Draft / Not Confirmed"}<br>")
            append("Attempts ${d.samples.size} · Success $successCount · Timeout $timeoutCount · AD $adCount<br>")
            append("Average $averageText · P90 $p90Text · P95 $p95Text</div>")
            append("<table><tr><th>#</th><th>Title</th><th>Reviewed Delay</th><th>Review</th><th>Detection</th><th>Reviewed T0</th><th>Reviewed T1</th><th>Original T0 Source</th><th>RAT</th><th>RSRP</th><th>SINR</th><th>PCI</th></tr>")
            d.samples.forEachIndexed { i,sample ->
                val rev=review.events.getOrNull(i)
                val delayText=if(rev?.valid==true) ReportReviewV1.durationMs(rev.t0,rev.t1)?.toString() ?: "--" else "--"
                val result=if(rev?.valid==false) "Invalid / Mis-touch" else sample.result
                val eventMs = reviewedWallTime(sample.startMs, rev?.t0)
                val snap = enrichSnapshot(sample.snapshot, nearestRecordingSnapshot(recordingRows, eventMs, sample.snapshot.subscriptionId))
                append("<tr><td>${sample.sequence}</td><td>${escape(sample.title)}</td><td>$delayText ms</td><td>${escape(result)}</td><td>${escape(sample.detection)}</td><td>${escape(rev?.t0.orEmpty())}</td><td>${escape(rev?.t1.orEmpty())}</td><td>${escape(sample.t0Source)}</td><td>${escape(snap.displayRat)}</td><td>${escape(snap.rsrp)}</td><td>${escape(snap.sinr)}</td><td>${escape(snap.pci)}</td></tr>")
            }
            append("</table></body></html>")
        }
    }

    private data class TimedSnapshot(val timeMs: Long, val snapshot: PingNetworkSnapshot, val dataSimId: Int?)

    private fun loadRecordingRows(path: String?): List<TimedSnapshot> {
        val file = path?.takeIf { it.isNotBlank() }?.let(::File) ?: return emptyList()
        if (!file.exists()) return emptyList()
        return runCatching {
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return@runCatching emptyList()
            val header = parse(lines.first())
            val idx = header.withIndex().associate { it.value to it.index }
            fun field(row: List<String>, key: String) = idx[key]?.let { row.getOrNull(it) }.orEmpty()
            val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).apply { isLenient = false }
            lines.drop(1).mapNotNull { raw ->
                val row = parse(raw)
                val time = runCatching { timeFmt.parse(field(row, "timestamp"))?.time }.getOrNull() ?: return@mapNotNull null
                val subId = field(row, "subscription_id").toIntOrNull() ?: -1
                val dataSimId = field(row, "data_sim_subscription_id").toIntOrNull()
                TimedSnapshot(
                    time,
                    PingNetworkSnapshot(
                        subscriptionId = subId,
                        simSlot = (field(row, "sim_slot").toIntOrNull() ?: 1) - 1,
                        operator = field(row, "operator").ifBlank { "--" },
                        rat = field(row, "rat").ifBlank { "--" },
                        displayRat = field(row, "display_rat").ifBlank { field(row, "rat").ifBlank { "--" } },
                        rsrp = field(row, "rsrp").ifBlank { "--" },
                        rsrq = field(row, "rsrq").ifBlank { "--" },
                        sinr = field(row, "sinr").ifBlank { "--" },
                        rssi = field(row, "rssi").ifBlank { "--" },
                        band = field(row, "band").ifBlank { "--" },
                        pci = field(row, "pci").ifBlank { "--" },
                        arfcn = field(row, "arfcn").ifBlank { "--" },
                        latitude = field(row, "latitude").toDoubleOrNull(),
                        longitude = field(row, "longitude").toDoubleOrNull()
                    ),
                    dataSimId
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun nearestRecordingSnapshot(rows: List<TimedSnapshot>, eventMs: Long, preferredSubId: Int): PingNetworkSnapshot? {
        if (rows.isEmpty() || eventMs <= 0L) return null
        val dataRows = rows.filter { row ->
            val desired = row.dataSimId
            desired == null || desired < 0 || row.snapshot.subscriptionId == desired
        }.ifEmpty { rows }
        val preferred = if (preferredSubId >= 0) dataRows.filter { it.snapshot.subscriptionId == preferredSubId } else emptyList()
        val pool = preferred.ifEmpty { dataRows }
        val nearest = pool.minByOrNull { kotlin.math.abs(it.timeMs - eventMs) } ?: return null
        return nearest.snapshot.takeIf { kotlin.math.abs(nearest.timeMs - eventMs) <= 15_000L }
    }

    private fun reviewedWallTime(baseMs: Long, reviewed: String?): Long {
        val text = reviewed?.trim().orEmpty()
        if (text.isBlank()) return baseMs
        val formats = listOf("yyyy-MM-dd HH:mm:ss.SSS", "HH:mm:ss.SSS", "HH:mm:ss")
        for (pattern in formats) {
            val parsed = runCatching {
                if (pattern.startsWith("yyyy")) {
                    SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(text)?.time
                } else {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(baseMs))
                    SimpleDateFormat("yyyy-MM-dd $pattern", Locale.US).apply { isLenient = false }.parse("$date $text")?.time
                }
            }.getOrNull() ?: continue
            if (!pattern.startsWith("yyyy")) {
                val candidates = listOf(parsed - 86_400_000L, parsed, parsed + 86_400_000L)
                return candidates.minByOrNull { kotlin.math.abs(it - baseMs) } ?: baseMs
            }
            return parsed
        }
        return baseMs
    }

    private fun enrichSnapshot(primary: PingNetworkSnapshot, fallback: PingNetworkSnapshot?): PingNetworkSnapshot {
        if (fallback == null) return primary
        fun choose(a: String, b: String): String = a.takeUnless { it.isBlank() || it == "--" } ?: b
        // Continuous recording is preferred when the event snapshot clearly came from another SIM
        // or is missing core LTE/NR fields. Otherwise only fill blanks.
        val wrongSub = primary.subscriptionId >= 0 && fallback.subscriptionId >= 0 && primary.subscriptionId != fallback.subscriptionId
        return if (wrongSub) fallback else primary.copy(
            operator = choose(primary.operator, fallback.operator),
            rat = choose(primary.rat, fallback.rat),
            displayRat = choose(primary.displayRat, fallback.displayRat),
            rsrp = choose(primary.rsrp, fallback.rsrp),
            rsrq = choose(primary.rsrq, fallback.rsrq),
            sinr = choose(primary.sinr, fallback.sinr),
            rssi = choose(primary.rssi, fallback.rssi),
            band = choose(primary.band, fallback.band),
            pci = choose(primary.pci, fallback.pci),
            arfcn = choose(primary.arfcn, fallback.arfcn),
            latitude = primary.latitude ?: fallback.latitude,
            longitude = primary.longitude ?: fallback.longitude
        )
    }

    private fun fmtTime(ms: Long): String = if (ms > 0L) java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date(ms)) else "--"

    private fun save(c: Context, name: String, mime: String, bytes: ByteArray, startedAt: Long): android.net.Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, ReportStorage.relativePath("YouTube", startedAt))
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
