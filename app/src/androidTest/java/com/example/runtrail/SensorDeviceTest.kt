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
 * เซนเซอร์บนเครื่อง (AUDIT §6)
 *  - Step counter: emulator ไม่มี → ส่ง SensorEvent เข้า listener ของ CadenceTracker ตามเวลาจริง (เส้นทางเดียวกับฮาร์ดแวร์)
 *  - เซนเซอร์ที่ emulator มี: Mac สั่งค่าด้วย `adb emu sensor set …` (ดู scripts ใน AUDIT) แล้วตรวจว่าหน้าตรวจเซนเซอร์แสดงค่านั้น
 *    รันเมื่อส่ง `-e hostSensors 1` เท่านั้น
 */
@RunWith(AndroidJUnit4::class)
class SensorDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext

    @Test fun cadenceFromStepCounterEventsInRealTime() {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val any: Sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: sm.getSensorList(Sensor.TYPE_ALL).first()
        val tracker = CadenceTracker(ctx)
        // 170 ก้าว/นาที เป็นเวลา 8 วินาที (ค่าสะสมแบบ Step Counter จริง: เริ่มจากก้าวตั้งแต่เปิดเครื่อง)
        // จำนวนก้าวคิดจากเวลาที่ผ่านไปจริง (เหมือนตัวนับก้าวจริง) ไม่ขึ้นกับว่า sleep ของ test คลาดไปเท่าไร
        val t0 = System.currentTimeMillis()
        repeat(17) {
            val steps = 12_345f + 170f * (System.currentTimeMillis() - t0) / 60_000f
            val e = stepEvent(any, steps)
            inst.runOnMainSync { tracker.onSensorChanged(e) }
            Thread.sleep(500)
        }
        val cadence = tracker.cadence()
        assertEquals("cadence $cadence", 170.0, cadence.toDouble(), 4.0)
        // หยุดเดินเกิน 5 วินาที → 0 (หน้าวิ่งแสดง "--")
        Thread.sleep(5_500)
        assertEquals(0, tracker.cadence())
    }

    @Test fun compassIgnoresDegenerateReadingsInsteadOfCrashing() {
        // เคยทำให้แอปเด้งทั้งแอปบนหน้าวิ่ง: ทิศคำนวณได้ NaN → roundToInt() โยน IllegalArgumentException (AUDIT A15)
        val sm = ctx.getSystemService(SensorManager::class.java)
        val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val headings = mutableListOf<Int>()
        val compass = com.example.runtrail.sensor.CompassHelper(ctx) { headings += it }
        inst.runOnMainSync {
            rotation?.let { compass.onSensorChanged(event(it, floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0f))) }
            // แรงโน้มถ่วงขนานกับสนามแม่เหล็ก (ค่าที่ทำให้ fusion ได้ NaN ในการทดสอบเต็มชุด)
            if (accel != null && magnet != null) {
                compass.onSensorChanged(event(accel, floatArrayOf(0f, 9.81f, 0f)))
                compass.onSensorChanged(event(magnet, floatArrayOf(0f, 45f, 0f)))
            }
        }
        assertEquals("no heading from invalid data", emptyList<Int>(), headings)
        // ค่าปกติยังได้ทิศ: เครื่องตั้งตรง หันหน้าทิศเหนือ
        inst.runOnMainSync {
            compass.onSensorChanged(event(accel!!, floatArrayOf(0f, 9.81f, 0f)))
            compass.onSensorChanged(event(magnet!!, floatArrayOf(0f, 20f, -40f)))
        }
        assertEquals(1, headings.size)
        assertTrue("heading ${headings[0]}", headings[0] in 0..359)
    }

    /**
     * SensorEvent แบบเดียวกับที่ระบบส่งมา — สร้างผ่าน constructor ภายใน SensorEvent(int) แล้วใส่ค่า
     * (constructor สาธารณะ 4 ตัวแปรไม่มีบน image นี้)
     */
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
        // อุณหภูมิไม่อยู่ในรายการ: เซนเซอร์อุณหภูมิของ emulator ส่ง -1e30 เสมอ (ไม่รับค่าจาก console)
        // → ตรวจแทนว่าแอปกรองค่าผิดปกติทิ้ง ไม่แสดงตัวเลขเพี้ยนบนจอ
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
