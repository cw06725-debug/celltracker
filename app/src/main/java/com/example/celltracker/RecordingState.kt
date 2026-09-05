package com.example.celltracker

import kotlinx.coroutines.flow.MutableStateFlow

data class RecordingStatus(
    val isRecording: Boolean = false,
    val startedAt: Long = 0L,
    val totalSamples: Long = 0L,
    val samplesBySubscription: Map<Int, Long> = emptyMap(),
    val latestPath: String? = null,
    val locationValid: Boolean = false,
    val locationAgeMs: Long = Long.MAX_VALUE
)

object RecordingState {
    val status = MutableStateFlow(RecordingStatus())
}
