package com.example.celltracker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*

class WhatsAppSendAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var activeInstance: WhatsAppSendAccessibilityService? = null
        fun requestOverlay() {
            activeInstance?.scope?.launch {
                if (activeInstance?.repo?.isArmed() == true) activeInstance?.showOverlay()
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repo: WhatsAppSendRepository
    private var overlay: View? = null
    private var capture: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var startButton: Button? = null
    private var sentButton: Button? = null
    private var file: java.io.File? = null
    private var sessionStart = 0L
    private var running = false
    private var armed = false
    private var seq = 0
    private var t0Wall = 0L
    private var t0Elapsed = 0L
    private var clockJob: Job? = null

    override fun onServiceConnected() {
        activeInstance = this
        repo = WhatsAppSendRepository(this)
        if (repo.isArmed()) showOverlay()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (::repo.isInitialized && repo.isArmed() && overlay == null) showOverlay()
    }
    override fun onInterrupt() = Unit

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 14, 20, 14)
            setBackgroundColor(0xE6202124.toInt())
        }
        val header = TextView(this).apply { setTextColor(0xffffffff.toInt()); text = "WhatsApp Image Send  ·  drag here"; setPadding(8,8,8,12) }
        val status = TextView(this).apply { setTextColor(0xffffffff.toInt()); text = "Ready · open WhatsApp image preview"; setPadding(8,0,8,8) }
        statusView = status
        val clock = TextView(this).apply { setTextColor(0xffffffff.toInt()); text = "TIME --:--:--.---"; setPadding(8,0,8,8) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = Button(this).apply { text = "START" }
        val sent = Button(this).apply { text = "SENT"; visibility = View.GONE }
        val stop = Button(this).apply { text = "STOP"; visibility = View.GONE }
        startButton = start; sentButton = sent
        val weighted = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(start, weighted); row.addView(sent, weighted); row2.addView(stop, weighted)
        box.addView(header); box.addView(status); box.addView(clock); box.addView(row); box.addView(row2)

        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 180 }
        overlayLp = lp
        var dx=0f; var dy=0f; var sx=0; var sy=0
        header.setOnTouchListener { _,e ->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{dx=e.rawX;dy=e.rawY;sx=lp.x;sy=lp.y;true}
                MotionEvent.ACTION_MOVE->{lp.x=(sx+e.rawX-dx).toInt().coerceAtLeast(0);lp.y=(sy+e.rawY-dy).toInt().coerceAtLeast(0);runCatching{wm.updateViewLayout(box,lp)};true}
                else->true
            }
        }
        start.setOnClickListener {
            if (!running) {
                sessionStart = System.currentTimeMillis(); file = repo.create(sessionStart); running = true
                sent.visibility = View.VISIBLE; stop.visibility = View.VISIBLE
            }
            when {
                t0Wall > 0L -> status.text = "#$seq active · tap SENT when sending completes"
                armed -> status.text = "ARMED · now tap WhatsApp Send"
                else -> armNext(status)
            }
        }
        sent.setOnClickListener {
            if (!running || t0Wall <= 0L) {
                status.text = if (armed) "ARMED · tap WhatsApp Send first" else "Press START, then tap WhatsApp Send first"
            } else completeSample(status)
        }
        stop.setOnClickListener {
            removeCapture(); armed=false
            file?.let { repo.finish(it, sessionStart, System.currentTimeMillis(), "Stopped") }
            repo.disarm(); running=false; status.text="Stopped"
            scope.launch { delay(700); dismissOverlay() }
        }
        wm.addView(box,lp); overlay=box
        clockJob?.cancel(); clockJob=scope.launch { val fmt=java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.US); while(isActive&&overlay===box){clock.text="TIME "+fmt.format(java.util.Date());delay(20)} }
    }

    private fun armNext(status: TextView) {
        removeCapture(); armed=false; t0Wall=0; t0Elapsed=0
        startButton?.apply { text="ARMING…"; isEnabled=false }
        status.text="ARMING…"
        scope.launch {
            delay(120)
            if (!running) return@launch
            armed=true
            installCapture()
            if (capture != null) {
                startButton?.apply { text="ARMED"; isEnabled=true }
                status.text="ARMED · now tap WhatsApp Send"
            } else {
                armed=false; startButton?.apply { text="START"; isEnabled=true }; status.text="Arm failed · press START again"
            }
        }
    }

    private fun installCapture() {
        if (!running || !armed || t0Wall>0 || capture!=null) return
        val wm=getSystemService(WindowManager::class.java)
        val v=View(this).apply { setBackgroundColor(0x01000000) }
        val cp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START}
        var downX=0f;var downY=0f;var downElapsed=0L;var moved=false;var multi=false
        val slop=64f*resources.displayMetrics.density
        v.setOnTouchListener { _,e ->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;downElapsed=SystemClock.elapsedRealtime();moved=false;multi=false;true}
                MotionEvent.ACTION_POINTER_DOWN->{multi=true;true}
                MotionEvent.ACTION_MOVE->{val x=e.rawX-downX;val y=e.rawY-downY;if(kotlin.math.sqrt(x*x+y*y)>slop)moved=true;true}
                MotionEvent.ACTION_UP->{
                    val duration=(SystemClock.elapsedRealtime()-downElapsed).coerceAtLeast(1)
                    if(!multi&&!moved&&duration<650){
                        seq++; t0Elapsed=downElapsed; t0Wall=System.currentTimeMillis()-duration; armed=false
                        startButton?.text="ACTIVE"; statusView?.text="#$seq · T0 SEND · tap SENT when complete"
                        val x=e.rawX;val y=e.rawY;removeCapture()
                        scope.launch { delay(80); if(!dispatchTap(x,y)){ t0Wall=0;t0Elapsed=0;seq=(seq-1).coerceAtLeast(0);statusView?.text="Tap delivery failed · press START again";startButton?.text="START" } }
                    } else {
                        val x0=downX;val y0=downY;val x1=e.rawX;val y1=e.rawY;removeCapture()
                        scope.launch { delay(60); dispatchSwipe(x0,y0,x1,y1,duration); delay(80); if(running&&armed) installCapture() }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL->{true}
                else->true
            }
        }
        runCatching{wm.addView(v,cp)}.onSuccess{capture=v}
    }

    private fun completeSample(status:TextView){
        val startW=t0Wall;val startE=t0Elapsed;if(startW<=0||startE<=0||file==null)return
        t0Wall=0;t0Elapsed=0;armed=false;removeCapture();startButton?.apply{text="START";isEnabled=true}
        scope.launch {
            val t1W=System.currentTimeMillis();val t1E=SystemClock.elapsedRealtime();val delay=(t1E-startE).coerceAtLeast(0)
            val snap=withContext(Dispatchers.IO){snapshot()};repo.append(file!!,WhatsAppSendSample(seq,startW,t1W,delay,snap,startE,t1E))
            status.text="#$seq · $delay ms · saved · press START for next sample"
        }
    }

    private fun dispatchTap(x:Float,y:Float):Boolean{val p=Path().apply{moveTo(x,y)};return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,55)).build(),null,null)}
    private fun dispatchSwipe(x0:Float,y0:Float,x1:Float,y1:Float,duration:Long):Boolean{val p=Path().apply{moveTo(x0,y0);lineTo(x1,y1)};return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,duration.coerceIn(100,700))).build(),null,null)}
    private fun removeCapture(){capture?.let{runCatching{getSystemService(WindowManager::class.java).removeView(it)}};capture=null}
    private fun dismissOverlay(){removeCapture();clockJob?.cancel();clockJob=null;overlay?.let{runCatching{getSystemService(WindowManager::class.java).removeView(it)}};overlay=null}
    private suspend fun snapshot():PingNetworkSnapshot{val sims=runCatching{CellularRepository(this).readAllSims()}.getOrDefault(emptyList());val id=SubscriptionManager.getDefaultDataSubscriptionId();val s=sims.firstOrNull{it.subscriptionId==id}?:sims.firstOrNull();val c=s?.servingCell;val l=LocationStore.latest.value;return PingNetworkSnapshot(subscriptionId=s?.subscriptionId?:-1,simSlot=s?.simSlotIndex?:-1,operator=c?.operator?:"--",rat=c?.rat?:"--",displayRat=c?.displayRat?:"--",rsrp=c?.rsrp?:"--",rsrq=c?.rsrq?:"--",sinr=c?.sinr?:"--",rssi=c?.rssi?:"--",band=c?.band?:"--",pci=c?.pci?:"--",arfcn=c?.arfcn?:"--",latitude=l.latitude.toDoubleOrNull(),longitude=l.longitude.toDoubleOrNull())}
    override fun onDestroy(){if(activeInstance===this)activeInstance=null;dismissOverlay();scope.cancel();super.onDestroy()}
}
