package com.example.runtrail.domain

import com.example.runtrail.data.TrackPoint
import kotlin.math.PI

/** เส้นทางสำหรับทดสอบ: เดินขึ้นทิศเหนือจากสวนลุมพินีด้วยความเร็วคงที่ */
object TestTracks {
    const val M_PER_DEG_LAT = 6_371_000.0 * PI / 180
    const val T0 = 1_762_772_530_000L

    fun north(n: Int, speed: Double, dtSec: Double = 1.0, startLat: Double = 13.7290, t0: Long = T0): List<TrackPoint> =
        List(n) { i ->
            TrackPoint(startLat + i * speed * dtSec / M_PER_DEG_LAT, 100.5385, t0 + (i * dtSec * 1000).toLong(), 5.0, 5f)
        }
}
