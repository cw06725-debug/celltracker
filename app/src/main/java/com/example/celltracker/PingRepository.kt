package com.example.celltracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

class PingRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun loadConfig(): PingTestConfig {
        val host = prefs.getString("host", "8.8.8.8").orEmpty().ifBlank { "8.8.8.8" }
        return PingTestConfig(
            taskName = prefs.getString("task_name", "Ping_$host").orEmpty().ifBlank { "Ping_$host" },
            host = host,
            count = prefs.getInt("count", 20),
            intervalMs = prefs.getLong("interval_ms", 1000L),
            timeoutMs = prefs.getLong("timeout_ms", 2000L),
            highLatencyThresholdMs = prefs.getString("threshold_ms", "300")?.toDoubleOrNull() ?: 300.0,
            autoRecord = prefs.getBoolean("auto_record", true)
        )
    }

    fun saveConfig(config: PingTestConfig) {
        prefs.edit()
            .putString("task_name", config.taskName)
            .putString("host", config.host)
            .putInt("count", config.count)
            .putLong("interval_ms", config.intervalMs)
            .putLong("timeout_ms", config.timeoutMs)
            .putString("threshold_ms", config.highLatencyThresholdMs.toString())
            .putBoolean("auto_record", config.autoRecord)
            .apply()
    }

    fun createSessionFile(config: PingTestConfig, startedAt: Long): File {
        val task = sanitize(config.taskName).ifBlank { sanitize("Ping_${config.host}") }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        val file = File(resultsDir(), "Ping_${task}_$stamp.csv")
        FileWriter(file, false).use { it.appendLine(CSV_HEADER) }
        writeMetadata(file, startedAt, 0L, "Running", null)
        return file
    }

    fun appendSample(file: File, config: PingTestConfig, startedAt: Long, recordingPath: String?, sample: PingSample) {
        val s = sample.snapshot
        val values = listOf(
            sample.sequence.toString(), sample.timestampMs.toString(), timeFormat.format(Date(sample.timestampMs)),
            config.taskName, config.host, sample.success.toString(), sample.latencyMs?.let { String.format(Locale.US, "%.3f", it) }.orEmpty(),
            sample.message, sample.consecutiveFailures.toString(), sample.eventSource, sample.eventType,
            config.highLatencyThresholdMs.toString(), startedAt.toString(), recordingPath.orEmpty(),
            s.subscriptionId.toString(), s.simSlot.takeIf { it >= 0 }?.plus(1)?.toString().orEmpty(), s.operator,
            s.rat, s.displayRat, s.mcc, s.mnc, s.tac, s.cellId, s.pci, s.arfcn, s.band, s.bandwidth,
            s.rsrp, s.rsrq, s.sinr, s.rssi, s.carrierAggregation,
            s.dataSimSubscriptionId?.toString().orEmpty(), s.dataNetwork,
            s.latitude?.toString().orEmpty(), s.longitude?.toString().orEmpty(), s.speedKmh, s.gpsAccuracy
        )
        FileWriter(file, true).use { it.appendLine(values.joinToString(",", transform = ::escapeCsv)) }
    }

    fun finalizeSession(file: File, startedAt: Long, endedAt: Long, status: String, recordingPath: String?) {
        writeMetadata(file, startedAt, endedAt, status, recordingPath)
    }

    fun loadHistory(): List<PingHistoryItem> = resultsDir()
        .listFiles { file -> file.extension.equals("csv", true) }
        ?.mapNotNull { runCatching { loadDetail(it.absolutePath).item }.getOrNull() }
        ?.sortedByDescending { it.startedAt }
        .orEmpty()

    fun loadDetail(path: String): PingDetail {
        val file = File(path)
        require(file.exists()) { "Ping result file not found" }
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.isNotEmpty()) { "Ping result is empty" }
        val header = parseCsvLine(lines.first())
        val index = header.withIndex().associate { it.value to it.index }
        fun field(row: List<String>, name: String): String = row.getOrNull(index[name] ?: -1).orEmpty()
        val rows = lines.drop(1).map(::parseCsvLine)
        val samples = rows.mapNotNull { row ->
            val sequence = field(row, "sequence").toIntOrNull() ?: return@mapNotNull null
            PingSample(
                sequence = sequence,
                timestampMs = field(row, "timestamp_epoch_ms").toLongOrNull()
                    ?: runCatching { timeFormat.parse(field(row, "timestamp"))?.time }.getOrNull() ?: 0L,
                latencyMs = field(row, "latency_ms").toDoubleOrNull(),
                success = field(row, "success").equals("true", true),
                message = field(row, "message"),
                consecutiveFailures = field(row, "consecutive_failures").toIntOrNull() ?: 0,
                eventSource = field(row, "event_source"),
                eventType = field(row, "event_type"),
                snapshot = PingNetworkSnapshot(
                    subscriptionId = field(row, "subscription_id").toIntOrNull() ?: -1,
                    simSlot = (field(row, "sim_slot").toIntOrNull() ?: 0) - 1,
                    operator = field(row, "operator").ifBlank { "--" },
                    rat = field(row, "rat").ifBlank { "--" },
                    displayRat = field(row, "display_rat").ifBlank { "--" },
                    mcc = field(row, "mcc").ifBlank { "--" },
                    mnc = field(row, "mnc").ifBlank { "--" },
                    tac = field(row, "tac").ifBlank { "--" },
                    cellId = field(row, "cell_id").ifBlank { "--" },
                    pci = field(row, "pci").ifBlank { "--" },
                    arfcn = field(row, "arfcn").ifBlank { "--" },
                    band = field(row, "band").ifBlank { "--" },
                    bandwidth = field(row, "bandwidth").ifBlank { "--" },
                    rsrp = field(row, "rsrp").ifBlank { "--" },
                    rsrq = field(row, "rsrq").ifBlank { "--" },
                    sinr = field(row, "sinr").ifBlank { "--" },
                    rssi = field(row, "rssi").ifBlank { "--" },
                    carrierAggregation = field(row, "carrier_aggregation").ifBlank { "--" },
                    dataSimSubscriptionId = field(row, "data_sim_subscription_id").toIntOrNull(),
                    dataNetwork = field(row, "data_network").ifBlank { "--" },
                    latitude = field(row, "latitude").toDoubleOrNull(),
                    longitude = field(row, "longitude").toDoubleOrNull(),
                    speedKmh = field(row, "speed_kmh").ifBlank { "--" },
                    gpsAccuracy = field(row, "gps_accuracy").ifBlank { "--" }
                )
            )
        }
        val first = rows.firstOrNull().orEmpty()
        val properties = readMetadata(file)
        val startedAt = properties.getProperty("started_at")?.toLongOrNull()
            ?: field(first, "test_started_at_ms").toLongOrNull()
            ?: samples.firstOrNull()?.timestampMs ?: file.lastModified()
        val endedAt = properties.getProperty("ended_at")?.toLongOrNull()?.takeIf { it > 0L }
            ?: samples.lastOrNull()?.timestampMs ?: startedAt
        val values = samples.mapNotNull { it.latencyMs }.sorted()
        fun percentile(p: Double): Double? {
            if (values.isEmpty()) return null
            return values[kotlin.math.ceil((values.size - 1) * p).toInt().coerceIn(0, values.lastIndex)]
        }
        val item = PingHistoryItem(
            path = file.absolutePath,
            taskName = field(first, "task_name").ifBlank { file.nameWithoutExtension },
            host = field(first, "host").ifBlank { "--" },
            startedAt = startedAt,
            endedAt = endedAt,
            packetCount = samples.size,
            receivedCount = samples.count { it.success },
            averageLatencyMs = values.takeIf { it.isNotEmpty() }?.average(),
            minLatencyMs = values.minOrNull(),
            maxLatencyMs = values.maxOrNull(),
            p50LatencyMs = percentile(0.50),
            p90LatencyMs = percentile(0.90),
            p95LatencyMs = percentile(0.95),
            highPingCount = samples.count { it.eventType == "HIGH_PING" },
            timeoutEventCount = samples.count { it.eventType == "PING_TIMEOUT" },
            highLatencyThresholdMs = field(first, "threshold_ms").toDoubleOrNull() ?: 300.0,
            recordingPath = properties.getProperty("recording_path")?.takeIf { it.isNotBlank() }
                ?: field(first, "recording_path").takeIf { it.isNotBlank() },
            status = properties.getProperty("status", "Completed")
        )
        return PingDetail(item, samples)
    }

    private fun writeMetadata(file: File, startedAt: Long, endedAt: Long, status: String, recordingPath: String?) {
        val properties = Properties().apply {
            setProperty("started_at", startedAt.toString())
            setProperty("ended_at", endedAt.toString())
            setProperty("status", status)
            setProperty("recording_path", recordingPath.orEmpty())
        }
        metadataFile(file).outputStream().use { properties.store(it, "CellTracker Ping session") }
    }

    private fun readMetadata(file: File): Properties = Properties().apply {
        val metadata = metadataFile(file)
        if (metadata.exists()) runCatching { metadata.inputStream().use(::load) }
    }

    private fun metadataFile(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.meta")
    private fun resultsDir() = File(context.getExternalFilesDir(null), "ping_results").apply { mkdirs() }

    companion object {
        private const val PREFS = "celltracker_ping"
        const val CSV_HEADER = "sequence,timestamp_epoch_ms,timestamp,task_name,host,success,latency_ms,message,consecutive_failures,event_source,event_type,threshold_ms,test_started_at_ms,recording_path,subscription_id,sim_slot,operator,rat,display_rat,mcc,mnc,tac,cell_id,pci,arfcn,band,bandwidth,rsrp,rsrq,sinr,rssi,carrier_aggregation,data_sim_subscription_id,data_network,latitude,longitude,speed_kmh,gps_accuracy"

        fun sanitize(value: String): String = value
            .replace(Regex("[\\/:*?\"<>|\\r\\n]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', ' ')
            .take(48)

        fun escapeCsv(value: String): String {
            val safe = value.replace("\"", "\"\"")
            return if (safe.contains(',') || safe.contains('"') || safe.contains('\n')) "\"$safe\"" else safe
        }

        fun parseCsvLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var index = 0
            while (index < line.length) {
                val char = line[index]
                when {
                    char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> { current.append('"'); index++ }
                    char == '"' -> quoted = !quoted
                    char == ',' && !quoted -> { out += current.toString(); current.setLength(0) }
                    else -> current.append(char)
                }
                index++
            }
            out += current.toString()
            return out
        }
    }
}

object PingTestStore {
    val state = kotlinx.coroutines.flow.MutableStateFlow(PingTestState())
}
