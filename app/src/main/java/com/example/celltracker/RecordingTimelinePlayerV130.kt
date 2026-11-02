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
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
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
        val density=resources.displayMetrics.density
        fun d(v:Int)=(v*density).toInt()
        val darkText=0xFF2B2440.toInt()
        val mutedText=0xFF655D77.toInt()
        fun roundedGradient(primary:Boolean = false, tint:Int = 0): GradientDrawable {
            val colors=when {
                primary -> intArrayOf(0xFFAA71CF.toInt(),0xFF7382DB.toInt(),0xFF59CDC3.toInt())
                tint==1 -> intArrayOf(0xFFE0F8ED.toInt(),0xFFD5F4EC.toInt())
                tint==2 -> intArrayOf(0xFFFCE4F0.toInt(),0xFFF8E3EB.toInt())
                tint==3 -> intArrayOf(0xFFE9E9FF.toInt(),0xFFE1ECFF.toInt())
                else -> intArrayOf(0xFFF0E5FA.toInt(),0xFFE9EDFC.toInt(),0xFFE0F6EE.toInt())
            }
            return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,colors).apply {
                cornerRadius=d(20).toFloat();setStroke(d(1),0x238D7CB7)
            }
        }
        fun smallText(value:String,size:Float=13f) = TextView(this).apply {
            text=value;textSize=size;setTextColor(mutedText)
        }
        fun button(value:String, primary:Boolean=false, tint:Int=0, click:()->Unit): Button = Button(this).apply {
            text=value;textSize=13f;isAllCaps=false;setSingleLine(true)
            ellipsize=TextUtils.TruncateAt.END
            setTextColor(if(primary)Color.WHITE else darkText)
            background=roundedGradient(primary,tint)
            minHeight=0;minimumHeight=0
            setPadding(d(8),0,d(8),0)
            setOnClickListener{click()}
        }
        val rootBackground = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFF8F2FB.toInt(),0xFFF9F6FC.toInt(),0xFFF1F9F7.toInt()))
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            setVerticalScrollBarEnabled(false)
            setBackground(rootBackground)
        }
        val base=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(d(16),d(12),d(16),d(24))
        }
        scroll.addView(base, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val toolbar=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        toolbar.addView(button("‹ Back",tint=3){finish()},LinearLayout.LayoutParams(d(84),d(42)))
        toolbar.addView(TextView(this).apply {
            text="Recording Review";textSize=21f;typeface=android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(darkText);setPadding(d(13),0,0,0)
        },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        base.addView(toolbar,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(54)))
        base.addView(smallText("Video playback · real-time clock (estimated)",12f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(32)))
        val frame=FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            background=GradientDrawable().apply {setColor(Color.BLACK);cornerRadius=d(20).toFloat()}
            clipToOutline=true
        }
        val videoView=VideoView(this)
        video=videoView
        frame.addView(videoView,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT,Gravity.CENTER))
        val clock=TextView(this).apply {
            text="${RecordingTimelineV130.clockFormat(session.wallAt(0L))} · estimated"
            textSize=11.5f;setTextColor(Color.WHITE);setBackgroundColor(0xBB1A1932.toInt())
            setPadding(d(9),d(6),d(9),d(6));gravity=Gravity.CENTER_VERTICAL
            maxLines=2
        }
        frame.addView(clock,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM))
        val frameHeight=(resources.displayMetrics.widthPixels - d(32))*9/16
        val videoParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,frameHeight.coerceAtLeast(d(185)))
        videoParams.bottomMargin=d(12)
        base.addView(frame,videoParams)
        val transport=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(d(12),d(12),d(12),d(10));background=roundedGradient()
        }
        fun displayTime(ms:Long): String {
            val safe=ms.coerceAtLeast(0L)
            return String.format(Locale.US,"%02d:%02d.%03d",safe/60000L,(safe/1000L)%60L,safe%1000L)
        }
        val timeText=smallText("${displayTime(0L)} / ${displayTime(session.videoDurationMs)}",13f)
        timeText.setTextColor(darkText)
        transport.addView(timeText)
        val smoother=PlaybackClockSmootherV130()
        var seekProtectionUntil=0L
        var requestedPosition=0L
        val seekBar=SeekBar(this).apply {max=session.videoDurationMs.coerceIn(1L,Int.MAX_VALUE.toLong()).toInt()}
        transport.addView(seekBar,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(36)))
        val transportRow=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        fun seekTo(target: Int) {
            val bounded=target.coerceIn(0,seekBar.max)
            requestedPosition=bounded.toLong()
            seekProtectionUntil=SystemClock.uptimeMillis()+280L
            smoother.reset(bounded.toLong(),SystemClock.uptimeMillis())
            videoView.seekTo(bounded)
            seekBar.progress=bounded
            timeText.text="${displayTime(bounded.toLong())} / ${displayTime(session.videoDurationMs)}"
            clock.text="${RecordingTimelineV130.clockFormat(session.wallAt(bounded.toLong()))} · estimated"
        }
        val back5=button("−5s",tint=3){seekTo((videoView.currentPosition-5000).coerceAtLeast(0))}
        val playButton=button("▶  Play",primary=true){
            if(videoView.isPlaying)videoView.pause() else videoView.start()
        }
        val forward5=button("+5s",tint=3){seekTo((videoView.currentPosition+5000).coerceAtMost(seekBar.max))}
        fun addTransportButton(view:Button,weight:Float) {
            transportRow.addView(view,LinearLayout.LayoutParams(0,d(45),weight).apply{marginStart=d(3);marginEnd=d(3)})
        }
        addTransportButton(back5,1f);addTransportButton(playButton,1.65f);addTransportButton(forward5,1f)
        transport.addView(transportRow)
        base.addView(transport,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        val reviewCard=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(d(12),d(14),d(12),d(15));background=roundedGradient()
        }
        val reviewTitle=TextView(this).apply {
            text="Stall annotations";textSize=17f;setTextColor(darkText)
            typeface=android.graphics.Typeface.DEFAULT_BOLD
        }
        reviewCard.addView(reviewTitle)
        val status=smallText("",12.5f).apply {setPadding(0,d(8),0,d(12))}
        reviewCard.addView(status)
        base.addView(reviewCard,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=d(14)})
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
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        fun addAction(title:String,weight:Float,tint:Int,action:()->Unit) {
            row.addView(button(title,tint=tint,click=action),
                LinearLayout.LayoutParams(0,d(48),weight).apply{marginStart=d(3);marginEnd=d(3)})
        }
        addAction("START",1f,1) {
            videoView.pause()
            markedStart=currentMs()
            refresh("Start ${RecordingTimelineV130.clockFormat(session.wallAt(markedStart!!))} · estimated")
        }
        addAction("END",1f,2) {
            videoView.pause()
            val a=markedStart
            val b=currentMs()
            if(a==null || b<=a)Toast.makeText(this,"Set a start first, then seek forward to end",Toast.LENGTH_SHORT).show()
            else {
                events += a to b
                markedStart=null
                persist()
                refresh("Saved event #${events.size}: %.2fs · ESTIMATED".format(Locale.US,(b-a)/1000.0))
            }
        }
        addAction("UNDO",.85f,3) {
            if(markedStart!=null)markedStart=null
            else if(events.isNotEmpty()){events.removeAt(events.lastIndex);persist()}
            refresh()
        }
        reviewCard.addView(row,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(48)).apply{bottomMargin=d(12)})
        lateinit var exportButton: Button
        exportButton=button("EXPORT REPORT",primary=true) {
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
        reviewCard.addView(exportButton,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,d(54)))
        base.addView(smallText("Original MP4 is never modified. Exported event times remain ESTIMATED.",11f),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=d(10)})
        setContentView(scroll)
        refresh(if(reviewEnabled) "Pause or seek, then mark START and END. ${if(isExternalVideo)"External video" else "CellTracker video"} · estimated clock." else "Timestamp playback only; no PUBG/Tamasha metadata available for event export")
        VideoDiagnosticLogV130.event(this,"Review opened durationMs=${session.videoDurationMs} external=$isExternalVideo")
        var isSeeking=false
        seekBar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean) {
                if(fromUser){
                    timeText.text="${displayTime(progress.toLong())} / ${displayTime(session.videoDurationMs)}"
                    clock.text="${RecordingTimelineV130.clockFormat(session.wallAt(progress.toLong()))} · estimated"
                }
            }
            override fun onStartTrackingTouch(bar:SeekBar?) {isSeeking=true}
            override fun onStopTrackingTouch(bar:SeekBar?) {
                seekTo(seekBar.progress)
                isSeeking=false
                VideoDiagnosticLogV130.event(this@RecordingTimelinePlayerV130,"Seek to ${seekBar.progress}ms")
            }
        })
        videoView.setOnErrorListener { _, what, extra ->
            VideoDiagnosticLogV130.event(this,"Player error what=$what extra=$extra")
            Toast.makeText(this,"Unable to play saved recording ($what/$extra)",Toast.LENGTH_LONG).show()
            true
        }
        videoView.setOnPreparedListener { player ->
            player.isLooping=false
            val w=player.videoWidth
            val h=player.videoHeight
            if(w>0 && h>0){
                val maximum=(resources.displayMetrics.heightPixels*.55f).toInt()
                val target=((resources.displayMetrics.widthPixels-d(32)).toLong()*h/w).toInt()
                videoParams.height=target.coerceIn(d(185),maximum.coerceAtLeast(d(185)))
                frame.layoutParams=videoParams
            }
            videoView.start()
            VideoDiagnosticLogV130.event(this,"Prepared video ${w}x$h")
        }
        videoView.setOnCompletionListener {playButton.text="↻  Replay"}
        videoView.setVideoURI(uri)
        val update=object:Runnable{
            override fun run() {
                val playing=videoView.isPlaying
                val now=SystemClock.uptimeMillis()
                val ms=when {
                    isSeeking -> seekBar.progress.toLong()
                    now < seekProtectionUntil -> requestedPosition
                    else -> smoother.tick(currentMs(),now,playing,session.videoDurationMs)
                }
                if(!isSeeking) {
                    // Do not seek the player while updating the display; video position is the source.
                    seekBar.progress=ms.toInt().coerceAtMost(seekBar.max)
                    val nextTime="${displayTime(ms)} / ${displayTime(session.videoDurationMs)}"
                    if(timeText.text.toString()!=nextTime)timeText.text=nextTime
                    val nextClock="${RecordingTimelineV130.clockFormat(session.wallAt(ms))} · estimated"
                    if(clock.text.toString()!=nextClock)clock.text=nextClock
                }
                val nextLabel=if(playing)"Ⅱ  Pause" else if(ms<session.videoDurationMs-500L)"▶  Play" else "↻  Replay"
                if(playButton.text.toString()!=nextLabel)playButton.text=nextLabel
                // Smooth UI-only interpolation; recording mapping itself is unchanged/ESTIMATED.
                handler.postDelayed(this,40L)
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
