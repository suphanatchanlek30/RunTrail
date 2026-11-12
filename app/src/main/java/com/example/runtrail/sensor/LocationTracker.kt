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

/**
 * อ่านตำแหน่งจริงจาก GPS ด้วย LocationManager ทุก 1 วินาที (MA12)
 * เรียก start() ใน onResume/ตอนเริ่มวิ่ง และ stop() เมื่อจบ
 */
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

    /**
     * แหล่งตำแหน่งที่ใช้: GPS ถ้าเครื่องมี · ไม่มี GPS (แท็บเล็ตบางรุ่น) → fused / network
     * เดิมขอ GPS_PROVIDER ตรง ๆ → เครื่องที่ไม่มีจะ IllegalArgumentException และ service เด้ง (AUDIT A4)
     */
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
            return false   // สิทธิ์ถูกถอนระหว่างตรวจกับขอ
        }
        running = true
        // จุดล่าสุดที่เครื่องรู้ (GPS หรือเครือข่าย อันที่ใหม่กว่า) ให้แผนที่ขยับไปตำแหน่งปัจจุบันก่อนได้สัญญาณแรก
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
     * เวลาที่ GPS วัดตำแหน่งนี้ได้จริง (ไม่ใช่เวลาที่แอปได้รับ)
     * ตอนจอดับ Android ส่งตำแหน่งมาเป็นชุด ถ้าใช้เวลาที่ได้รับ จุดในชุดเดียวกันจะดูเหมือนกระโดดเร็วผิดปกติแล้วถูกตัดทิ้ง
     * ใช้นาฬิกา elapsedRealtime (ไม่เพี้ยนตามการตั้งเวลาเครื่อง) แปลงเป็น epoch
     */
    private fun fixTime(location: Location): Long {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return System.currentTimeMillis() - ageMs.coerceIn(0, 10 * 60_000)
    }

    override fun onProviderEnabled(provider: String) = onProviderChanged(true)

    override fun onProviderDisabled(provider: String) = onProviderChanged(false)

    companion object {
        const val INTERVAL_MS = 1000L

        /** เลือกแหล่งตำแหน่งที่ดีที่สุดที่เครื่องมี (ฟังก์ชันล้วน ทดสอบได้) */
        fun pickProvider(available: List<String>): String? =
            listOf(LocationManager.GPS_PROVIDER, "fused", LocationManager.NETWORK_PROVIDER).firstOrNull { it in available }
    }
}
