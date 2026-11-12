package com.example.runtrail.domain

import com.example.runtrail.data.TrackPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * ลดการสั่นของตำแหน่ง GPS ก่อนนำไปคิดระยะ (Kalman filter แบบตำแหน่งคงที่ + ความไม่แน่นอนที่โตตามเวลา)
 *
 * - จุดที่ GPS บอกว่าแม่นยำ (accuracy เล็ก) มีน้ำหนักมาก · จุดที่คลาดมากแทบไม่ดึงตำแหน่ง
 * - ความไม่แน่นอนโตขึ้น [processNoiseMps]² ต่อวินาที จึงตามคนวิ่งทัน และหลังหยุด/สัญญาณหายจะกระโดดไปตำแหน่งใหม่ได้เอง
 * - ทำงานเป็นเมตร (เหนือ/ตะวันออก) รอบจุดอ้างอิงแรก
 *
 * เหตุผล: สัญญาณรบกวน ±4–7 ม. ทำให้การเดินถูกนับระยะเกินจริง 12–30 % (GpsNoiseDistanceTest)
 * ค่าที่ใช้จริง [forRecording]: ปรับตามความเร็ว ×1.5 → คลาดไม่เกิน −7 %…+4 % กับสัญญาณแบบสมจริง
 */
class PositionSmoother(
    private val processNoiseMps: Double = DEFAULT_PROCESS_NOISE,
    /** > 0 = ปรับตามความเร็วที่ GPS วัดได้ (Doppler): q = speed × ค่านี้ ในช่วง [MIN_Q, MAX_Q] */
    private val speedFactor: Double = 0.0,
) {

    private var lat0 = 0.0
    private var lng0 = 0.0
    private var kLng = 1.0
    private var n = 0.0
    private var e = 0.0
    private var variance = -1.0     // < 0 = ยังไม่มีจุด
    private var lastTime = 0L

    fun reset() { variance = -1.0 }

    /** คืนจุดที่ปรับแล้ว (เวลา ความสูง และความแม่นยำเดิม) */
    fun update(p: TrackPoint, speedMps: Float? = null): TrackPoint {
        val acc = p.accuracy.toDouble().coerceAtLeast(MIN_ACCURACY)
        if (variance < 0) {
            lat0 = p.lat; lng0 = p.lng
            kLng = METERS_PER_DEG * cos(Math.toRadians(p.lat))
            n = 0.0; e = 0.0
            variance = acc * acc
            lastTime = p.time
            return p
        }
        val dt = ((p.time - lastTime) / 1000.0).coerceAtLeast(0.0)
        lastTime = p.time
        val q = if (speedFactor > 0 && speedMps != null && speedMps >= 0f) (speedMps * speedFactor).coerceIn(MIN_Q, MAX_Q)
        else processNoiseMps
        variance += dt * q * q
        val mn = (p.lat - lat0) * METERS_PER_DEG
        val me = (p.lng - lng0) * kLng
        val k = variance / (variance + acc * acc)
        n += k * (mn - n)
        e += k * (me - e)
        variance *= (1 - k)
        return p.copy(lat = lat0 + n / METERS_PER_DEG, lng = lng0 + e / kLng)
    }

    /** ความไม่แน่นอนปัจจุบัน (เมตร) */
    val uncertaintyM: Double get() = if (variance < 0) Double.NaN else sqrt(variance)

    companion object {
        /** ค่าที่หน้าวิ่งใช้จริง (เลือกจากการวัดใน GpsNoiseDistanceTest) */
        fun forRecording() = PositionSmoother(speedFactor = 1.5)

        const val DEFAULT_PROCESS_NOISE = 3.0
        private const val MIN_ACCURACY = 1.0
        const val MIN_Q = 1.5
        const val MAX_Q = 6.0
        private const val METERS_PER_DEG = 111_194.93
    }
}
