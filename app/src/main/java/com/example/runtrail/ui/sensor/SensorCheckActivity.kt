package com.example.runtrail.ui.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.databinding.ActivitySensorCheckBinding
import com.example.runtrail.databinding.ItemSensorBinding
import com.example.runtrail.sensor.SensorHelper
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.Sheets
import java.util.Locale
import kotlin.math.sqrt

/**
 * 04 · Sensor Check (MA12) — เครื่องมี/ไม่มีเซนเซอร์อะไร + ค่าสด
 * - register ใน onResume / unregister ใน onPause
 * - onAccuracyChanged: เข็มทิศความแม่นยำต่ำ → แสดงป้าย "ปรับเทียบ"
 */
class SensorCheckActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivitySensorCheckBinding
    private lateinit var sm: SensorManager
    private val rows = mutableMapOf<String, ItemSensorBinding>()
    private val sensorKeys = mutableMapOf<Int, String>()

    /** แตะแถวที่ "ต้องขอสิทธิ์" → ขอสิทธิ์ได้จากหน้านี้เลย (เดิมแตะแล้วไม่มีอะไรเกิดขึ้น, AUDIT A11) */
    private val askPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { updateGps(); updateStepPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivitySensorCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.content.applySystemBarsPadding(top = true, bottom = true)
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        binding.btnBack.setOnClickListener { finish() }

        SensorHelper.specs.forEach { spec ->
            val parent = if (spec.group == SensorHelper.Group.MOTION) binding.groupMotion else binding.groupEnv
            val row = ItemSensorBinding.inflate(layoutInflater, parent, false)
            row.divider.visibility = if (parent.childCount == 0) View.GONE else View.VISIBLE
            row.ivIcon.setImageResource(spec.icon)
            row.tvName.text = spec.name
            row.tvKind.text = SensorHelper.kind(this, spec)
            val available = SensorHelper.isAvailable(this, spec)
            if (available) {
                row.tvValue.text = "—"
                setStatus(row, R.string.sensor_status_ready, R.color.run_green_dark, R.drawable.dot_green_s)
            } else {
                row.tvValue.text = "—"
                row.tvValue.setTextColor(getColor(R.color.text_disabled))
                row.ivIcon.setBackgroundResource(R.drawable.bg_icon_chip_canvas_s)
                row.ivIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.text_disabled)
                setStatus(row, R.string.sensor_status_fallback, R.color.text_tertiary, 0)
            }
            spec.sensorType?.let { sensorKeys[it] = spec.key }
            rows[spec.key] = row
            parent.addView(row.root)
        }

        val total = SensorHelper.specs.size
        val found = SensorHelper.countAvailable(this)
        binding.ring.progress = found / total.toFloat()
        binding.tvRing.text = "$found/$total"
        binding.tvSummary.text = getString(R.string.sensor_summary, found, total)
        binding.tvReady.setText(if (SensorHelper.isAvailable(this, SensorHelper.specs.first())) R.string.sensor_ready else R.string.sensor_not_ready)
    }

    private fun setStatus(row: ItemSensorBinding, text: Int, color: Int, dot: Int) {
        row.tvStatus.setText(text)
        row.tvStatus.setTextColor(getColor(color))
        row.tvStatus.background = null
        row.tvStatus.setPadding(0, 0, 0, 0)
        row.tvStatus.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
    }

    override fun onResume() {
        super.onResume()
        SensorHelper.specs.forEach { spec ->
            SensorHelper.sensor(this, spec)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        updateGps()
        updateStepPermission()
    }

    override fun onPause() {
        super.onPause()
        sm.unregisterListener(this)
    }

    private fun updateGps() {
        val row = rows[SensorHelper.GPS] ?: return
        if (!SensorHelper.isAvailable(this, SensorHelper.specs.first())) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val permitted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        when {
            !permitted -> {
                row.tvValue.setText(R.string.sensor_gps_no_permission)
                setStatus(row, R.string.sensor_status_need_permission, R.color.warning, R.drawable.dot_warning)
                row.row.setOnClickListener {
                    askPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            !enabled -> {
                row.tvValue.setText(R.string.sensor_gps_off)
                setStatus(row, R.string.sensor_status_off, R.color.warning, R.drawable.dot_warning)
                row.row.setOnClickListener {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    catch (e: android.content.ActivityNotFoundException) { }
                }
            }
            else -> {
                row.tvValue.setText(R.string.sensor_gps_on)
                setStatus(row, R.string.sensor_status_ready, R.color.run_green_dark, R.drawable.dot_green_s)
                row.row.setOnClickListener(null)
            }
        }
    }

    private fun updateStepPermission() {
        val row = rows["step"] ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED &&
            SensorHelper.isAvailable(this, SensorHelper.specs[1])
        ) {
            setStatus(row, R.string.sensor_status_need_permission, R.color.warning, R.drawable.dot_warning)
            row.row.setOnClickListener { askPermission.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION)) }
        } else if (SensorHelper.isAvailable(this, SensorHelper.specs[1])) {
            setStatus(row, R.string.sensor_status_ready, R.color.run_green_dark, R.drawable.dot_green_s)
            row.row.setOnClickListener(null)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val key = sensorKeys[event.sensor.type] ?: return
        val row = rows[key] ?: return
        val v = event.values
        row.tvValue.text = when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> getString(R.string.sensor_steps, String.format(Locale.US, "%,d", v[0].toLong()))
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION ->
                String.format(Locale.US, "%.2f m/s²", sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
            Sensor.TYPE_MAGNETIC_FIELD -> if (row.tvStatus.text == getString(R.string.sensor_calibrate)) {
                getString(R.string.sensor_low_accuracy)
            } else String.format(Locale.US, "%.0f μT", sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
            Sensor.TYPE_PRESSURE ->
                if (v[0] in 300f..1100f) String.format(Locale.US, "%.1f hPa", v[0]) else "—"
            Sensor.TYPE_LIGHT -> String.format(Locale.US, "%.0f lux", v[0])
            Sensor.TYPE_AMBIENT_TEMPERATURE ->
                if (v[0] in -40f..80f) String.format(Locale.US, "%.1f°C", v[0]) else "—"
            else -> "—"
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type != Sensor.TYPE_MAGNETIC_FIELD) return
        val row = rows["magnet"] ?: return
        if (accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            row.tvValue.setText(R.string.sensor_low_accuracy)
            row.tvValue.setTextColor(getColor(R.color.warning))
            row.tvStatus.setText(R.string.sensor_calibrate)
            row.tvStatus.setTextColor(getColor(R.color.warning))
            row.tvStatus.setCompoundDrawablesRelative(null, null, null, null)
            row.tvStatus.setBackgroundResource(R.drawable.bg_pill_warning_soft)
            row.tvStatus.setPadding(16, 0, 16, 0)
            row.row.setOnClickListener {
                Sheets.confirm(
                    this, R.drawable.ic_compass, getString(R.string.sensor_calibrate_title),
                    getString(R.string.sensor_calibrate_body), confirmText = getString(R.string.action_ok), cancelText = null,
                )
            }
        } else {
            row.tvValue.setTextColor(getColor(R.color.text_primary))
            setStatus(row, R.string.sensor_status_ready, R.color.run_green_dark, R.drawable.dot_green_s)
            row.row.setOnClickListener(null)
        }
    }
}
