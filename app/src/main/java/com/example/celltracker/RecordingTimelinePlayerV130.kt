package com.example.celltracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast

/** Wall clock rendered only during playback, never included in the raw screen capture. */
class RecordingTimelinePlayerV130 : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var video: VideoView? = null
    private var ticker: Runnable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val record = RecordingTimelineV130.last(this)
        if (record == null) {
            Toast.makeText(this, "No completed CellTracker timestamped recording", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val (uri, session) = record
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val videoView = VideoView(this)
        video = videoView
        frame.addView(videoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val clock = TextView(this).apply {
            text = "Video 00:00 · ${RecordingTimelineV130.clockFormat(session.wallAt(0L))} · estimated"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA111827.toInt())
            setPadding(16, 12, 16, 12)
        }
        frame.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(frame)
        val controls = MediaController(this)
        controls.setAnchorView(videoView)
        videoView.setMediaController(controls)
        videoView.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "Unable to play saved recording ($what/$extra)", Toast.LENGTH_LONG).show()
            true
        }
        videoView.setOnPreparedListener { it.isLooping = false; videoView.start() }
        videoView.setVideoURI(uri)
        val update = object : Runnable {
            override fun run() {
                val ms = videoView.currentPosition.toLong().coerceAtLeast(0L)
                val seconds = ms / 1000L
                clock.text = "Video %02d:%02d · %s · estimated".format(seconds / 60, seconds % 60,
                    RecordingTimelineV130.clockFormat(session.wallAt(ms)))
                handler.postDelayed(this, 250L)
            }
        }
        ticker = update
        handler.post(update)
    }
    override fun onDestroy() {
        ticker?.let { handler.removeCallbacks(it) }
        video?.stopPlayback()
        super.onDestroy()
    }
    companion object {
        fun open(context: Context) {
            context.startActivity(Intent(context, RecordingTimelinePlayerV130::class.java))
        }
    }
}
