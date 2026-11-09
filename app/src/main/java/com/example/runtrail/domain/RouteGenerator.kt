package com.example.runtrail.domain

import com.example.runtrail.data.RoutePoint
import com.example.runtrail.data.Run
import com.example.runtrail.data.SampleData
import com.example.runtrail.data.Split
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * สร้างเส้นทางและ splits จำลองจาก seed ของการวิ่ง
 * (ช่วง UI ยังไม่มีจุด GPS จริงใน run_points จึงสร้างรูปเส้นทางวนรอบสวนแบบคงที่ต่อการวิ่ง)
 */
object RouteGenerator {

    private const val POINTS = 180

    /** เส้นทางของการวิ่งที่ยังไม่บันทึก (หน้าวิ่งสด / Welcome) */
    fun demoRoute(seed: Int = 1): List<RoutePoint> =
        route(Run(title = "", startTime = 0, distanceM = 5020.0, movingTimeS = 1868, routeSeed = seed))

    fun route(run: Run): List<RoutePoint> {
        if (run.track.size >= 2) {
            return TrackMath.normalize(run.track, RunCalculator.pace(run).roundToInt().coerceAtLeast(1))
        }
        val rnd = Random(run.routeSeed * 7919 + 13)
        val p1 = rnd.nextDouble(0.0, 2 * PI)
        val p2 = rnd.nextDouble(0.0, 2 * PI)
        val a1 = rnd.nextDouble(0.06, 0.14)
        val a2 = rnd.nextDouble(0.03, 0.08)
        val rx = rnd.nextDouble(0.30, 0.36)
        val ry = rnd.nextDouble(0.27, 0.33)
        val start = PI * 0.78 + rnd.nextDouble(-0.25, 0.25)
        val sweep = 2 * PI * 0.93
        val splits = splits(run)
        val distKm = run.distanceM / 1000.0
        val avgPace = RunCalculator.pace(run).let { if (it <= 0) 372.0 else it }

        var alt = 4f
        return List(POINTS) { i ->
            val f = i / (POINTS - 1).toDouble()
            val t = start + sweep * f
            val r = 1 + a1 * sin(2 * t + p1) + a2 * sin(3 * t + p2)
            val x = 0.5 + rx * r * cos(t)
            val y = 0.52 + ry * r * sin(t)
            val km = f * distKm
            val split = splits.getOrNull(km.toInt().coerceAtMost(splits.size - 1))
            // ไล่ค่าเพซระหว่างกึ่งกลางของแต่ละ split + คลื่นเล็กน้อย ให้กราฟ/สีเส้นดูเป็นธรรมชาติ
            val base = if (splits.isEmpty()) avgPace else {
                val pos = (km - 0.5).coerceIn(0.0, (splits.size - 1).toDouble())
                val i0 = pos.toInt()
                val i1 = (i0 + 1).coerceAtMost(splits.size - 1)
                val t0 = pos - i0
                splits[i0].paceSecPerKm * (1 - t0) + splits[i1].paceSecPerKm * t0
            }
            val pace = (base + sin(f * 13 + p2) * 3).roundToInt()
            alt += ((split?.elevationDeltaM ?: 2) / (POINTS / distKm.coerceAtLeast(1.0)).toFloat()) *
                (if (sin(t * 3 + p1) > -0.3) 1f else -0.6f)
            RoutePoint(x.toFloat(), y.toFloat(), pace, alt)
        }
    }

    fun splits(run: Run): List<Split> {
        val distKm = run.distanceM / 1000.0
        if (distKm <= 0.0) return emptyList()
        if (run.track.size >= 2) return TrackMath.splits(run.track)
        if (run.title == "วิ่งเย็นริมสวน" && run.distanceM in 5000.0..5050.0) return SampleData.figmaSplits

        val avg = RunCalculator.pace(run)
        val n = ceil(distKm).toInt().coerceAtLeast(1)
        val rnd = Random(run.routeSeed * 104729 + 7)
        val offsets = MutableList(n) { rnd.nextInt(-24, 25) }
        val mean = offsets.average()
        return List(n) { i ->
            Split(
                km = i + 1,
                paceSecPerKm = (avg + offsets[i] - mean).roundToInt(),
                elevationDeltaM = rnd.nextInt(1, 10),
            )
        }
    }
}
