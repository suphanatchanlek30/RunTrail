package com.example.runtrail.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.runtrail.data.TrackPoint

/** Delivers location fixes every second via [LocationManager]. */
class LocationTracker(
    context: Context,
    private val onPoint: (TrackPoint, speedMps: Float?) -> Unit,
    private val onProviderChanged: (enabled: Boolean) -> Unit,
) : LocationListener {

    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    var running = false
        private set

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Provider in use: GPS when present, otherwise fused or network (some tablets have no GPS). */
    val provider: String? get() = pickProvider(lm.allProviders)

    val gpsEnabled: Boolean get() = provider?.let { lm.isProviderEnabled(it) } ?: false

    @Suppress("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (!hasPermission) return false
        val p = provider ?: return false
        try {
            lm.requestLocationUpdates(p, INTERVAL_MS, 0f, this, Looper.getMainLooper())
        } catch (e: IllegalArgumentException) {
            return false
        } catch (e: SecurityException) {
            return false   // permission revoked meanwhile
        }
        running = true
        // Show the last known position (newest of GPS and network) before the first fix arrives.
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p ->
                try { lm.getLastKnownLocation(p) } catch (e: IllegalArgumentException) { null } catch (e: SecurityException) { null }
            }
            .maxByOrNull { it.time }
            ?.let { lastKnown = it }
        return true
    }

    fun stop() {
        if (!running) return
        try { lm.removeUpdates(this) } catch (e: SecurityException) { }
        running = false
    }

    var lastKnown: Location? = null
        private set

    override fun onLocationChanged(location: Location) {
        lastKnown = location
        val point = TrackPoint(
            lat = location.latitude,
            lng = location.longitude,
            time = fixTime(location),
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            accuracy = if (location.hasAccuracy()) location.accuracy else 10f,
        )
        onPoint(point, if (location.hasSpeed()) location.speed else null)
    }

    /**
     * When the fix was measured, not when it was delivered. With the screen off Android batches
     * locations; delivery time would make a batch look like an impossible jump. Uses elapsedRealtime,
     * which is immune to wall-clock changes.
     */
    private fun fixTime(location: Location): Long {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return System.currentTimeMillis() - ageMs.coerceIn(0, 10 * 60_000)
    }

    override fun onProviderEnabled(provider: String) = onProviderChanged(true)

    override fun onProviderDisabled(provider: String) = onProviderChanged(false)

    companion object {
        const val INTERVAL_MS = 1000L

        /** Best available location provider. */
        fun pickProvider(available: List<String>): String? =
            listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER).firstOrNull { it in available }
    }
}
