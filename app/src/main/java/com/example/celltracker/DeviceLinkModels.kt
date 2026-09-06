package com.example.celltracker

import kotlinx.coroutines.flow.MutableStateFlow

enum class DeviceLinkRole { CONTROLLER, AGENT }
enum class DeviceLinkStatus {
    IDLE, SCANNING, DEVICE_FOUND, PAIRING, WAITING, CONNECTING, CONNECTED,
    RECONNECTING, PERMISSION_REQUIRED, BLUETOOTH_OFF, CONNECTION_FAILED,
    DISCONNECTED, DISCOVERING, LISTENING, ERROR
}
enum class CallDirection { A_TO_B, B_TO_A, BIDIRECTIONAL_BLOCK, BIDIRECTIONAL_ALTERNATE }
enum class AutomationMode { AUTO_WHEN_AVAILABLE, SEMI_AUTO }

data class BluetoothPeer(
    val name: String = "Unknown",
    val deviceId: String = "",
    val address: String = "",
    val bonded: Boolean = false
)

data class DeviceProfile(
    val deviceName: String = android.os.Build.MODEL,
    val deviceId: String = "",
    val phoneNumber: String = "",
    val simSlot: Int = 0,
    val subscriptionId: Int = -1,
    val operator: String = "--",
    val rat: String = "--",
    val voiceRat: String = "--",
    val signal: String = "--",
    val batteryPercent: Int = -1,
    val appVersion: String = BuildConfig.VERSION_NAME,
    val phoneNumberSim1: String = "",
    val phoneNumberSim2: String = ""
) {
    fun phoneForSlot(slot: Int): String = when(slot) { 0 -> phoneNumberSim1.ifBlank { if(simSlot==0) phoneNumber else "" }; 1 -> phoneNumberSim2.ifBlank { if(simSlot==1) phoneNumber else "" }; else -> "" }
}

data class DeviceLinkState(
    val role: DeviceLinkRole = DeviceLinkRole.CONTROLLER,
    val status: DeviceLinkStatus = DeviceLinkStatus.IDLE,
    val bluetoothEnabled: Boolean = false,
    val permissionGranted: Boolean = false,
    val discoverable: Boolean = false,
    val discoveryActive: Boolean = false,
    val statusMessage: String = "Idle",
    val localProfile: DeviceProfile = DeviceProfile(),
    val peer: BluetoothPeer? = null,
    val peerProfile: DeviceProfile? = null,
    val pairedDevices: List<BluetoothPeer> = emptyList(),
    val discoveredDevices: List<BluetoothPeer> = emptyList(),
    val latencyMs: Double? = null,
    val clockOffsetMs: Double? = null,
    val lastHeartbeatMs: Long = 0L,
    val reconnectEnabled: Boolean = true
)

data class DeviceLinkMessage(
    val protocolVersion: Int = 1,
    val messageType: String,
    val deviceId: String,
    val sessionId: String = "",
    val attemptId: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val payload: Map<String, String> = emptyMap()
)

data class CallSetupConfig(
    val taskName: String = "CallSetup",
    val direction: CallDirection = CallDirection.A_TO_B,
    val callCount: Int = 10,
    val setupTimeoutMs: Long = 30_000L,
    val holdTimeMs: Long = 10_000L,
    val interCallIntervalMs: Long = 10_000L,
    val highLatencyThresholdMs: Long = 8_000L,
    val autoRecord: Boolean = true,
    val aCallSimSlot: Int = 0,
    val bCallSimSlot: Int = 0,
    val automationMode: AutomationMode = AutomationMode.AUTO_WHEN_AVAILABLE
)

data class CallNetworkSnapshot(
    val endpoint: String = "A",
    val moment: String = "BEFORE_DIAL",
    val timestampMs: Long = 0L,
    val elapsedRealtimeMs: Long = 0L,
    val subscriptionId: Int = -1,
    val simSlot: Int = -1,
    val operator: String = "--",
    val rat: String = "--",
    val displayRat: String = "--",
    val voiceRat: String = "--",
    val mcc: String = "--",
    val mnc: String = "--",
    val tac: String = "--",
    val cellId: String = "--",
    val pci: String = "--",
    val arfcn: String = "--",
    val band: String = "--",
    val bandwidth: String = "--",
    val rsrp: String = "--",
    val rsrq: String = "--",
    val sinr: String = "--",
    val rssi: String = "--",
    val carrierAggregation: String = "--",
    val dataNetwork: String = "--",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedKmh: String = "--",
    val accuracy: String = "--"
)

