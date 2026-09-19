package com.example.celltracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ScenarioPhaseV1 {
    READY, BASELINE, ACTIVE, POWER_OUT, RECOVERY, FINISHED
}

data class ScenarioEventV1(
    val id: String = UUID.randomUUID().toString(),
    val timeMs: Long = System.currentTimeMillis(),
    val type: String,
    val note: String = ""
)

data class ScenarioSessionV1(
    val id: String = UUID.randomUUID().toString(),
    val scenario: String,
    val location: String,
    val operator: String,
    val dut: String,
    val ref: String,
    val testItems: List<String>,
    val startMs: Long = System.currentTimeMillis(),
    val endMs: Long? = null,
    val phase: ScenarioPhaseV1 = ScenarioPhaseV1.READY,
    val events: List<ScenarioEventV1> = emptyList()
) {
    fun displayTime(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startMs))
}

object ScenarioSessionStoreV1 {
    private const val PREF = "scenario_sessions_v1"
    private const val KEY = "sessions"

    fun save(context: Context, session: ScenarioSessionV1) {
        val all = load(context).toMutableList()
        val i = all.indexOfFirst { it.id == session.id }
        if (i >= 0) all[i] = session else all.add(0, session)
        persist(context, all.take(100))
    }

    fun load(context: Context): List<ScenarioSessionV1> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun addEvent(context: Context, sessionId: String, type: String, note: String = ""): ScenarioSessionV1? {
        val all = load(context).toMutableList()
        val i = all.indexOfFirst { it.id == sessionId }
        if (i < 0) return null
        val s = all[i]
        val updated = s.copy(events = s.events + ScenarioEventV1(type = type, note = note))
        all[i] = updated
        persist(context, all)
        return updated
    }

    fun setPhase(context: Context, sessionId: String, phase: ScenarioPhaseV1): ScenarioSessionV1? {
        val all = load(context).toMutableList()
        val i = all.indexOfFirst { it.id == sessionId }
        if (i < 0) return null
        var s = all[i].copy(phase = phase)
        if (phase == ScenarioPhaseV1.FINISHED) {
            s = s.copy(endMs = System.currentTimeMillis(),
                events = s.events + ScenarioEventV1(type = "SESSION_FINISHED"))
        }
        all[i] = s
        persist(context, all)
        return s
    }

    private fun persist(context: Context, sessions: List<ScenarioSessionV1>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(s: ScenarioSessionV1) = JSONObject().apply {
        put("id", s.id); put("scenario", s.scenario); put("location", s.location)
        put("operator", s.operator); put("dut", s.dut); put("ref", s.ref)
        put("startMs", s.startMs); put("endMs", s.endMs ?: JSONObject.NULL)
        put("phase", s.phase.name)
        put("testItems", JSONArray(s.testItems))
        put("events", JSONArray().apply {
            s.events.forEach { e -> put(JSONObject().apply {
                put("id", e.id); put("timeMs", e.timeMs); put("type", e.type); put("note", e.note)
            })}
        })
    }

    private fun fromJson(o: JSONObject): ScenarioSessionV1 {
        val items = o.optJSONArray("testItems") ?: JSONArray()
        val events = o.optJSONArray("events") ?: JSONArray()
        return ScenarioSessionV1(
            id=o.optString("id"), scenario=o.optString("scenario"),
            location=o.optString("location"), operator=o.optString("operator"),
            dut=o.optString("dut"), ref=o.optString("ref"),
            testItems=(0 until items.length()).map { items.optString(it) },
            startMs=o.optLong("startMs"),
            endMs=if (o.isNull("endMs")) null else o.optLong("endMs"),
            phase=runCatching { ScenarioPhaseV1.valueOf(o.optString("phase","READY")) }
                .getOrDefault(ScenarioPhaseV1.READY),
            events=(0 until events.length()).map {
                val e=events.getJSONObject(it)
                ScenarioEventV1(e.optString("id"),e.optLong("timeMs"),e.optString("type"),e.optString("note"))
            }
        )
    }
}
