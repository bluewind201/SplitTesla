package com.splittesla.app.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat

/** One phone GPS fix. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float?,
    val bearingDeg: Float?,
    val accuracyM: Float?,
    val atMillis: Long,
)

/**
 * The phone's GPS, once a second while the dashboard is on screen or
 * floating over the navi. Feeds speed camera alerts and the speed fallback
 * when neither the Commander nor the car's BLE gives a speed.
 */
class PhoneGps(private val context: Context, private val onFix: (GpsFix) -> Unit) : LocationListener {
    private val manager get() = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var running = false
        private set

    fun start(): Boolean {
        if (running) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            running = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { manager.removeUpdates(this) }
    }

    override fun onLocationChanged(location: Location) {
        // Fixes worse than this put the car on the next road over.
        if (location.hasAccuracy() && location.accuracy > 60f) return
        onFix(
            GpsFix(
                latitude = location.latitude,
                longitude = location.longitude,
                speedKmh = if (location.hasSpeed()) location.speed * 3.6f else null,
                bearingDeg = if (location.hasBearing()) location.bearing else null,
                accuracyM = if (location.hasAccuracy()) location.accuracy else null,
                atMillis = System.currentTimeMillis(),
            ),
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
}
