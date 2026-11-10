package com.example.runtrail.domain

import com.example.runtrail.data.RoutePoint
import com.example.runtrail.data.Run
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * เส้นทางสำหรับวาดภาพย่อ/กราฟ
 * - การวิ่งจริง: แปลงจุด GPS (หรือเส้นทางย่อ) เป็นพิกัด 0..1
 * - การวิ่งที่เพิ่มเอง: ไม่มีเส้นทาง (คืนรายการว่าง ไม่สร้างเส้นปลอม)
 * - demoRoute: เส้นตัวอย่างสำหรับหน้า Welcome เท่านั้น
 */
object RouteGenerator {

    private const val POINTS = 180

    fun route(run: Run): List<RoutePoint> {
        val points = run.track.ifEmpty { run.thumb }
        if (points.size < 2) return emptyList()
        return TrackMath.normalize(points, RunCalculator.pace(run).roundToInt().coerceAtLeast(1))
    }

    /** เส้นทางวนรอบสวนแบบคงที่ ใช้ประกอบภาพในหน้าแนะนำแอป */
    fun demoRoute(seed: Int = 1): List<RoutePoint> {
        val rnd = Random(seed * 7919 + 13)
        val p1 = rnd.nextDouble(0.0, 2 * PI)
        val p2 = rnd.nextDouble(0.0, 2 * PI)
        val a1 = rnd.nextDouble(0.06, 0.14)
        val a2 = rnd.nextDouble(0.03, 0.08)
        val rx = rnd.nextDouble(0.30, 0.36)
        val ry = rnd.nextDouble(0.27, 0.33)
        val start = PI * 0.78 + rnd.nextDouble(-0.25, 0.25)
        val sweep = 2 * PI * 0.93
        var alt = 4f
        return List(POINTS) { i ->
            val f = i / (POINTS - 1).toDouble()
            val t = start + sweep * f
            val r = 1 + a1 * sin(2 * t + p1) + a2 * sin(3 * t + p2)
            val pace = (372 + sin(f * 9 + p1) * 28 + sin(f * 13 + p2) * 3).roundToInt()
            alt += if (sin(t * 3 + p1) > -0.3) 0.05f else -0.03f
            RoutePoint((0.5 + rx * r * cos(t)).toFloat(), (0.52 + ry * r * sin(t)).toFloat(), pace, alt)
        }
    }
}
