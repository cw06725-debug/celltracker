package com.example.celltracker

data class CellData(
    val subscriptionId: Int = -1,
    val simSlotIndex: Int = -1,
    val simLabel: String = "--",
    val rat: String = "--",
    val displayRat: String = "--",
    val operator: String = "--",
    val mcc: String = "--",
    val mnc: String = "--",
    val tac: String = "--",
    val cellId: String = "--",
    val pci: String = "--",
    val arfcn: String = "--",
    val rsrp: String = "--",
    val rsrq: String = "--",
    val sinr: String = "--",
    val registered: Boolean = false
)

data class SimCellState(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val simLabel: String,
    val servingCell: CellData = CellData(),
    val neighbors: List<CellData> = emptyList()
)

data class LocationData(
    val latitude: String = "--",
    val longitude: String = "--",
    val altitude: String = "--",
    val accuracy: String = "--",
    val speedKmh: String = "--",
    val bearing: String = "--",
    val isValid: Boolean = false,
    val timestampMs: Long = 0L
)

enum class MarkerAction(val label: String) {
    QUICK_MARK("Quick mark"),
    MARK_WITH_SCREENSHOT("Mark + screenshot"),
    EVENT_MENU("Open event type menu"),
    CUSTOM_NOTE("Custom note"),
    NONE("No action")
}

enum class RecordScope { CURRENT_SIM, BOTH_SIMS }

data class RecordingItem(
    val path: String, val name: String, val startedAt: Long, val durationMs: Long,
    val totalSamples: Long, val simSummary: String, val simCount: Int = 1
)

data class AppSettings(
    val uiRefreshMs: Long = 1000L,
    val recordIntervalMs: Long = 1000L,
    val tapAction: MarkerAction = MarkerAction.QUICK_MARK,
    val longPressAction: MarkerAction = MarkerAction.MARK_WITH_SCREENSHOT,
    val vibrateOnMark: Boolean = true,
    val toastOnMark: Boolean = true,
    val soundOnMark: Boolean = false,
    val recordScope: RecordScope = RecordScope.CURRENT_SIM
)

data class AppState(
    val sims: List<SimCellState> = emptyList(),
    val selectedSubscriptionId: Int? = null,
    val location: LocationData = LocationData(),
    val settings: AppSettings = AppSettings(),
    val isRecording: Boolean = false,
    val recordingElapsedMs: Long = 0L,
    val recordingSamples: Long = 0L,
    val recordingSamplesBySubscription: Map<Int, Long> = emptyMap(),
    val latestRecordingPath: String? = null,
    val recordingLocationValid: Boolean = false,
    val recordingLocationAgeMs: Long = Long.MAX_VALUE,
    val recordings: List<RecordingItem> = emptyList(),
    val exportMessage: String? = null,
    val error: String? = null,
    val lastUpdated: String = "--"
)

data class TrackSample(
    val timestampMs: Long,
    val simSlot: Int,
    val subscriptionId: Int,
    val operator: String,
    val rat: String,
    val displayRat: String,
    val mcc: String,
    val mnc: String,
    val tac: String,
    val cellId: String,
    val pci: String,
    val arfcn: String,
    val rsrp: String,
    val rsrq: String,
    val sinr: String,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: String,
    val accuracy: String,
    val speedKmh: String,
    val bearing: String,
    val locationValid: Boolean,
    val isMarker: Boolean = false,
    val eventType: String = "",
    val eventNote: String = "",
    val screenshot: String = ""
)

data class RecordingDetail(
    val item: RecordingItem,
    val samples: List<TrackSample>
) {
    val simSlots: List<Int> get() = samples.map { it.simSlot }.distinct().sorted()
}
