package com.example.celltracker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

class LocationRepository(private val context: Context) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission")
    fun locations(): Flow<LocationData> = callbackFlow {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            close()
            return@callbackFlow
        }

        val listener = LocationListener { location -> trySend(location.toData()) }

        fun request(provider: String) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener)
                    lm.getLastKnownLocation(provider)?.let { trySend(it.toData()) }
                }
            } catch (_: Exception) { }
        }

        if (fine) request(LocationManager.GPS_PROVIDER)
        request(LocationManager.NETWORK_PROVIDER)

        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toData() = LocationData(
        latitude = String.format(Locale.US, "%.6f", latitude),
        longitude = String.format(Locale.US, "%.6f", longitude),
        altitude = if (hasAltitude()) String.format(Locale.US, "%.1f m", altitude) else "--",
        accuracy = if (hasAccuracy()) String.format(Locale.US, "%.1f m", accuracy) else "--",
        speedKmh = if (hasSpeed()) String.format(Locale.US, "%.1f km/h", speed * 3.6f) else "--",
        bearing = if (hasBearing()) String.format(Locale.US, "%.1f°", bearing) else "--"
    )
}
