package com.example.runtrail.domain

import com.example.runtrail.data.TrackPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Recorded vs true distance under simulated GPS noise, with and without [PositionSmoother].
 *
 * Noise models: "realistic" drifts like a real receiver (AR(1), ρ = 0.95); "white" is independent
 * per second (worst case). Routes: a straight line and a 100 × 60 m block with frequent 90° turns.
 */
class GpsNoiseDistanceTest {

    private val mLat = 111_194.93
    private val mLng = 111_194.93 * cos(Math.toRadians(13.73))

    private fun position(d: Double, square: Boolean): Pair<Double, Double> {
        if (!square) return d to 0.0
        val c = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 60.0, 0.0 to 60.0, 0.0 to 0.0)
        var left = d % 320.0
        for (i in 1 until c.size) {
            val len = hypot(c[i].first - c[i - 1].first, c[i].second - c[i - 1].second)
            if (left <= len) { val f = left / len; return c[i - 1].first + (c[i].first - c[i - 1].first) * f to c[i - 1].second + (c[i].second - c[i - 1].second) * f }
            left -= len
        }
        return 0.0 to 0.0
    }

    /** Returns recorded distance ÷ true distance. */
    fun simulate(speed: Double, sigma: Double, rho: Double, square: Boolean, smoother: PositionSmoother?, seed: Long, seconds: Int = 600): Double {
        val rnd = Random(seed)
        val state = LiveRunState(startTime = 0, smoother = smoother)
        var en = 0.0; var ee = 0.0
        val innov = sigma * sqrt(1 - rho * rho)
        for (i in 0..seconds) {
            en = rho * en + innov * rnd.nextGaussian()
            ee = rho * ee + innov * rnd.nextGaussian()
            val (north, east) = position(i * speed, square)
            val p = TrackPoint(13.73 + (north + en) / mLat, 100.53 + (east + ee) / mLng, i * 1000L, 5.0, (2 * sigma).toFloat())
            state.tick(i * 1000L, i * 1000L)
            state.onLocation(p, (speed + rnd.nextGaussian() * 0.2).toFloat().coerceAtLeast(0f), i * 1000L)
        }
        return state.distanceM / (seconds * speed)
    }

    private fun avg(f: (Long) -> Double) = (1L..5L).map(f).average()

    /** The recording settings stay within ±8% (realistic) and ±12% (worst case); no filter is clearly worse. */
    @Test fun recordingSmootherKeepsDistanceClose() {
        for (square in listOf(false, true)) for (speed in listOf(1.4, 3.0)) for (sigma in listOf(2.0, 4.0, 7.0)) {
            val realistic = avg { simulate(speed, sigma, 0.95, square, PositionSmoother.forRecording(), it) }
            val white = avg { simulate(speed, sigma, 0.0, square, PositionSmoother.forRecording(), it) }
            val tag = "${if (square) "turns" else "straight"} speed $speed σ $sigma"
            assertTrue("$tag realistic $realistic", realistic in 0.92..1.08)
            assertTrue("$tag white $white", white in 0.90..1.12)
        }
        // Baseline: walking with poor GPS and no filter over-counts by more than 20%.
        assertTrue(avg { simulate(1.4, 7.0, 0.95, false, null, it) } > 1.2)
    }

    @Test fun measure() {
        val variants = listOf<Pair<String, () -> PositionSmoother?>>(
            "none" to { null }, "q=3" to { PositionSmoother(3.0) },
            "v×1" to { PositionSmoother(speedFactor = 1.0) }, "v×1.5" to { PositionSmoother(speedFactor = 1.5) },
            "v×2" to { PositionSmoother(speedFactor = 2.0) },
        )
        println("route    noise      speed σ    " + variants.joinToString("  ") { "%7s".format(it.first) })
        for (square in listOf(false, true)) for ((rho, noise) in listOf(0.95 to "realistic", 0.0 to "white")) {
            for (speed in listOf(1.4, 3.0)) for (sigma in listOf(2.0, 4.0, 7.0)) {
                val cells = variants.map { (_, mk) -> "%7.2f".format(avg { seed -> simulate(speed, sigma, rho, square, mk(), seed) }) }
                println("%-8s %-10s %.1f   %.0f    ".format(if (square) "turns" else "straight", noise, speed, sigma) + cells.joinToString("  "))
            }
        }
    }
}
