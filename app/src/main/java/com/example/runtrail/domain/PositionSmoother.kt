package com.example.runtrail.domain

import com.example.runtrail.data.TrackPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Kalman filter that smooths GPS positions before distance is measured.
 *
 * Accurate fixes pull the estimate strongly and noisy ones barely move it. Uncertainty grows by
 * [processNoiseMps]² per second, so the estimate keeps up with a runner and recovers after gaps.
 * Works in metres (north/east) around the first fix.
 *
 * Without it, ±4–7 m noise inflates walking distance by 12–30% (see GpsNoiseDistanceTest).
 */
class PositionSmoother(
    private val processNoiseMps: Double = DEFAULT_PROCESS_NOISE,
    /** When > 0, process noise follows GPS speed: q = speed × factor, clamped to [MIN_Q, MAX_Q]. */
    private val speedFactor: Double = 0.0,
) {

    private var lat0 = 0.0
    private var lng0 = 0.0
    private var kLng = 1.0
    private var n = 0.0
    private var e = 0.0
    private var variance = -1.0     // < 0 until the first fix
    private var lastTime = 0L

    fun reset() { variance = -1.0 }

    /** Returns the smoothed point; time, altitude and accuracy are kept. */
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

    /** Current position uncertainty in metres. */
    val uncertaintyM: Double get() = if (variance < 0) Double.NaN else sqrt(variance)

    companion object {
        /** Settings used while recording, tuned in GpsNoiseDistanceTest. */
        fun forRecording() = PositionSmoother(speedFactor = 1.5)

        const val DEFAULT_PROCESS_NOISE = 3.0
        private const val MIN_ACCURACY = 1.0
        const val MIN_Q = 1.5
        const val MAX_Q = 6.0
        private const val METERS_PER_DEG = 111_194.93
    }
}
