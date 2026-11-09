package com.example.runtrail.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Cadence (ก้าว/นาที) จาก Step Counter — นับส่วนต่างจำนวนก้าวในช่วง 30 วินาทีล่าสุด (MA12)
 */
class CadenceTracker(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    val available: Boolean get() = sensor != null && allowed

    fun start() { if (available) sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI) }

    fun stop() = sm.unregisterListener(this)

    /** ก้าว/นาที หรือ 0 ถ้ายังไม่มีข้อมูล */
    fun cadence(now: Long = System.currentTimeMillis()): Int {
        while (samples.size > 2 && now - samples.first().first > WINDOW_MS) samples.removeFirst()
        if (samples.size < 2) return 0
        val (t0, s0) = samples.first()
        val (t1, s1) = samples.last()
        val minutes = (t1 - t0) / 60000.0
        if (minutes < 0.1 || now - t1 > 5000) return 0
        return ((s1 - s0) / minutes).toInt().coerceIn(0, 260)
    }

    override fun onSensorChanged(event: SensorEvent) {
        samples.addLast(System.currentTimeMillis() to event.values[0])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val WINDOW_MS = 30_000L
    }
}
