package com.example.celltracker

import kotlinx.coroutines.flow.MutableStateFlow

enum class BasementStage(val label: String) {
    IDLE("Ready"),
    START_TO_B1("START → B1"),
    B1_STABILIZING("B1 · Stabilizing"),
    B1_PING("B1 · Ping Testing"),
    B1_COMPLETE("B1 Test Completed"),
    B1_TO_B2("B1 → B2"),
    B2_STABILIZING("B2 · Stabilizing"),
    B2_PING("B2 · Ping Testing"),
    B2_COMPLETE("B2 Test Completed"),
    B2_TO_B1("B2 → B1 Return"),
    B1_RETURN_STABILIZING("B1 Return · Stabilizing"),
    B1_RETURN_PING("B1 Return · Ping Testing"),
    B1_RETURN_COMPLETE("B1 Return Test Completed"),
    B1_TO_START("B1 → START Return"),
    RECOVERY("START · Recovery"),
    RECOVERY_COMPLETE("Recovery Completed"),
    FINISHED("Finished"),
    ABORTED("Aborted")
}

data class BasementTestConfig(
    val deviceLabel: String = "DUT",
    val host: String = "8.8.8.8",
    val stabilizeSeconds: Int = 30,
    val pingSeconds: Int = 60,
    val recoverySeconds: Int = 60,
    val selectedSubscriptionId: Int = -1
)

data class BasementNetworkSample(
    val timestampMs: Long,
    val stage: String,
    val segment: String,
    val simSlot: Int,
    val subscriptionId: Int,
    val operator: String,
    val rat: String,
    val displayRat: String,
    val registered: Boolean,
    val lteBand: String,
    val ltePci: String,
    val lteArfcn: String,
    val lteRsrp: String,
    val lteRsrq: String,
    val lteSinr: String,
    val lteCellId: String,
    val lteTac: String,
    val nrState: String,
    val nrBand: String,
    val nrPci: String,
    val nrArfcn: String,
    val nrSsRsrp: String,
    val nrSsRsrq: String,
    val nrSsSinr: String,
    val nrCellId: String,
    val nrTac: String,
    val dataState: String
)

data class BasementEvent(
    val timestampMs: Long,
    val segment: String,
    val type: String,
    val from: String = "",
    val to: String = "",
    val durationMs: Long? = null,
    val description: String = ""
)

data class BasementPingSample(
    val point: String,
    val timestampMs: Long,
    val sequence: Int,
    val success: Boolean,
    val rttMs: Double?,
    val message: String,
    val rat: String,
    val rsrp: String
)

data class BasementPointResult(
    val point: String,
    val sent: Int,
    val received: Int,
    val lossPct: Double,
    val avgRttMs: Double?,
    val minRttMs: Double?,
    val maxRttMs: Double?,
    val result: String
)

data class BasementLiveState(
    val isRunning: Boolean = false,
    val stage: BasementStage = BasementStage.IDLE,
    val stageStartedAt: Long = 0L,
    val sessionStartedAt: Long = 0L,
    val currentRat: String = "--",
    val currentRsrp: String = "--",
    val operator: String = "--",
    val countdownSeconds: Int? = null,
    val pingProgress: Int = 0,
    val pingTotal: Int = 0,
    val lastPointResult: BasementPointResult? = null,
    val lteRecoveryMs: Long? = null,
    val dataRecoveryMs: Long? = null,
    val nrRecoveryMs: Long? = null,
    val lteRecoveryRequired: Boolean = true,
    val nrRecoveryRequired: Boolean = true,
    val recoveryTimedOut: Boolean = false,
    val reportPath: String = "",
    val statusMessage: String = "Ready"
)

object BasementTestStore {
    val state = MutableStateFlow(BasementLiveState())
}
