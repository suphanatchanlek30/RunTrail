package com.example.runtrail.sensor

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import com.example.runtrail.R
import com.example.runtrail.domain.Lang

/** Sensors the app uses and whether this device has them. */
object SensorHelper {

    enum class Group { MOTION, ENVIRONMENT }

    data class Spec(
        val key: String,
        val name: String,
        val group: Group,
        val icon: Int,
        val sensorType: Int?,          // null = GPS (LocationManager)
        val usage: String,
    )

    const val GPS = "gps"

    val specs: List<Spec> get() = listOf(
        Spec(GPS, "GPS", Group.MOTION, R.drawable.ic_nav, null, "Location"),
        Spec("step", "Step Counter", Group.MOTION, R.drawable.ic_activity, Sensor.TYPE_STEP_COUNTER, ""),
        Spec("accel", "Accelerometer", Group.MOTION, R.drawable.ic_zap, Sensor.TYPE_ACCELEROMETER, Lang.t("เขย่า · เข็มทิศ", "Shake · Compass")),
        Spec("linear", "Linear Acceleration", Group.MOTION, R.drawable.ic_zap, Sensor.TYPE_LINEAR_ACCELERATION, ""),
        Spec("magnet", "Magnetometer", Group.MOTION, R.drawable.ic_magnet, Sensor.TYPE_MAGNETIC_FIELD, Lang.t("เข็มทิศ", "Compass")),
        Spec("baro", "Barometer", Group.ENVIRONMENT, R.drawable.ic_mountain, Sensor.TYPE_PRESSURE, Lang.t("ความสูง", "Altitude")),
        Spec("light", "Light", Group.ENVIRONMENT, R.drawable.ic_sun, Sensor.TYPE_LIGHT, Lang.t("โหมดกลางคืน", "Night mode")),
        Spec("temp", "Ambient Temp", Group.ENVIRONMENT, R.drawable.ic_thermo, Sensor.TYPE_AMBIENT_TEMPERATURE, Lang.t("อุณหภูมิ", "Temperature")),
    )

    fun sensor(context: Context, spec: Spec): Sensor? {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return spec.sensorType?.let { sm.getDefaultSensor(it) }
    }

    fun isAvailable(context: Context, spec: Spec): Boolean =
        if (spec.sensorType == null) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
        } else {
            sensor(context, spec) != null
        }

    /** "Hardware" or "Software" (computed from other sensors). */
    fun kind(context: Context, spec: Spec): String {
        if (spec.sensorType == null) return "Location · Hardware"
        val s = sensor(context, spec) ?: return Lang.t("ไม่มีในเครื่องนี้", "Not on this device")
        val software = spec.sensorType == Sensor.TYPE_LINEAR_ACCELERATION ||
            s.name.contains("virtual", true) || s.name.contains("fusion", true)
        val base = if (software) "Software" else "Hardware"
        return if (spec.usage.isNotEmpty()) "$base · ${spec.usage}" else base
    }

    fun countAvailable(context: Context): Int = specs.count { isAvailable(context, it) }
}
