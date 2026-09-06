package com.example.celltracker

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.util.Properties

class CallSetupRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("call_setup", Context.MODE_PRIVATE)
    private val root = File(context.getExternalFilesDir(null), "call_setup_results").apply { mkdirs() }

    fun loadConfig() = CallSetupConfig(
        taskName = prefs.getString("task_name", "CallSetup") ?: "CallSetup",
        direction = runCatching { CallDirection.valueOf(prefs.getString("direction", CallDirection.A_TO_B.name)!!) }.getOrDefault(CallDirection.A_TO_B),
        callCount = prefs.getInt("count", 10), setupTimeoutMs = prefs.getLong("timeout", 30_000L),
        holdTimeMs = prefs.getLong("hold", 10_000L), interCallIntervalMs = prefs.getLong("interval", 10_000L),
        highLatencyThresholdMs = prefs.getLong("threshold", 8_000L), autoRecord = prefs.getBoolean("auto_record", true),
        aCallSimSlot = prefs.getInt("a_sim", 0), bCallSimSlot = prefs.getInt("b_sim", 0),
        automationMode = runCatching { AutomationMode.valueOf(prefs.getString("mode", AutomationMode.AUTO_WHEN_AVAILABLE.name)!!) }.getOrDefault(AutomationMode.AUTO_WHEN_AVAILABLE)
    )

    fun saveConfig(c: CallSetupConfig) = prefs.edit().putString("task_name", c.taskName).putString("direction", c.direction.name)
        .putInt("count", c.callCount).putLong("timeout", c.setupTimeoutMs).putLong("hold", c.holdTimeMs)
        .putLong("interval", c.interCallIntervalMs).putLong("threshold", c.highLatencyThresholdMs)
        .putBoolean("auto_record", c.autoRecord).putInt("a_sim", c.aCallSimSlot).putInt("b_sim", c.bCallSimSlot)
        .putString("mode", c.automationMode.name).apply()

    fun loadLocalSimSlot() = prefs.getInt("local_sim", 0)
    fun loadLocalNumber(simSlot: Int = loadLocalSimSlot()): String {
        val perSimKey = "phone_number_sim_$simSlot"
        if (prefs.contains(perSimKey)) return prefs.getString(perSimKey, "").orEmpty()
        // Migrate the v0.9.0 single identity only to the SIM it originally belonged to.
        return if (prefs.getInt("local_sim", 0) == simSlot) prefs.getString("phone_number", "").orEmpty() else ""
    }
    fun saveLocalSimSlot(simSlot: Int) = prefs.edit().putInt("local_sim", simSlot).apply()
    fun saveLocalIdentity(number: String, simSlot: Int) = prefs.edit()
        .putString("phone_number_sim_$simSlot", number.trim())
        .putString("phone_number", number.trim())
        .putInt("local_sim", simSlot)
        .apply()

    fun createSession(sessionId: String, config: CallSetupConfig, a: DeviceProfile, b: DeviceProfile, startedAt: Long): File {
        val safe = config.taskName.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "CallSetup" }.take(48)
        val dir = File(root, "${safe}_${startedAt}_$sessionId").apply { mkdirs() }
        File(dir, "attempts.csv").writeText(ATTEMPT_HEADER + "\n")
        File(dir, "snapshots.csv").writeText(SNAPSHOT_HEADER + "\n")
        File(dir, "events.csv").writeText("timestamp,event_source,event_type,attempt_id,direction,detail\n")
        writeMeta(dir, config, a, b, startedAt, 0L, "Running")
        return dir
    }

    fun appendAttempt(dir: File, result: CallAttemptResult) {
        FileWriter(File(dir, "attempts.csv"), true).use { w ->
            w.appendLine(csv(listOf(result.attemptNumber, result.attemptId, result.direction, result.startedAt, result.endedAt,
                result.dialAt ?: "", result.mtRingingAt ?: "", result.moConnectedAt ?: "", result.mtConnectedAt ?: "",
                result.callEndedAt ?: "", result.setupLatencyMs ?: "", result.result, result.confidence, result.failureDetail, result.bluetoothLost)))
        }
        result.snapshots.forEach { appendSnapshot(dir, result.attemptId, it) }
    }

    fun appendSnapshot(dir: File, attemptId: String, s: CallNetworkSnapshot) {
        FileWriter(File(dir, "snapshots.csv"), true).use { w -> w.appendLine(csv(listOf(
            attemptId, s.endpoint, s.moment, s.timestampMs, s.elapsedRealtimeMs, s.subscriptionId, s.simSlot, s.operator,
            s.rat, s.displayRat, s.voiceRat, s.mcc, s.mnc, s.tac, s.cellId, s.pci, s.arfcn, s.band, s.bandwidth,
            s.rsrp, s.rsrq, s.sinr, s.rssi, s.carrierAggregation, s.dataNetwork, s.latitude ?: "", s.longitude ?: "", s.speedKmh, s.accuracy
        ))) }
    }

    fun appendEvent(dir: File, timestamp: Long, type: String, attemptId: String, direction: String, detail: String) {
        FileWriter(File(dir, "events.csv"), true).use { it.appendLine(csv(listOf(timestamp, "AUTO", type, attemptId, direction, detail))) }
    }

    fun finish(dir: File, config: CallSetupConfig, a: DeviceProfile, b: DeviceProfile, startedAt: Long, endedAt: Long, status: String) =
        writeMeta(dir, config, a, b, startedAt, endedAt, status)

    private fun writeMeta(dir: File, c: CallSetupConfig, a: DeviceProfile, b: DeviceProfile, start: Long, end: Long, status: String) {
        Properties().apply {
            setProperty("session_id", dir.name.substringAfterLast('_')); setProperty("task_name", c.taskName)
            setProperty("direction", c.direction.name); setProperty("started_at", start.toString()); setProperty("ended_at", end.toString())
            setProperty("status", status); setProperty("device_a", a.deviceName); setProperty("device_b", b.deviceName)
            setProperty("device_a_id", a.deviceId); setProperty("device_b_id", b.deviceId)
            setProperty("operator_a", a.operator); setProperty("operator_b", b.operator)
            setProperty("phone_a", a.phoneNumber); setProperty("phone_b", b.phoneNumber)
            setProperty("threshold_ms", c.highLatencyThresholdMs.toString())
        }.store(File(dir, "session.properties").outputStream(), "CellTracker Call Setup Session")
    }

    fun loadHistory(): List<CallSetupHistoryItem> = root.listFiles { f -> f.isDirectory }?.mapNotNull { loadItem(it) }
        ?.sortedByDescending { it.startedAt } ?: emptyList()

    private fun loadItem(dir: File): CallSetupHistoryItem? = runCatching {
        val p = Properties().apply { File(dir, "session.properties").inputStream().use { load(it) } }
        val attempts = readAttempts(dir)
        val success = attempts.count { it.result == "SUCCESS" }
        val lat = attempts.mapNotNull { it.setupLatencyMs?.toDouble() }.sorted()
        fun pct(q: Double) = lat.takeIf { it.isNotEmpty() }?.get(kotlin.math.ceil((lat.size - 1) * q).toInt().coerceIn(0, lat.lastIndex))
        CallSetupHistoryItem(dir.absolutePath, p.getProperty("session_id", dir.name), p.getProperty("task_name", "CallSetup"),
            p.getProperty("device_a", "DUT A"), p.getProperty("device_b", "DUT B"), p.getProperty("operator_a", "--"),
            p.getProperty("operator_b", "--"), p.getProperty("direction", "--"), p.getProperty("started_at", "0").toLong(),
            p.getProperty("ended_at", "0").toLong().takeIf { it > 0 } ?: dir.lastModified(), attempts.size, success,
            lat.takeIf { it.isNotEmpty() }?.average(), pct(.9), pct(.95), p.getProperty("threshold_ms", "8000").toLong(), p.getProperty("status", "Unknown"))
    }.getOrNull()

    fun loadDetail(path: String): CallSetupDetail? {
        val dir = File(path); val item = loadItem(dir) ?: return null
        val snapshots = readSnapshots(dir).groupBy { it.first }
        return CallSetupDetail(item, readAttempts(dir).map { it.copy(snapshots = snapshots[it.attemptId].orEmpty().map { pair -> pair.second }) }, readEvents(dir))
    }

    private fun readAttempts(dir: File): List<CallAttemptResult> = File(dir, "attempts.csv").takeIf { it.exists() }?.readLines()?.drop(1)?.filter { it.isNotBlank() }?.mapNotNull { line ->
        runCatching { val x = parseCsv(line); CallAttemptResult(x[0].toInt(), x[1], x[2], x[3].toLong(), x[4].toLong(), x[5].toLongOrNull(), x[6].toLongOrNull(), x[7].toLongOrNull(), x[8].toLongOrNull(), x[9].toLongOrNull(), x[10].toLongOrNull(), x[11], x[12], x[13], x[14].toBoolean()) }.getOrNull()
    } ?: emptyList()

    private fun readSnapshots(dir: File): List<Pair<String, CallNetworkSnapshot>> = File(dir, "snapshots.csv").takeIf { it.exists() }?.readLines()?.drop(1)?.filter { it.isNotBlank() }?.mapNotNull { line ->
        runCatching { val x = parseCsv(line); x[0] to CallNetworkSnapshot(x[1], x[2], x[3].toLong(), x[4].toLong(), x[5].toInt(), x[6].toInt(), x[7], x[8], x[9], x[10], x[11], x[12], x[13], x[14], x[15], x[16], x[17], x[18], x[19], x[20], x[21], x[22], x[23], x[24], x[25].toDoubleOrNull(), x[26].toDoubleOrNull(), x[27], x[28]) }.getOrNull()
    } ?: emptyList()

    private fun readEvents(dir:File):List<CallSetupEvent> = File(dir,"events.csv").takeIf{it.exists()}?.readLines()?.drop(1)?.filter{it.isNotBlank()}?.mapNotNull{line->runCatching{val x=parseCsv(line);CallSetupEvent(x[0].toLong(),x[1],x[2],x[3],x[4],x[5])}.getOrNull()}?:emptyList()

    companion object {
        const val ATTEMPT_HEADER = "attempt_number,attempt_id,direction,started_at,ended_at,dial_at,mt_ringing_at,mo_connected_at,mt_connected_at,call_ended_at,setup_latency_ms,result,confidence,failure_detail,bluetooth_lost"
        const val SNAPSHOT_HEADER = "attempt_id,endpoint,moment,timestamp,elapsed_realtime,subscription_id,sim_slot,operator,rat,display_rat,voice_rat,mcc,mnc,tac,cell_id,pci,arfcn,band,bandwidth,rsrp,rsrq,sinr,rssi,ca_endc,data_network,latitude,longitude,speed_kmh,accuracy"
        fun csv(values: List<Any>) = values.joinToString(",") { value -> val s = value.toString().replace("\"", "\"\""); if (s.any { it == ',' || it == '\"' || it == '\n' }) "\"$s\"" else s }
        fun parseCsv(line: String): List<String> { val out=mutableListOf<String>(); val b=StringBuilder(); var q=false; var i=0; while(i<line.length){ val c=line[i]; when { c=='\"' && q && i+1<line.length && line[i+1]=='\"' -> { b.append('\"'); i++ }; c=='\"' -> q=!q; c==',' && !q -> { out+=b.toString(); b.clear() }; else -> b.append(c) }; i++ }; out+=b.toString(); return out }
    }
}
