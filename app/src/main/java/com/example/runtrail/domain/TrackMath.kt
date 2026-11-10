package com.example.runtrail.domain

import com.example.runtrail.data.RoutePoint
import com.example.runtrail.data.Split
import com.example.runtrail.data.TrackPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * คำนวณจากจุด GPS จริง (Kotlin ล้วน ทดสอบบน JVM ได้)
 */
object TrackMath {

    private const val EARTH_R = 6_371_000.0

    /** ระยะทางระหว่าง 2 จุด (เมตร) — สูตร Haversine */
    fun distance(a: TrackPoint, b: TrackPoint): Double = distance(a.lat, a.lng, b.lat, b.lng)

    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** ความเร็วสูงสุดที่ยังนับเป็นการวิ่ง (ม./วิ) — เหมือน backend */
    const val MAX_SPEED_MPS = 9.0

    /**
     * ระยะสะสมของแต่ละจุด (เมตร)
     * ช่วงที่ "กระโดด" เร็วเกินคนวิ่ง (GPS เพิ่งจับตำแหน่งใหม่ได้) ไม่นับระยะ — backend ใช้กฎเดียวกัน
     */
    fun cumulative(track: List<TrackPoint>): DoubleArray {
        val out = DoubleArray(track.size)
        for (i in 1 until track.size) out[i] = out[i - 1] + segment(track[i - 1], track[i])
        return out
    }

    fun segment(a: TrackPoint, b: TrackPoint): Double {
        val d = distance(a, b)
        val dt = (b.time - a.time) / 1000.0
        return if (dt > 0 && d / dt > MAX_SPEED_MPS) 0.0 else d
    }

    /** เพซของแต่ละจุด (วินาที/กม.) เฉลี่ยในหน้าต่างประมาณ 60 ม. เพื่อลดความแกว่งของ GPS */
    fun paces(track: List<TrackPoint>, fallback: Int): IntArray {
        val cum = cumulative(track)
        return IntArray(track.size) { i ->
            var lo = i
            var hi = i
            while (lo > 0 && cum[i] - cum[lo] < 30) lo--
            while (hi < track.size - 1 && cum[hi] - cum[i] < 30) hi++
            val d = cum[hi] - cum[lo]
            val t = (track[hi].time - track[lo].time) / 1000.0
            if (d < 5 || t <= 0) fallback else (t / (d / 1000.0)).roundToInt().coerceIn(150, 1200)
        }
    }

    /** split รายกิโลเมตรจากเวลาที่ผ่านแต่ละหลักกิโลเมตร (ประมาณเชิงเส้นระหว่างจุด) */
    fun splits(track: List<TrackPoint>): List<Split> {
        if (track.size < 2) return emptyList()
        val cum = cumulative(track)
        val total = cum.last()
        if (total < 50) return emptyList()

        fun indexAt(d: Double): Int = cum.indexOfFirst { it >= d }.let { if (it < 0) cum.lastIndex else it }
        fun timeAt(d: Double): Double {
            val i = indexAt(d)
            if (i == 0) return track[0].time.toDouble()
            val seg = cum[i] - cum[i - 1]
            val f = if (seg > 0) ((d - cum[i - 1]) / seg).coerceIn(0.0, 1.0) else 1.0
            return track[i - 1].time + (track[i].time - track[i - 1].time) * f
        }

        val out = mutableListOf<Split>()
        val n = kotlin.math.ceil(total / 1000.0).toInt()
        for (km in 1..n) {
            val startD = (km - 1) * 1000.0
            val endD = minOf(km * 1000.0, total)
            val distKm = (endD - startD) / 1000.0
            if (distKm < 0.05) continue
            val pace = ((timeAt(endD) - timeAt(startD)) / 1000.0 / distKm).roundToInt()
            val elev = elevationGain(track.subList(indexAt(startD), indexAt(endD) + 1))
            out += Split(km, pace, elev)
        }
        return out
    }

    /** ความสูงสะสม (ขึ้นอย่างเดียว) ตัดการแกว่งต่ำกว่า 2 ม. */
    fun elevationGain(track: List<TrackPoint>): Int {
        var gain = 0.0
        var ref = track.firstOrNull()?.altitude ?: return 0
        for (p in track) {
            val d = p.altitude - ref
            if (d >= 2) { gain += d; ref = p.altitude } else if (d <= -2) ref = p.altitude
        }
        return gain.roundToInt()
    }

    /** แปลงพิกัดจริงเป็น 0..1 (รักษาสัดส่วน) สำหรับวาดภาพย่อ/กราฟ */
    fun normalize(track: List<TrackPoint>, fallbackPace: Int): List<RoutePoint> {
        if (track.size < 2) return emptyList()
        val lat0 = track.map { it.lat }.average()
        val k = cos(lat0 * PI / 180)
        val xs = track.map { it.lng * k }
        val ys = track.map { -it.lat }
        val minX = xs.min(); val maxX = xs.max()
        val minY = ys.min(); val maxY = ys.max()
        val span = max(max(maxX - minX, maxY - minY), 1e-9)
        val offX = (span - (maxX - minX)) / 2
        val offY = (span - (maxY - minY)) / 2
        val paces = paces(track, fallbackPace)
        return track.indices.map { i ->
            RoutePoint(
                x = (0.08 + 0.84 * (xs[i] - minX + offX) / span).toFloat(),
                y = (0.08 + 0.84 * (ys[i] - minY + offY) / span).toFloat(),
                paceSecPerKm = paces[i],
                altitudeM = track[i].altitude.toFloat(),
            )
        }
    }
}

/**
 * กรองจุด GPS ขณะวิ่ง: ตัดจุดที่คลาดเคลื่อนสูง / กระโดดเร็วเกินคนวิ่ง / ขยับน้อยกว่าระยะความคลาดเคลื่อน
 */
class GpsFilter(
    private val maxAccuracyM: Float = 25f,
    private val maxSpeedMps: Double = 9.0,
) {
    var last: TrackPoint? = null
        private set

    /** true = จุดล่าสุดที่รับเป็นการย้ายจุดอ้างอิง (GPS เพิ่งจับตำแหน่งใหม่) ไม่มีระยะ แต่ต้องเก็บจุดไว้ */
    var reanchored = false
        private set
    private var fastRejects = 0

    /** คืนระยะที่เพิ่มขึ้น (เมตร) หรือ null ถ้าจุดนี้ไม่ควรเก็บ */
    fun accept(p: TrackPoint): Double? {
        reanchored = false
        if (p.accuracy > maxAccuracyM) return null
        val prev = last
        if (prev == null) { last = p; return 0.0 }
        val d = TrackMath.distance(prev, p)
        val dt = (p.time - prev.time) / 1000.0
        if (dt <= 0) return null
        if (d / dt > maxSpeedMps) {
            // จุดอ้างอิงเดิมอาจผิด (เช่น ตำแหน่งเก่าค้างตอนเปิด GPS) — ถ้าเร็วเกินติดกัน 5 จุดให้เริ่มจุดอ้างอิงใหม่
            if (++fastRejects < REANCHOR_AFTER) return null
            fastRejects = 0
            last = p
            reanchored = true
            return 0.0
        }
        fastRejects = 0
        if (d < max(3.0, p.accuracy / 2.0)) return null
        last = p
        return d
    }

    fun reset(from: TrackPoint?) { last = from; fastRejects = 0 }

    companion object {
        const val REANCHOR_AFTER = 5
    }
}