data class CallAttemptResult(
    val attemptNumber: Int,
    val attemptId: String,
    val direction: String,
    val startedAt: Long,
    val endedAt: Long,
    val dialAt: Long? = null,
    val mtRingingAt: Long? = null,
    val moConnectedAt: Long? = null,
    val mtConnectedAt: Long? = null,
    val callEndedAt: Long? = null,
    val setupLatencyMs: Long? = null,
    val result: String,
    val confidence: String = "MEDIUM_PUBLIC_API",
    val failureDetail: String = "",
    val bluetoothLost: Boolean = false,
    val snapshots: List<CallNetworkSnapshot> = emptyList()
)

data class CallSetupHistoryItem(
    val path: String,
    val sessionId: String,
    val taskName: String,
    val deviceA: String,
    val deviceB: String,
    val operatorA: String,
    val operatorB: String,
    val direction: String,
    val startedAt: Long,
    val endedAt: Long,
    val attempts: Int,
    val success: Int,
    val averageMs: Double?,
    val p90Ms: Double?,
    val p95Ms: Double?,
    val highLatencyThresholdMs: Long,
    val status: String
) {
    val failure: Int get() = (attempts - success).coerceAtLeast(0)
    val successRate: Double get() = if (attempts == 0) 0.0 else success * 100.0 / attempts
}

data class CallSetupEvent(val timestampMs:Long,val source:String,val type:String,val attemptId:String,val direction:String,val detail:String)
data class CallSetupDetail(val item: CallSetupHistoryItem, val attempts: List<CallAttemptResult>, val events: List<CallSetupEvent> = emptyList())

data class CallSetupTestState(
    val isRunning: Boolean = false,
    val config: CallSetupConfig = CallSetupConfig(),
    val sessionId: String = "",
    val currentAttempt: Int = 0,
    val currentDirection: String = "--",
    val localRole: String = "--",
    val peerRole: String = "--",
    val localCallState: String = "IDLE",
    val peerCallState: String = "IDLE",
    val statusMessage: String = "Ready",
    val attempts: List<CallAttemptResult> = emptyList(),
    val currentSetupLatencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val resultPath: String? = null,
    val automationCapability: String = "Checking",
    val localSnapshot: CallNetworkSnapshot? = null,
    val peerSnapshot: CallNetworkSnapshot? = null
) {
    val success: Int get() = attempts.count { it.result == "SUCCESS" }
    val failure: Int get() = attempts.size - success
    val successRate: Double get() = if (attempts.isEmpty()) 0.0 else success * 100.0 / attempts.size
    private val latencies get() = attempts.mapNotNull { it.setupLatencyMs?.toDouble() }.sorted()
    val averageMs: Double? get() = latencies.takeIf { it.isNotEmpty() }?.average()
    val minMs: Double? get() = latencies.minOrNull()
    val maxMs: Double? get() = latencies.maxOrNull()
    val p50Ms: Double? get() = percentile(.50)
    val p90Ms: Double? get() = percentile(.90)
    val p95Ms: Double? get() = percentile(.95)
    private fun percentile(p: Double): Double? {
        if (latencies.isEmpty()) return null
        return latencies[kotlin.math.ceil((latencies.size - 1) * p).toInt().coerceIn(0, latencies.lastIndex)]
    }
}

object DeviceLinkStore {
    val link = MutableStateFlow(DeviceLinkState())
    val callTest = MutableStateFlow(CallSetupTestState())
}

object CallResultCodes {
    const val SUCCESS="SUCCESS"; const val MO_DIAL_FAILED="MO_DIAL_FAILED"; const val MT_NO_INCOMING_CALL="MT_NO_INCOMING_CALL"
    const val SETUP_TIMEOUT="SETUP_TIMEOUT"; const val MO_NOT_CONNECTED="MO_NOT_CONNECTED"; const val MT_NOT_CONNECTED="MT_NOT_CONNECTED"
    const val BUSY="BUSY"; const val REJECTED="REJECTED"; const val NO_ANSWER="NO_ANSWER"
    const val DISCONNECTED_BEFORE_CONNECTED="DISCONNECTED_BEFORE_CONNECTED"; const val BLUETOOTH_LINK_LOST="BLUETOOTH_LINK_LOST"; const val UNKNOWN_FAILURE="UNKNOWN_FAILURE"
}
