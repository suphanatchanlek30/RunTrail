package com.example.runtrail.domain

import com.example.runtrail.data.Pause
import com.example.runtrail.data.TrackPoint
import kotlin.math.roundToInt

/**
 * แปลงข้อมูลชุดเล็กเป็นข้อความเพื่อเก็บในคอลัมน์เดียว (Kotlin ล้วน ทดสอบบน JVM ได้)
 * - เส้นทางย่อ: "lat,lng,time,alt,acc;..."
 * - ช่วงหยุด: "start-end;..."
 */
object Codec {

    fun encodeTrack(points: List<TrackPoint>): String =
        points.joinToString(";") { "${it.lat},${it.lng},${it.time},${it.altitude},${it.accuracy}" }

    fun decodeTrack(s: String?): List<TrackPoint> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val c = part.split(',')
            if (c.size < 5) null else try {
                TrackPoint(c[0].toDouble(), c[1].toDouble(), c[2].toLong(), c[3].toDouble(), c[4].toFloat())
            } catch (e: NumberFormatException) {
                null
            }
        }
    }

    fun encodePauses(pauses: List<Pause>): String = pauses.joinToString(";") { "${it.start}-${it.end}" }

    fun decodePauses(s: String?): List<Pause> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(';').mapNotNull { part ->
            val c = part.split('-')
            val a = c.getOrNull(0)?.toLongOrNull()
            val b = c.getOrNull(1)?.toLongOrNull()
            if (a == null || b == null || b < a) null else Pause(a, b)
        }
    }

    /** ลดจำนวนจุดให้เหลือไม่เกิน max โดยเก็บจุดแรกและจุดสุดท้ายไว้เสมอ (ใช้วาดภาพย่อในการ์ด) */
    fun downsample(points: List<TrackPoint>, max: Int = 120): List<TrackPoint> {
        if (points.size <= max) return points
        val step = (points.size - 1) / (max - 1).toDouble()
        return List(max) { i -> points[(i * step).roundToInt().coerceAtMost(points.lastIndex)] }
    }
}
