package com.example.celltracker

import org.json.JSONObject

object DeviceLinkProtocol {
    const val VERSION = 1
    const val SERVICE_NAME = "CellTracker Device Link"
    val SERVICE_UUID: java.util.UUID = java.util.UUID.fromString("cf59e11e-78d1-4b3f-91d2-743bd5080f61")

    fun encode(message: DeviceLinkMessage): String = JSONObject().apply {
        put("protocol_version", message.protocolVersion)
        put("message_type", message.messageType)
        put("device_id", message.deviceId)
        put("session_id", message.sessionId)
        put("attempt_id", message.attemptId)
        put("timestamp", message.timestamp)
        put("payload", JSONObject(message.payload))
    }.toString() + "\n"

    fun decode(line: String): DeviceLinkMessage {
        val json = JSONObject(line)
        val payloadJson = json.optJSONObject("payload") ?: JSONObject()
        val payload = buildMap {
            val keys = payloadJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, payloadJson.optString(key, ""))
            }
        }
        return DeviceLinkMessage(
            protocolVersion = json.optInt("protocol_version", 0),
            messageType = json.getString("message_type"),
            deviceId = json.optString("device_id"),
            sessionId = json.optString("session_id"),
            attemptId = json.optString("attempt_id"),
            timestamp = json.optLong("timestamp"),
            payload = payload
        )
    }
}
