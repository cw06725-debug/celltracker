package com.example.celltracker

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recording companion overlay.  It intentionally owns only UI state; samples and
 * markers continue to be written by RecordingService so leaving CellTracker does
 * not create a second recording pipeline.
 */
class FloatingOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var cellular: CellularRepository

    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var issueView: View? = null
    private var refreshJob: Job? = null
    private var compact = false
    private var lastTargetSimNo: Int? = null

    private lateinit var headerText: TextView
    private lateinit var primaryText: TextView
    private lateinit var secondaryText: TextView
    private lateinit var dataText: TextView
    private lateinit var toggleButton: Button

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settingsRepository = SettingsRepository(this)
        cellular = CellularRepository(this)
        compact = settingsRepository.load().floatingStartCompact
        createOverlay()
        startRefreshLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun createOverlay() {
        val settings = settingsRepository.load()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBackground(settings.floatingOpacity)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerText = textView(14f, true).apply { text = "S-- · Waiting" }
        toggleButton = Button(this).apply {
            text = if (compact) "+" else "−"
            textSize = 15f
            minWidth = dp(38); minimumWidth = dp(38)
            minHeight = dp(34); minimumHeight = dp(34)
            setPadding(0, 0, 0, 0)
            setOnClickListener { setCompact(!compact) }
        }
        headerRow.addView(headerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(toggleButton, LinearLayout.LayoutParams(dp(40), dp(36)))
        root.addView(headerRow)

        primaryText = textView(17f, true).apply { text = "RSRP --   SINR --" }
        secondaryText = textView(13f, false).apply { text = "RSRQ -- · B-- · PCI --" }
        dataText = textView(12f, false).apply { text = "Data -- · REC" }
        root.addView(primaryText)
        root.addView(secondaryText)
        root.addView(dataText)

        val mark = Button(this).apply {
            text = "MARK"
            textSize = 14f
            setOnClickListener { showIssueMenu() }
        }
        root.addView(mark, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
        mark.tag = "markButton"

        val params = WindowManager.LayoutParams(
            dp(220),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (settings.floatingRememberPosition) prefs.getInt(KEY_X, dp(12)) else dp(12)
            y = if (settings.floatingRememberPosition) prefs.getInt(KEY_Y, dp(120)) else dp(120)
        }
        attachDrag(headerRow, params)
        overlayView = root
        overlayParams = params
        windowManager.addView(root, params)
        setCompact(compact)
    }

    private fun attachDrag(handle: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(3) || kotlin.math.abs(dy) > dp(3)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    overlayView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved && settingsRepository.load().floatingRememberPosition) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setCompact(value: Boolean) {
        compact = value
        toggleButton.text = if (compact) "+" else "−"
        primaryText.visibility = if (compact) View.GONE else View.VISIBLE
        secondaryText.visibility = if (compact) View.GONE else View.VISIBLE
        dataText.visibility = if (compact) View.GONE else View.VISIBLE
        overlayView?.findViewWithTag<View>("markButton")?.visibility = if (compact) View.GONE else View.VISIBLE
        overlayParams?.let { p ->
            p.width = if (compact) dp(165) else dp(220)
            overlayView?.let { runCatching { windowManager.updateViewLayout(it, p) } }
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                val status = RecordingState.status.value
                val settings = settingsRepository.load()
                if (!status.isRecording || !settings.floatingWindowEnabled) {
                    withContext(Dispatchers.Main) { stopSelf() }
                    break
                }
                val all = runCatching { cellular.readAllSims() }.getOrDefault(emptyList())
                val targetId = status.markTargetSubscriptionId
                val sim = all.firstOrNull { it.subscriptionId == targetId } ?: all.firstOrNull()
                withContext(Dispatchers.Main) {
                    overlayView?.background = roundedBackground(settings.floatingOpacity)
                    if (sim != null) updateTexts(sim)
                }
                delay(settings.uiRefreshMs.coerceIn(500L, 2000L))
            }
        }
    }

    private fun updateTexts(sim: SimCellState) {
        val c = sim.servingCell
        val simNo = sim.simSlotIndex + 1
        lastTargetSimNo = simNo
        val settings = settingsRepository.load()
        val selected = if (compact) settings.floatingCompactFields else settings.floatingExpandedFields
        val ordered = FloatingField.entries.filter { it in selected }
        fun value(field: FloatingField): String = when (field) {
            FloatingField.MARK_TARGET -> "S$simNo"
            FloatingField.OPERATOR -> c.operator.ifBlank { "--" }
            FloatingField.RAT -> c.displayRat.ifBlank { c.rat }
            FloatingField.RSRP -> "RSRP ${unit(c.rsrp, "dBm")}"
            FloatingField.RSRQ -> "RSRQ ${unit(c.rsrq, "dB")}"
            FloatingField.SINR -> "SINR ${unit(c.sinr, "dB")}"
            FloatingField.RSSI -> "RSSI ${unit(c.rssi, "dBm")}"
            FloatingField.BAND -> "Band ${c.band}"
            FloatingField.PCI -> "PCI ${c.pci}"
            FloatingField.ARFCN -> "ARFCN ${c.arfcn}"
            FloatingField.CA -> "CA ${c.carrierAggregation}"
            FloatingField.DATANET -> "Data ${NetworkStore.dataNetwork.ifBlank { "--" }}"
            FloatingField.DATA_SIM -> {
                val d = NetworkStore.dataSimSubscriptionId
                if (d < 0) "Data SIM --" else if (d == c.subscriptionId) "Data SIM S$simNo" else "Data SIM $d"
            }
            FloatingField.CELL_ID -> "Cell ${c.cellId}"
            FloatingField.TAC -> "TAC ${c.tac}"
            FloatingField.SPEED -> "Speed ${LocationStore.latest.value.speedKmh} km/h"
            FloatingField.GPS_ACCURACY -> "GPS ±${LocationStore.latest.value.accuracy} m"
            FloatingField.RECORDING -> "REC"
        }
        if (compact) {
            headerText.text = ordered.joinToString(" · ") { value(it) }.ifBlank { "S$simNo · REC" }
            return
        }
        val tokens = ordered.map { value(it) }
        headerText.text = tokens.take(3).joinToString(" · ").ifBlank { "S$simNo · ${c.operator}" }
        primaryText.text = tokens.drop(3).take(3).joinToString("   ")
        secondaryText.text = tokens.drop(6).take(3).joinToString(" · ")
        dataText.text = tokens.drop(9).joinToString(" · ")
        primaryText.visibility = if (primaryText.text.isBlank()) View.GONE else View.VISIBLE
        secondaryText.visibility = if (secondaryText.text.isBlank()) View.GONE else View.VISIBLE
        dataText.visibility = if (dataText.text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun showIssueMenu() {
        if (issueView != null) return
        val settings = settingsRepository.load()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground((settings.floatingOpacity + 0.12f).coerceAtMost(1f))
        }
        panel.addView(textView(15f, true).apply { text = "Mark issue · Target ${targetLabel()}" })
        val scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settings.issueTypes.forEach { issue ->
            scrollContent.addView(Button(this).apply {
                text = issue
                isAllCaps = false
                setOnClickListener { submitMark(issue); closeIssueMenu() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        }
        scrollContent.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { closeIssueMenu() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))
        panel.addView(ScrollView(this).apply { addView(scrollContent) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330)))

        val base = overlayParams ?: return
        val params = WindowManager.LayoutParams(
            dp(230), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = base.x
            y = (base.y + dp(50)).coerceAtLeast(0)
        }
        issueView = panel
        runCatching { windowManager.addView(panel, params) }.onFailure { issueView = null }
    }

    private fun targetLabel(): String {
        return lastTargetSimNo?.let { "SIM$it" } ?: "--"
    }

    private fun submitMark(issue: String) {
        val subId = RecordingState.status.value.markTargetSubscriptionId
        if (subId < 0) return
        val settings = settingsRepository.load()
        if (settings.floatingCaptureScreenshotOnMark && ScreenCaptureService.isReady) {
            overlayView?.visibility = View.INVISIBLE
            val capture = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_CAPTURE_MARK
                putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, subId)
                putExtra(RecordingService.EXTRA_EVENT_TYPE, issue)
                putExtra(RecordingService.EXTRA_EVENT_NOTE, "")
            }
            ContextCompat.startForegroundService(this, capture)
            scope.launch {
                delay(450)
                withContext(Dispatchers.Main) { overlayView?.visibility = View.VISIBLE }
            }
        } else {
            val intent = Intent(this, RecordingService::class.java).apply {
                action = RecordingService.ACTION_MARK
                putExtra(RecordingService.EXTRA_MARK_SUBSCRIPTION_ID, subId)
                putExtra(RecordingService.EXTRA_EVENT_TYPE, issue)
                putExtra(RecordingService.EXTRA_EVENT_NOTE, "")
            }
            ContextCompat.startForegroundService(this, intent)
        }
        if (settings.toastOnMark) runCatching { Toast.makeText(this, "Marked: $issue", Toast.LENGTH_SHORT).show() }
        if (settings.vibrateOnMark) runCatching {
            val vibrator = getSystemService(Vibrator::class.java)
            if (Build.VERSION.SDK_INT >= 26) vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator?.vibrate(60)
        }
    }

    private fun closeIssueMenu() {
        issueView?.let { runCatching { windowManager.removeView(it) } }
        issueView = null
    }

    private fun textView(sizeSp: Float, bold: Boolean) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    /** Background alpha only: text and buttons remain fully legible. */
    private fun roundedBackground(opacity: Float) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        val alpha = (opacity.coerceIn(0.20f, 1f) * 235f).toInt().coerceIn(40, 235)
        setColor(Color.argb(alpha, 25, 28, 34))
        setStroke(dp(1), Color.argb(120, 255, 255, 255))
    }

    private fun unit(value: String, suffix: String) = if (value.isBlank() || value == "--") "--" else "$value $suffix"
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    override fun onDestroy() {
        refreshJob?.cancel()
        closeIssueMenu()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PREFS = "celltracker_overlay"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
    }
}
