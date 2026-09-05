package com.example.celltracker

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
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

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this); location = LocationRepository(this); settingsRepository = SettingsRepository(this)
        createNotificationChannel()
        val n = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CellTracker recording").setContentText("Recording cellular and location samples").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIFICATION_ID, n)
        // Keep location updates active while recording. Every update is published into the shared LocationStore.
        scope.launch { location.locations().collectLatest { /* LocationStore is updated by LocationRepository */ } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (recordingJob?.isActive != true) {
            targetSubscriptionId = intent?.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1) ?: -1
            bothSims = intent?.getBooleanExtra(EXTRA_BOTH_SIMS, false) ?: false
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        recordingJob = scope.launch {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val scopeName = if (bothSims) "DualSIM" else "SIM"
            val file = File(dir, "CellTracker_${scopeName}_$stamp.csv")
            FileWriter(file, false).use { it.appendLine(CSV_HEADER) }
            val started = System.currentTimeMillis(); val perSim = mutableMapOf<Int, Long>()
            val initialLocation = LocationStore.latest.value
            RecordingState.status.value = RecordingStatus(true, started, 0, emptyMap(), file.absolutePath, initialLocation.isValid, locationAge(initialLocation))
            getSharedPreferences("celltracker_recording", MODE_PRIVATE).edit().putString("latest_path", file.absolutePath).apply()
            while (isActive) {
                val cycleStart = System.currentTimeMillis()
                val all = cellular.readAllSims()
                val sims = if (bothSims) all else all.filter { it.subscriptionId == targetSubscriptionId }
                val locationSnapshot = LocationStore.latest.value
                FileWriter(file, true).use { w -> sims.forEach { sim ->
                    val c=sim.servingCell; w.appendLine(csvLine(cycleStart,c,locationSnapshot,false,"","")); perSim[c.subscriptionId]=(perSim[c.subscriptionId]?:0)+1
                }}
                RecordingState.status.value = RecordingStatus(true, started, perSim.values.sum(), perSim.toMap(), file.absolutePath, locationSnapshot.isValid, locationAge(locationSnapshot))
                val spent=System.currentTimeMillis()-cycleStart; delay((settingsRepository.load().recordIntervalMs-spent).coerceAtLeast(0))
            }
        }
    }
    private fun locationAge(l: LocationData): Long = if (!l.isValid || l.timestampMs <= 0L) Long.MAX_VALUE else (System.currentTimeMillis() - l.timestampMs).coerceAtLeast(0L)
    override fun onDestroy(){ RecordingState.status.value=RecordingState.status.value.copy(isRecording=false); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?)=null
    private fun createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID,"CellTracker recording",NotificationManager.IMPORTANCE_LOW)) }
    companion object {
        const val CHANNEL_ID="celltracker_recording"; const val NOTIFICATION_ID=1001
        const val EXTRA_SUBSCRIPTION_ID="subscription_id"; const val EXTRA_BOTH_SIMS="both_sims"
        const val CSV_HEADER="timestamp,sim_slot,subscription_id,operator,rat,display_rat,mcc,mnc,tac,cell_id,pci,arfcn,rsrp,rsrq,sinr,band,bandwidth,rssi,timing_advance,csi_rsrp,csi_rsrq,csi_sinr,latitude,longitude,altitude,accuracy,speed_kmh,bearing,location_valid,is_marker,event_type,event_note,screenshot"
        fun csvLine(timestamp:Long,c:CellData,l:LocationData,isMarker:Boolean,eventType:String,eventNote:String):String{
            val time=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(Date(timestamp)); val v=listOf(time,(c.simSlotIndex+1).toString(),c.subscriptionId.toString(),c.operator,c.rat,c.displayRat,c.mcc,c.mnc,c.tac,c.cellId,c.pci,c.arfcn,c.rsrp,c.rsrq,c.sinr,c.band,c.bandwidth,c.rssi,c.timingAdvance,c.csiRsrp,c.csiRsrq,c.csiSinr,l.latitude,l.longitude,l.altitude,l.accuracy,l.speedKmh,l.bearing,l.isValid.toString(),isMarker.toString(),eventType,eventNote,"")
            return v.joinToString(","){ escapeCsv(it) }
        }
        private fun escapeCsv(value:String):String{ val safe=value.replace("\"","\"\""); return if(safe.contains(',')||safe.contains('"')||safe.contains('\n')) "\"$safe\"" else safe }
    }
}
