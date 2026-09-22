package com.example.celltracker

data class ReviewPlayerRequest(
    val reportUri:String,
    val eventIndex:Int,
    val videoUri:String,
    val recordingStartMs:Long,
    val initialPositionMs:Long,
    val eventLabel:String
)
