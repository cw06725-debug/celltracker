package com.example.celltracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

class VideoLoadingRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("celltracker_video_loading", Context.MODE_PRIVATE)

    fun loadConfig() = VideoLoadingConfig(
        prefs.getInt("count", 10),
        prefs.getLong("timeout", 15000),
        prefs.getLong("return_wait", 2000),
        prefs.getBoolean("auto_record", true)
    )

    fun saveConfig(c: VideoLoadingConfig) {
        prefs.edit()
            .putInt("count", c.count)
            .putLong("timeout", c.timeoutMs)
            .putLong("return_wait", c.returnWaitMs)
            .putBoolean("auto_record", c.autoRecord)
            .apply()
    }

    fun arm(c: VideoLoadingConfig) {
        saveConfig(c)
        prefs.edit().putBoolean("armed", true).apply()
    }

    fun isArmed() = prefs.getBoolean("armed", false)

    fun disarm() {
        prefs.edit().putBoolean("armed", false).apply()
    }

    fun create(start: Long): File {
        val name = "YouTube_Video_Loading_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(start))}.csv"
        val file = File(dir(), name)
        file.writeText(HEADER + "\n")
        meta(file, start, 0, "Running", null)
        return file
    }

    fun append(file: File, sample: VideoLoadingSample) {
        val n = sample.snapshot
        val values = listOf(
            sample.sequence,
            sample.startMs,
            fmt(sample.startMs),
            sample.loadedMs.takeIf { it > 0 } ?: "",
            sample.delayMs ?: "",
            sample.result,
            sample.detection,
            sample.title,
            n.subscriptionId,
            n.simSlot + 1,
            n.operator,
            n.displayRat,
            n.rsrp,
            n.rsrq,
            n.sinr,
            n.rssi,
            n.band,
            n.pci,
            n.arfcn,
            n.latitude ?: "",
            n.longitude ?: ""
        )
        FileWriter(file, true).use { writer ->
            writer.appendLine(values.joinToString(",") { csv(it.toString()) })
        }
    }

    fun finish(file: File, start: Long, end: Long, status: String, recording: String?) {
        meta(file, start, end, status, recording)
    }

    fun history(): List<VideoLoadingDetail> =
        dir().listFiles { file -> file.extension == "csv" }
            ?.mapNotNull { file -> runCatching { load(file.absolutePath) }.getOrNull() }
            ?.sortedByDescending { it.startedAt }
            .orEmpty()

    fun load(path: String): VideoLoadingDetail {
        val file = File(path)
        val rows = file.readLines().filter { it.isNotBlank() }
        if (rows.isEmpty()) {
            return VideoLoadingDetail(path, file.lastModified(), 0L, "Empty", emptyList(), null)
        }

        val header = parse(rows.first())
        val index = header.withIndex().associate { indexed -> indexed.value to indexed.index }

        fun get(row: List<String>, key: String): String {
            val column = index[key] ?: return ""
            return row.getOrNull(column).orEmpty()
        }

        val samples = rows.drop(1).mapNotNull { raw ->
            val row = parse(raw)
            val sequence = get(row, "sequence").toIntOrNull() ?: return@mapNotNull null
            val startMs = get(row, "start_ms").toLongOrNull() ?: return@mapNotNull null
            val loadedMs = get(row, "loaded_ms").toLongOrNull() ?: 0L
            val snapshot = PingNetworkSnapshot(
                subscriptionId = get(row, "subscription_id").toIntOrNull() ?: -1,
                simSlot = (get(row, "sim_slot").toIntOrNull() ?: 1) - 1,
                operator = get(row, "operator"),
                displayRat = get(row, "rat"),
                rsrp = get(row, "rsrp"),
                rsrq = get(row, "rsrq"),
                sinr = get(row, "sinr"),
                rssi = get(row, "rssi"),
                band = get(row, "band"),
                pci = get(row, "pci"),
                arfcn = get(row, "arfcn"),
                latitude = get(row, "latitude").toDoubleOrNull(),
                longitude = get(row, "longitude").toDoubleOrNull()
            )
            VideoLoadingSample(
                sequence = sequence,
                title = get(row, "title"),
                startMs = startMs,
                loadedMs = loadedMs,
                delayMs = get(row, "delay_ms").toLongOrNull(),
                result = get(row, "result"),
                detection = get(row, "detection"),
                snapshot = snapshot
            )
        }

        val props = Properties()
        val metaFile = File(file.parentFile, file.nameWithoutExtension + ".meta")
        if (metaFile.exists()) {
            metaFile.inputStream().use { props.load(it) }
        }

        val startedAt = props.getProperty("started")?.toLongOrNull()
            ?: samples.firstOrNull()?.startMs
            ?: file.lastModified()
        val endedAt = props.getProperty("ended")?.toLongOrNull()
            ?: samples.lastOrNull()?.loadedMs
            ?: 0L
        val status = props.getProperty("status", "Completed")
        val recordingPath = props.getProperty("recording")?.takeIf { it.isNotBlank() }

        return VideoLoadingDetail(
            path = path,
            startedAt = startedAt,
            endedAt = endedAt,
            status = status,
            samples = samples,
            recordingPath = recordingPath
        )
    }

    private fun meta(file: File, start: Long, end: Long, status: String, recording: String?) {
        Properties().apply {
            setProperty("started", start.toString())
            setProperty("ended", end.toString())
            setProperty("status", status)
            setProperty("recording", recording.orEmpty())
        }.store(
            File(file.parentFile, file.nameWithoutExtension + ".meta").outputStream(),
            "CellTracker YouTube Video Loading"
        )
    }

    private fun dir() = File(context.getExternalFilesDir(null), "video_loading_results").apply { mkdirs() }

    private fun fmt(time: Long) =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))

    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

    private fun parse(line: String): List<String> {
        val output = mutableListOf<String>()
        val buffer = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"') {
                buffer.append('"')
                i++
            } else if (c == '"') {
                quoted = !quoted
            } else if (c == ',' && !quoted) {
                output += buffer.toString()
                buffer.setLength(0)
            } else {
                buffer.append(c)
            }
            i++
        }
        output += buffer.toString()
        return output
    }

    companion object {
        const val HEADER = "sequence,start_ms,start_time,loaded_ms,delay_ms,result,detection,title,subscription_id,sim_slot,operator,rat,rsrp,rsrq,sinr,rssi,band,pci,arfcn,latitude,longitude"
    }
}
