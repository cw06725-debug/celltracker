package com.example.celltracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
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
        val isExternalVideo = prefs.getString("mappingProvenance", "").orEmpty().startsWith("EXTERNAL_VIDEO_")
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

        val scale=resources.displayMetrics.density
        fun d(value:Int)=(value*scale).toInt()
        fun cardGradient(primary:Boolean): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if(primary) intArrayOf(0xFFAA70CE.toInt(),0xFF7280DD.toInt(),0xFF5ACFC3.toInt())
            else intArrayOf(0xFFF1E4FA.toInt(),0xFFE9ECFA.toInt(),0xFFDFF4ED.toInt())
        ).apply {cornerRadius=d(18).toFloat();setStroke(d(1),0x22AA79C9)}
        val controls = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(d(16),d(14),d(16),d(23));
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFF3EAF8.toInt(),0xFFF9F5FB.toInt(),0xFFE7F5F0.toInt()))
        }
        val reviewTitle = TextView(this).apply {text="Recording review";textSize=17f;setTextColor(0xFF302640.toInt());typeface=android.graphics.Typeface.DEFAULT_BOLD}
        val status = TextView(this).apply {textSize=12f;setTextColor(0xFF615A74.toInt());setPadding(0,d(9),0,d(12))}
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
        fun btn(title:String, primary:Boolean=false, action:()->Unit) = Button(this).apply {
            this.text=title;this.textSize=12f;this.isAllCaps=false;this.setSingleLine(true)
            this.ellipsize=TextUtils.TruncateAt.END
            this.setTextColor(if(primary)Color.WHITE else 0xFF504177.toInt())
            this.background=cardGradient(primary)
            this.minHeight=d(52)
            setOnClickListener{action()}
        }
        fun addAction(title:String, weight:Float, action:()->Unit) {
            val lp=LinearLayout.LayoutParams(0,d(52),weight).apply {marginStart=d(3);marginEnd=d(3)}
            row.addView(btn(title,false,action),lp)
        }
        addAction("START",1f) {
            videoView.pause()
            markedStart=currentMs()
            refresh("Start ${RecordingTimelineV130.clockFormat(session.wallAt(markedStart!!))} · estimated")
        }
        addAction("END",1f) {
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
        }
        addAction("UNDO",.85f) {
            if(markedStart!=null)markedStart=null
            else if(events.isNotEmpty()){events.removeAt(events.lastIndex);persist()}
            refresh()
        }
        controls.addView(row,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(52)).apply {bottomMargin=d(12)})
        lateinit var exportButton: Button
        exportButton=btn("EXPORT REPORT",true) {
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
                                prefs.getString("reviewAppVersion","").orEmpty(),
                                if(isExternalVideo)"EXTERNAL_VIDEO_REVIEW_ESTIMATED" else "VIDEO_REVIEW_ESTIMATED")
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
        controls.addView(exportButton,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(54)))
        base.addView(controls,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(base)
        refresh(if(reviewEnabled) "Seek, pause, then mark START / END. Original MP4 remains unchanged. Time ${if(isExternalVideo)"EXTERNAL ESTIMATED" else "ESTIMATED"}." else "Timestamp playback only; no test metadata for reviewed export")
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
