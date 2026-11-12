package com.example.runtrail.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

/**
 * เข็มทิศจาก rotation vector (ถ้าไม่มี ใช้ magnetometer + accelerometer) — MA12
 * register ใน onResume / unregister ใน onPause
 */
class CompassHelper(context: Context, private val onHeading: (degrees: Int) -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private val matrix = FloatArray(9)
    private val orientation = FloatArray(3)

    val available: Boolean get() = rotation != null || (magnet != null && accel != null)

    fun start() {
        if (rotation != null) {
            sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
        } else {
            magnet?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
            accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> SensorManager.getRotationMatrixFromVector(matrix, event.values)
            Sensor.TYPE_ACCELEROMETER -> { event.values.copyInto(gravity); if (!fromMagAccel()) return }
            Sensor.TYPE_MAGNETIC_FIELD -> { event.values.copyInto(geomagnetic); if (!fromMagAccel()) return }
        }
        SensorManager.getOrientation(matrix, orientation)
        // ค่าเสื่อม (สนามแม่เหล็กขนานกับแรงโน้มถ่วง / แม่เหล็กรบกวนแรง) ได้ NaN → ข้ามเหตุการณ์นี้
        // เดิม roundToInt() ของ NaN โยน exception ทำให้แอปเด้งทั้งแอปบนหน้าวิ่ง (AUDIT A15)
        headingDegrees(orientation[0])?.let(onHeading)
    }

    private fun fromMagAccel(): Boolean = SensorManager.getRotationMatrix(matrix, null, gravity, geomagnetic)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        /** มุม azimuth (เรเดียน) → องศา 0..359 · null ถ้าค่าใช้ไม่ได้ (NaN / อนันต์) */
        fun headingDegrees(azimuthRad: Float): Int? {
            if (azimuthRad.isNaN() || azimuthRad.isInfinite()) return null
            return ((Math.toDegrees(azimuthRad.toDouble()) + 360) % 360).roundToInt() % 360
        }

        private val NAMES = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        fun label(deg: Int): String = "${NAMES[((deg + 22) % 360) / 45]} $deg°"
    }
}
