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
    val bearing: String = "--"
)

enum class MarkerAction(val label: String) {
    QUICK_MARK("Quick mark"),
    MARK_WITH_SCREENSHOT("Mark + screenshot"),
    EVENT_MENU("Open event type menu"),
    CUSTOM_NOTE("Custom note"),
    NONE("No action")
}

data class AppSettings(
    val uiRefreshMs: Long = 1000L,
    val recordIntervalMs: Long = 1000L,
    val tapAction: MarkerAction = MarkerAction.QUICK_MARK,
    val longPressAction: MarkerAction = MarkerAction.MARK_WITH_SCREENSHOT,
    val vibrateOnMark: Boolean = true,
    val toastOnMark: Boolean = true,
    val soundOnMark: Boolean = false
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
    val exportMessage: String? = null,
    val error: String? = null,
    val lastUpdated: String = "--"
)
