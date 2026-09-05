package com.example.celltracker

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

object RecordingDetailRepository {
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun load(path: String, fallbackItem: RecordingItem? = null): RecordingDetail {
        val file = File(path)
        val samples = loadSamples(path)
        require(samples.isNotEmpty()) { "Recording has no samples" }
        val item = fallbackItem ?: buildItem(file, samples)
        return RecordingDetail(item, samples)
    }

    fun loadSamples(path: String): List<TrackSample> {
        val file = File(path)
        require(file.exists()) { "Recording file not found" }
        val lines = file.readLines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val header = parseCsvLine(lines.first())
        val index = header.withIndex().associate { it.value to it.index }
        fun field(fields: List<String>, name: String): String = fields.getOrNull(index[name] ?: -1).orEmpty()

        val samples = lines.drop(1).mapNotNull { line ->
            runCatching {
                val f = parseCsvLine(line)
                val ts = timeFormat.parse(field(f, "timestamp"))?.time ?: return@runCatching null
                val lat = field(f, "latitude").toDoubleOrNull()
                val lon = field(f, "longitude").toDoubleOrNull()
                TrackSample(
                    timestampMs = ts,
                    simSlot = field(f, "sim_slot").toIntOrNull() ?: 1,
                    subscriptionId = field(f, "subscription_id").toIntOrNull() ?: -1,
                    operator = field(f, "operator"),
                    rat = field(f, "rat"),
                    displayRat = field(f, "display_rat"),
                    mcc = field(f, "mcc"),
                    mnc = field(f, "mnc"),
                    tac = field(f, "tac"),
                    cellId = field(f, "cell_id"),
                    pci = field(f, "pci"),
                    arfcn = field(f, "arfcn"),
                    rsrp = field(f, "rsrp"),
                    rsrq = field(f, "rsrq"),
                    sinr = field(f, "sinr"),
                    band = field(f, "band").ifBlank { "--" },
                    bandwidth = field(f, "bandwidth").ifBlank { "--" },
                    rssi = field(f, "rssi").ifBlank { "--" },
                    timingAdvance = field(f, "timing_advance").ifBlank { "--" },
                    csiRsrp = field(f, "csi_rsrp").ifBlank { "--" },
                    csiRsrq = field(f, "csi_rsrq").ifBlank { "--" },
                    csiSinr = field(f, "csi_sinr").ifBlank { "--" },
                    latitude = lat,
                    longitude = lon,
                    altitude = field(f, "altitude"),
                    accuracy = field(f, "accuracy"),
                    speedKmh = field(f, "speed_kmh"),
                    bearing = field(f, "bearing"),
                    locationValid = lat != null && lon != null && (field(f, "location_valid").isBlank() || field(f, "location_valid").equals("true", true)),
                    isMarker = field(f, "is_marker").equals("true", true),
                    eventType = field(f, "event_type"),
                    eventNote = field(f, "event_note"),
                    screenshot = field(f, "screenshot")
                )
            }.getOrNull()
        }.filterNotNull()

        return samples
    }

    private fun buildItem(file: File, samples: List<TrackSample>): RecordingItem {
        val start = samples.firstOrNull()?.timestampMs ?: file.lastModified()
        val end = samples.lastOrNull()?.timestampMs ?: start
        val sims = samples.groupBy { it.simSlot }.entries.sortedBy { it.key }.map { (slot, rows) ->
            "SIM $slot ${rows.firstOrNull()?.operator.orEmpty()}".trim()
        }
        return RecordingItem(
            path = file.absolutePath,
            name = file.name,
            startedAt = start,
            durationMs = (end - start).coerceAtLeast(0),
            totalSamples = samples.size.toLong(),
            simSummary = sims.joinToString(" + "),
            simCount = sims.size.coerceAtLeast(1)
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += current.toString(); current.setLength(0) }
                else -> current.append(ch)
            }
            i++
        }
        out += current.toString()
        return out
    }
}
