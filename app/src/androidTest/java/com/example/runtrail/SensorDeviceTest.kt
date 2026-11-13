package com.example.runtrail

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.sensor.CadenceTracker
import com.example.runtrail.ui.sensor.SensorCheckActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device sensors.
 *
 * The emulator has no step counter, so SensorEvents are fed to CadenceTracker's listener in real time.
 * Sensors the emulator does have are set from the host with `adb emu sensor set …` and checked on the
 * sensor screen; that part runs only with `-e hostSensors 1`.
 */
@RunWith(AndroidJUnit4::class)
class SensorDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test fun cadenceFromStepCounterEventsInRealTime() {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val any: Sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: sm.getSensorList(Sensor.TYPE_ALL).first()
        val tracker = CadenceTracker(ctx)
        // 170 steps/min for 8 s, as a cumulative counter starting from steps since boot.
        // Steps follow elapsed real time, so sleep jitter in the test does not matter.
        val t0 = System.currentTimeMillis()
        repeat(17) {
            val steps = 12_345f + 170f * (System.currentTimeMillis() - t0) / 60_000f
            val e = stepEvent(any, steps)
            inst.runOnMainSync { tracker.onSensorChanged(e) }
            Thread.sleep(500)
        }
        val cadence = tracker.cadence()
        assertEquals("cadence $cadence", 170.0, cadence.toDouble(), 4.0)
        // No step for more than 5 s → 0 (shown as "--").
        Thread.sleep(5_500)
        assertEquals(0, tracker.cadence())
    }

    @Test fun compassIgnoresDegenerateReadingsInsteadOfCrashing() {
        // A NaN heading must be skipped, not crash the live screen.
        val sm = ctx.getSystemService(SensorManager::class.java)
        val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val headings = mutableListOf<Int>()
        val compass = com.example.runtrail.sensor.CompassHelper(ctx) { headings += it }
        inst.runOnMainSync {
            rotation?.let { compass.onSensorChanged(event(it, floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0f))) }
            // Gravity parallel to the magnetic field makes sensor fusion return NaN.
            if (accel != null && magnet != null) {
                compass.onSensorChanged(event(accel, floatArrayOf(0f, 9.81f, 0f)))
                compass.onSensorChanged(event(magnet, floatArrayOf(0f, 45f, 0f)))
            }
        }
        assertEquals("no heading from invalid data", emptyList<Int>(), headings)
        // A normal reading still gives a heading: device upright, facing north.
        inst.runOnMainSync {
            compass.onSensorChanged(event(accel!!, floatArrayOf(0f, 9.81f, 0f)))
            compass.onSensorChanged(event(magnet!!, floatArrayOf(0f, 20f, -40f)))
        }
        assertEquals(1, headings.size)
        assertTrue("heading ${headings[0]}", headings[0] in 0..359)
    }

    /** Builds a SensorEvent through the hidden SensorEvent(int) constructor; the public one is missing on this image. */
    private fun event(sensor: Sensor, values: FloatArray): SensorEvent =
        SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
            .newInstance(values.size).also {
                values.copyInto(it.values); it.sensor = sensor; it.timestamp = SystemClock.elapsedRealtimeNanos()
                it.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
            }

    private fun stepEvent(sensor: Sensor, steps: Float): SensorEvent = event(sensor, floatArrayOf(steps))

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    @Test fun sensorScreenShowsValuesSetFromTheHost() {
        assumeTrue("run with -e hostSensors 1 while the host sets emulator sensors",
            InstrumentationRegistry.getArguments().getString("hostSensors") == "1")
        // Temperature is excluded: the emulator always reports -1e30, which the app must filter out.
        val expected = listOf("1005.5 hPa", "321 lux", "9.81 m/s²", "45 μT")
        ActivityScenario.launch(SensorCheckActivity::class.java).use { s ->
            val end = SystemClock.uptimeMillis() + 15_000
            var seen: List<String> = emptyList()
            while (SystemClock.uptimeMillis() < end) {
                s.onActivity { seen = texts(it.window.decorView) }
                if (expected.all { it in seen }) break
                Thread.sleep(300)
            }
            expected.forEach { assertEquals("value $it on screen; saw ${seen.filter { t -> t.any(Char::isDigit) }}", true, it in seen) }
            assertEquals("no absurd temperature shown: $seen", false, seen.any { it.endsWith("°C") && it.length > 8 })
        }
    }
}
