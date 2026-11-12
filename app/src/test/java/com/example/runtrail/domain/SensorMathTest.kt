package com.example.runtrail.domain

import com.example.runtrail.sensor.CadenceTracker
import com.example.runtrail.sensor.CompassHelper
import org.junit.Assert.assertEquals
import org.junit.Test

/** ค่าที่ได้จากเซนเซอร์ เทียบกับคำตอบที่คำนวณด้วยมือ (AUDIT เฟส 4) */
class SensorMathTest {

    @Test fun cadenceFromStepCounter() {
        // 170 ก้าว/นาที: 30 วินาที เดินเพิ่ม 85 ก้าว (ค่าสะสมเริ่มที่ 10,000 จากตอนเปิดเครื่อง)
        val t0 = 1_000_000L
        val samples = List(31) { i -> t0 + i * 1000L to 10_000f + i * 85f / 30 }
        assertEquals(170, CadenceTracker.compute(samples, now = t0 + 30_000))
        // หยุดเดินเกิน 5 วินาที → 0 (แสดง "--")
        assertEquals(0, CadenceTracker.compute(samples, now = t0 + 36_000))
        // ข้อมูลไม่ถึง 6 วินาที → 0
        assertEquals(0, CadenceTracker.compute(samples.take(5), now = t0 + 4_000))
        // ไม่มีเซนเซอร์ / ยังไม่มีค่า → 0 ไม่เด้ง
        assertEquals(0, CadenceTracker.compute(emptyList(), now = t0))
        // ค่าสะสมถูกรีเซ็ต (รีบูตเครื่อง) → ไม่ติดลบ
        assertEquals(0, CadenceTracker.compute(listOf(t0 to 5000f, t0 + 20_000 to 10f), now = t0 + 20_000))
    }

    @Test fun caloriesFollowAcsmFormula() {
        // 10 กม. ใน 60 นาที, 70 กก.: ความเร็ว 166.67 ม./นาที → MET = (0.2×166.67+3.5)/3.5 = 10.524 → 736.7 kcal
        assertEquals(736.7, RunCalculator.calories(10_000.0, 3600, 70), 0.1)
        assertEquals(0.0, RunCalculator.calories(1000.0, 0, 70), 0.0)
        // เดินอยู่กับที่ 10 นาที (ระยะ 0) → MET 1 → 11.7 kcal
        assertEquals(11.67, RunCalculator.calories(0.0, 600, 70), 0.01)
    }

    @Test fun paceAndDistanceUnits() {
        assertEquals(360.0, RunCalculator.paceSecPerKm(1800, 5000.0), 0.0)
        assertEquals(0.0, RunCalculator.paceSecPerKm(1800, 0.0), 0.0)        // ระยะ 0 ไม่หารศูนย์
        assertEquals("9:39", Fmt.pace(360.0, miles = true))                  // 6:00/กม. = 9:39/ไมล์
        assertEquals("3.11", Fmt.distance(5000.0, miles = true))
    }

    @Test fun compassHeadingFromAzimuth() {
        assertEquals(0, CompassHelper.headingDegrees(0f))
        assertEquals(90, CompassHelper.headingDegrees((Math.PI / 2).toFloat()))
        assertEquals(270, CompassHelper.headingDegrees((-Math.PI / 2).toFloat()))
        assertEquals(0, CompassHelper.headingDegrees(Math.PI.toFloat() * 2 - 0.001f))
        // AUDIT A15: ค่าเสื่อมจากเซนเซอร์ต้องถูกข้าม ไม่ใช่ทำให้แอปเด้ง
        assertEquals(null, CompassHelper.headingDegrees(Float.NaN))
        assertEquals(null, CompassHelper.headingDegrees(Float.POSITIVE_INFINITY))
    }

    @Test fun compassLabels() {
        assertEquals("N 0°", CompassHelper.label(0))
        assertEquals("N 22°", CompassHelper.label(22))
        assertEquals("NE 23°", CompassHelper.label(23))
        assertEquals("E 90°", CompassHelper.label(90))
        assertEquals("NW 337°", CompassHelper.label(337))
        assertEquals("N 338°", CompassHelper.label(338))
        assertEquals("N 359°", CompassHelper.label(359))
    }
}
