package com.example.celltracker

import kotlinx.coroutines.flow.MutableStateFlow

data class RecordingStatus(
    val isRecording: Boolean = false,
    val startedAt: Long = 0L,
    val samples: Long = 0L,
    val latestPath: String? = null
)

object RecordingState {
    val status = MutableStateFlow(RecordingStatus())
}
