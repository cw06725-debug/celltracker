package com.example.celltracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import android.widget.Toast
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Post-test frame review never changes the original MP4 or displays an overlay during the test.
 *  All wall-clock mappings remain explicitly ESTIMATED until first-frame PTS calibration exists.
 */
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
        val prefs = getSharedPreferences("ct_recording_timeline", MODE_PRIVATE)
        val reviewEnabled = prefs.getString("reviewVideoUri", "") == uri.toString() &&
            prefs.getString("reviewKind", "") in setOf("LIVE", "GAME")
        val events = mutableListOf<Pair<Long,Long>>()
        if (reviewEnabled) {
            runCatching {
                val js = JSONArray(prefs.getString("reviewedEvents", "[]"))
                for (i in 0 until js.length()) {
                    val entry=js.getJSONObject(i)
                    val first=entry.getLong("start")
                    val last=entry.getLong("end")
                    if(first>=0L && last>first && last<=session.videoDurationMs) events += first to last
                }
            }
        }
        var markedStart: Long? = null
        var saving = false
        val base = LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.BLACK)}
        val frame = FrameLayout(this).apply {setBackgroundColor(Color.BLACK)}
        val videoView = VideoView(this)
        video = videoView
        frame.addView(videoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val clock = TextView(this).apply {
            text = "Video 00:00 · ${RecordingTimelineV130.clockFormat(session.wallAt(0L))} · estimated"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xBB17202C.toInt())
            setPadding(16, 12, 16, 12)
        }
        frame.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        base.addView(frame,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))

        val controls = LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(14,8,14,20);setBackgroundColor(0xFF24222D.toInt())}
        val reviewTitle = TextView(this).apply {text="Recording review · no in-game overlay";textSize=15f;setTextColor(Color.WHITE)}
        val status = TextView(this).apply {textSize=12f;setTextColor(0xFFCEEAD7.toInt());setPadding(0,7,0,7)}
        controls.addView(reviewTitle)
        controls.addView(status)
        fun persist() {
            val arr=JSONArray()
            events.forEach { (a,b)->arr.put(JSONObject().put("start",a).put("end",b)) }
            prefs.edit().putString("reviewedEvents",arr.toString()).apply()
        }
        fun currentMs(): Long=videoView.currentPosition.toLong().coerceIn(0L,session.videoDurationMs)
        fun refresh(note: String = "") {
            val prefix=if(markedStart!=null) "Stall started at ${RecordingTimelineV130.subtitleTime(markedStart!!)} · mark END" else "${events.size} marked stalls"
            status.text=if(note.isEmpty())prefix else "$prefix\n$note"
        }
        val row = LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        fun btn(text:String, action:()->Unit) = Button(this).apply {
            this.text=text;this.textSize=11f;this.isAllCaps=false;this.setSingleLine(true)
            setOnClickListener{action()}
        }
        row.addView(btn("MARK START") {
            videoView.pause()
            markedStart=currentMs()
            refresh("Start ${RecordingTimelineV130.clockFormat(session.wallAt(markedStart!!))} · estimated")
        },LinearLayout.LayoutParams(0,(48f*resources.displayMetrics.density).toInt(),1f))
        row.addView(btn("MARK END") {
            videoView.pause()
            val a=markedStart
            val b=currentMs()
            if(a==null || b<=a) Toast.makeText(this,"Set a start first, then seek forward to end",Toast.LENGTH_SHORT).show()
            else {
                events += a to b
                markedStart=null
                persist()
                refresh("Saved event #${events.size}: %.2fs · ESTIMATED".format(Locale.US,(b-a)/1000.0))
            }
        },LinearLayout.LayoutParams(0,(48f*resources.displayMetrics.density).toInt(),1f))
        row.addView(btn("UNDO") {
            if(markedStart!=null)markedStart=null
            else if(events.isNotEmpty()){events.removeAt(events.lastIndex);persist()}
            refresh()
        },LinearLayout.LayoutParams(0,(48f*resources.displayMetrics.density).toInt(),0.7f))
        controls.addView(row)
        lateinit var exportButton: Button
        exportButton=btn("EXPORT REVIEWED STALL REPORT · CSV + XLSX") {
            if(saving || events.isEmpty()) {
                if(events.isEmpty())Toast.makeText(this,"Mark at least one complete stall first",Toast.LENGTH_SHORT).show()
            } else if (!reviewEnabled) {
                Toast.makeText(this,"Only PUBG/Tamasha recordings support stall report export",Toast.LENGTH_LONG).show()
            } else {
                val kind=runCatching {ManualStallKindV130.valueOf(prefs.getString("reviewKind","").orEmpty())}.getOrNull()
                if(kind==null)Toast.makeText(this,"Recording test type missing",Toast.LENGTH_LONG).show()
                else {
                    val snapshot=events.toList()
                    val signature=uri.toString()+"|"+snapshot.joinToString(";") { "${it.first}:${it.second}" }
                    if(prefs.getString("lastReviewExportSignature", "") == signature) {
                        Toast.makeText(this,"These annotations were already exported",Toast.LENGTH_SHORT).show()
                    } else {
                    saving=true;exportButton.isEnabled=false
                    Thread {
                        val result=runCatching {
                            val from=session.wallAt(0L)
                            val to=session.wallAt(session.videoDurationMs)
                            ManualStallReportV130.save(this,kind,
                                prefs.getString("reviewTask",kind.label).orEmpty().ifBlank{kind.label},
                                prefs.getString("reviewRole","DUT1").orEmpty(),from,to,
                                snapshot.map { (a,b)->ManualStallEventV130(session.wallAt(a),session.wallAt(b)) },
                                prefs.getString("reviewOperator", "").orEmpty(),
                                prefs.getString("reviewRat", "").orEmpty(),
                                prefs.getString("reviewBand", "").orEmpty(),
                                prefs.getString("reviewPci", "").orEmpty(),
                                prefs.getString("reviewRsrp", "").orEmpty(),
                                prefs.getString("reviewSinr", "").orEmpty(),
                                prefs.getString("reviewAppVersion","").orEmpty(),"VIDEO_REVIEW_ESTIMATED")
                        }
                        runOnUiThread {
                            saving=false;exportButton.isEnabled=true
                            result.onSuccess { prefs.edit().putString("lastReviewExportSignature",signature).apply();Toast.makeText(this,"Reviewed stall CSV + XLSX saved to Reports",Toast.LENGTH_LONG).show();refresh("${snapshot.size} events exported · timeline ESTIMATED") }
                                .onFailure { Toast.makeText(this,"Export failed: ${it.message}",Toast.LENGTH_LONG).show() }
                        }
                    }.start()
                    }
                }
            }
        }
        controls.addView(exportButton,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        base.addView(controls,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(base)
        refresh(if(reviewEnabled)"Seek/pause, mark start and end. Review-time marks are stored separately from the original MP4." else "Timestamp playback only; no test metadata for reviewed export")
        val mediaControls = MediaController(this)
        mediaControls.setAnchorView(videoView)
        videoView.setMediaController(mediaControls)
        videoView.setOnErrorListener { _, what, extra ->
            Toast.makeText(this, "Unable to play saved recording ($what/$extra)", Toast.LENGTH_LONG).show()
            true
        }
        videoView.setOnPreparedListener { it.isLooping=false;videoView.start() }
        videoView.setVideoURI(uri)
        val update = object : Runnable {
            override fun run() {
                val ms=currentMs()
                val seconds=ms/1000L
                clock.text="Video %02d:%02d · %s · estimated".format(seconds/60,seconds%60,RecordingTimelineV130.clockFormat(session.wallAt(ms)))
                handler.postDelayed(this,250L)
            }
        }
        ticker=update
        handler.post(update)
    }
    override fun onDestroy() {
        ticker?.let{handler.removeCallbacks(it)}
        video?.stopPlayback()
        super.onDestroy()
    }
    companion object {
        fun open(context: Context) { context.startActivity(Intent(context,RecordingTimelinePlayerV130::class.java)) }
    }
}
