package com.example.celltracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cellular: CellularRepository
    private lateinit var location: LocationRepository
    private lateinit var settingsRepository: SettingsRepository
    private var recordingJob: Job? = null
    private var latestLocation = LocationData()

    override fun onCreate() {
        super.onCreate()
        cellular = CellularRepository(this)
        location = LocationRepository(this)
        settingsRepository = SettingsRepository(this)
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("CellTracker recording")
            .setContentText("Recording cellular and location samples")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scope.launch {
            location.locations().collectLatest { latestLocation = it }
        }
        startRecording()
    }

    private fun startRecording() {
        if (recordingJob?.isActive == true) return
        recordingJob = scope.launch {
            val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "CellTracker_$stamp.csv")
            FileWriter(file, false).use { it.appendLine(CSV_HEADER) }
            val started = System.currentTimeMillis()
            RecordingState.status.value = RecordingStatus(true, started, 0, file.absolutePath)
            getSharedPreferences("celltracker_recording", MODE_PRIVATE).edit().putString("latest_path", file.absolutePath).apply()
            var sampleCount = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val sims = cellular.readAllSims()
                FileWriter(file, true).use { writer ->
                    sims.forEach { sim ->
                        val c = sim.servingCell
                        writer.appendLine(csvLine(now, c, latestLocation, false, "", ""))
                        sampleCount++
                    }
                }
                RecordingState.status.value = RecordingStatus(true, started, sampleCount, file.absolutePath)
                delay(settingsRepository.load().recordIntervalMs)
            }
        }
    }

    override fun onDestroy() {
        RecordingState.status.value = RecordingState.status.value.copy(isRecording = false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "CellTracker recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "celltracker_recording"
        const val NOTIFICATION_ID = 1001
        const val CSV_HEADER = "timestamp,sim_slot,subscription_id,operator,rat,display_rat,mcc,mnc,tac,cell_id,pci,arfcn,rsrp,rsrq,sinr,latitude,longitude,altitude,accuracy,speed_kmh,bearing,is_marker,event_type,event_note,screenshot"

        fun csvLine(
            timestamp: Long,
            c: CellData,
            l: LocationData,
            isMarker: Boolean,
            eventType: String,
            eventNote: String
        ): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val values = listOf(
                time, (c.simSlotIndex + 1).toString(), c.subscriptionId.toString(), c.operator,
                c.rat, c.displayRat, c.mcc, c.mnc, c.tac, c.cellId, c.pci, c.arfcn,
                c.rsrp, c.rsrq, c.sinr, l.latitude, l.longitude, l.altitude, l.accuracy,
                l.speedKmh, l.bearing, isMarker.toString(), eventType, eventNote, ""
            )
            return values.joinToString(",") { escapeCsv(it) }
        }

        private fun escapeCsv(value: String): String {
            val safe = value.replace("\"", "\"\"")
            return if (safe.contains(',') || safe.contains('"') || safe.contains('\n')) "\"$safe\"" else safe
        }
    }
}
