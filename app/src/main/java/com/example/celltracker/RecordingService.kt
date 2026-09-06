package com.example.celltracker

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cellular: CellularRepository
    private lateinit var location: LocationRepository
    private lateinit var settingsRepository: SettingsRepository
    private var recordingJob: Job? = null
    private var targetSubscriptionId: Int = -1
    private var bothSims: Boolean = false
    private var markTargetSubscriptionId: Int = -1
    private var taskName: String = ""

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this); location = LocationRepository(this); settingsRepository = SettingsRepository(this)
        createNotificationChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CellTracker recording").setContentText("Recording cellular and location samples").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIFICATION_ID, n)
        scope.launch { location.locations().collectLatest { } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MARK) {
            val subId = intent.getIntExtra(EXTRA_MARK_SUBSCRIPTION_ID, markTargetSubscriptionId.takeIf { it >= 0 } ?: targetSubscriptionId)
            val eventType = intent.getStringExtra(EXTRA_EVENT_TYPE).orEmpty().ifBlank { "General" }
            val eventNote = intent.getStringExtra(EXTRA_EVENT_NOTE).orEmpty()
            val screenshotPath = intent.getStringExtra(EXTRA_SCREENSHOT_PATH).orEmpty()
            val eventSource = intent.getStringExtra(EXTRA_EVENT_SOURCE).orEmpty().ifBlank { "MANUAL" }
            scope.launch { appendMarker(subId, eventType, eventNote, screenshotPath, eventSource) }
            return START_NOT_STICKY
        }
        if (recordingJob?.isActive != true) {
            targetSubscriptionId = intent?.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1) ?: -1
            markTargetSubscriptionId = intent?.getIntExtra(EXTRA_MARK_SUBSCRIPTION_ID, targetSubscriptionId) ?: targetSubscriptionId
            bothSims = intent?.getBooleanExtra(EXTRA_BOTH_SIMS, false) ?: false
            taskName = intent?.getStringExtra(EXTRA_TASK_NAME).orEmpty().trim()
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        recordingJob = scope.launch {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val initialSims = runCatching { cellular.readAllSims() }.getOrDefault(emptyList())
            val scopeName = if (bothSims) {
                "DualSIM"
            } else {
                val slot = initialSims.firstOrNull { it.subscriptionId == targetSubscriptionId }?.servingCell?.simSlotIndex
                if (slot != null && slot >= 0) "SIM${slot + 1}" else "SIM"
            }
            val safeTask = sanitizeTaskName(taskName)
            val taskPart = safeTask.takeIf { it.isNotBlank() }?.let { "_${it}" }.orEmpty()
            val file = File(dir, "CellTracker${taskPart}_${scopeName}_$stamp.csv")
            FileWriter(file, false).use { it.appendLine(CSV_HEADER) }
            val started = System.currentTimeMillis(); val perSim = mutableMapOf<Int, Long>()
            val initialLocation = LocationStore.latest.value
            RecordingState.status.value = RecordingStatus(true, started, 0, emptyMap(), file.absolutePath, markTargetSubscriptionId, initialLocation.isValid, locationAge(initialLocation), taskName)
            getSharedPreferences("celltracker_recording", MODE_PRIVATE).edit()
                .putString("latest_path", file.absolutePath)
                .putString("active_path", file.absolutePath)
                .putString("active_task_name", taskName)
                .putLong("active_started_at", started)
                .putBoolean("active_recording", true)
                .apply()
            val overlaySettings = settingsRepository.load()
            if (overlaySettings.floatingWindowEnabled && overlaySettings.floatingAutoShowDuringRecording && android.provider.Settings.canDrawOverlays(this@RecordingService)) {
                runCatching { startService(Intent(this@RecordingService, FloatingOverlayService::class.java)) }
            }
            while (isActive) {
                val cycleStart = System.currentTimeMillis()
                val all = cellular.readAllSims()
                val sims = if (bothSims) all else all.filter { it.subscriptionId == targetSubscriptionId }
                val locationSnapshot = LocationStore.latest.value
                FileWriter(file, true).use { w -> sims.forEach { sim ->
                    val c=sim.servingCell; w.appendLine(csvLine(cycleStart,c,locationSnapshot,false,"","")); perSim[c.subscriptionId]=(perSim[c.subscriptionId]?:0)+1
                }}
                RecordingState.status.value = RecordingStatus(true, started, perSim.values.sum(), perSim.toMap(), file.absolutePath, markTargetSubscriptionId, locationSnapshot.isValid, locationAge(locationSnapshot), taskName)
                val spent=System.currentTimeMillis()-cycleStart; delay((settingsRepository.load().recordIntervalMs-spent).coerceAtLeast(0))
            }
        }
    }

    private suspend fun appendMarker(subscriptionId: Int, eventType: String, eventNote: String, screenshotPath: String = "", eventSource: String = "MANUAL") {
        val status = RecordingState.status.value
        val path = status.latestPath ?: return
        if (!status.isRecording) return
        val sim = cellular.readAllSims().firstOrNull { it.subscriptionId == subscriptionId }
            ?: cellular.readAllSims().firstOrNull() ?: return
        val locationSnapshot = LocationStore.latest.value
        val markerId = "M" + SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).format(Date())
        FileWriter(File(path), true).use { w ->
            w.appendLine(csvLine(System.currentTimeMillis(), sim.servingCell, locationSnapshot, true, eventType, eventNote, markerId, eventSource, screenshotPath))
        }
    }

    private fun locationAge(l: LocationData): Long = if (!l.isValid || l.timestampMs <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - l.timestampMs).coerceAtLeast(0L)
    override fun onDestroy(){
        RecordingState.status.value=RecordingState.status.value.copy(isRecording=false)
        getSharedPreferences("celltracker_recording", MODE_PRIVATE).edit()
            .putBoolean("active_recording", false)
            .remove("active_path")
            .remove("active_task_name")
            .remove("active_started_at")
            .apply()
        if (!settingsRepository.load().floatingKeepWhenStopped) {
            runCatching { stopService(Intent(this, FloatingOverlayService::class.java)) }
        }
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?)=null
    private fun createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"CellTracker recording",NotificationManager.IMPORTANCE_LOW)) }
    companion object {
        const val CHANNEL_ID="celltracker_recording"; const val NOTIFICATION_ID=1001
        const val EXTRA_SUBSCRIPTION_ID="subscription_id"; const val EXTRA_BOTH_SIMS="both_sims"
        const val ACTION_MARK="com.example.celltracker.ACTION_MARK"
        const val EXTRA_MARK_SUBSCRIPTION_ID="mark_subscription_id"
        const val EXTRA_EVENT_TYPE="event_type"
        const val EXTRA_EVENT_NOTE="event_note"
        const val EXTRA_EVENT_SOURCE="event_source"
        const val EXTRA_SCREENSHOT_PATH="screenshot_path"
        const val EXTRA_TASK_NAME="task_name"
        const val CSV_HEADER="timestamp,sim_slot,subscription_id,operator,rat,display_rat,mcc,mnc,tac,cell_id,pci,arfcn,rsrp,rsrq,sinr,band,bandwidth,rssi,timing_advance,csi_rsrp,csi_rsrq,csi_sinr,cqi,signal_level,asu_level,carrier_aggregation,data_rat,voice_rat,roaming,latitude,longitude,altitude,accuracy,speed_kmh,bearing,location_valid,is_marker,marker_id,event_source,event_type,event_note,screenshot,data_sim_subscription_id,data_network"
        fun csvLine(timestamp:Long,c:CellData,l:LocationData,isMarker:Boolean,eventType:String,eventNote:String,markerId:String="",eventSource:String="MANUAL",screenshotPath:String=""):String{
            val time=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date(timestamp)); val v=listOf(time,(c.simSlotIndex+1).toString(),c.subscriptionId.toString(),c.operator,c.rat,c.displayRat,c.mcc,c.mnc,c.tac,c.cellId,c.pci,c.arfcn,c.rsrp,c.rsrq,c.sinr,c.band,c.bandwidth,c.rssi,c.timingAdvance,c.csiRsrp,c.csiRsrq,c.csiSinr,c.cqi,c.level,c.asuLevel,c.carrierAggregation,c.dataRat,c.voiceRat,c.roaming,l.latitude,l.longitude,l.altitude,l.accuracy,l.speedKmh,l.bearing,l.isValid.toString(),isMarker.toString(),markerId,eventSource,eventType,eventNote,screenshotPath,NetworkStore.dataSimSubscriptionId.takeIf { it >= 0 }?.toString().orEmpty(),NetworkStore.dataNetwork)
            return v.joinToString(","){ escapeCsv(it) }
        }
        private fun sanitizeTaskName(value: String): String = value
            .replace(Regex("[\\/:*?\"<>|\r\n]+"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', ' ')
            .take(48)
        private fun escapeCsv(value:String):String{ val safe=value.replace("\"","\"\""); return if(safe.contains(',')||safe.contains('"')||safe.contains('\n')) "\"$safe\"" else safe }
    }
}
