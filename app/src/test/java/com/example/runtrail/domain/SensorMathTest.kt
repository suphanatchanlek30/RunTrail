package com.example.runtrail.domain

import com.example.runtrail.sensor.CadenceTracker
import com.example.runtrail.sensor.CompassHelper
import org.junit.Assert.assertEquals
import org.junit.Test

/** Sensor-derived values checked against hand-calculated answers. */
class SensorMathTest {

    @Test fun cadenceFromStepCounter() {
        // 170 steps/min: 85 steps in 30 s (the counter starts at 10,000 since boot).
        val t0 = 1_000_000L
        val samples = List(31) { i -> t0 + i * 1000L to 10_000f + i * 85f / 30 }
        assertEquals(170, CadenceTracker.compute(samples, now = t0 + 30_000))
        // No step for more than 5 s → 0 (shown as "--").
        assertEquals(0, CadenceTracker.compute(samples, now = t0 + 36_000))
        // Less than 6 s of data → 0.
        assertEquals(0, CadenceTracker.compute(samples.take(5), now = t0 + 4_000))
        // No sensor or no samples yet → 0.
        assertEquals(0, CadenceTracker.compute(emptyList(), now = t0))
        // Counter reset by a reboot → never negative.
        assertEquals(0, CadenceTracker.compute(listOf(t0 to 5000f, t0 + 20_000 to 10f), now = t0 + 20_000))
    }

    @Test fun caloriesFollowAcsmFormula() {
        // 10 km in 60 min at 70 kg: 166.67 m/min → MET = (0.2 × 166.67 + 3.5) / 3.5 = 10.524 → 736.7 kcal.
        assertEquals(736.7, RunCalculator.calories(10_000.0, 3600, 70), 0.1)
        assertEquals(0.0, RunCalculator.calories(1000.0, 0, 70), 0.0)
        // Standing still for 10 min → MET 1 → 11.7 kcal.
        assertEquals(11.67, RunCalculator.calories(0.0, 600, 70), 0.01)
    }

    @Test fun paceAndDistanceUnits() {
        assertEquals(360.0, RunCalculator.paceSecPerKm(1800, 5000.0), 0.0)
        assertEquals(0.0, RunCalculator.paceSecPerKm(1800, 0.0), 0.0)        // zero distance: no division by zero
        assertEquals("9:39", Fmt.pace(360.0, miles = true))                  // 6:00/km = 9:39/mi
        assertEquals("3.11", Fmt.distance(5000.0, miles = true))
    }

    @Test fun compassHeadingFromAzimuth() {
        assertEquals(0, CompassHelper.headingDegrees(0f))
        assertEquals(90, CompassHelper.headingDegrees((Math.PI / 2).toFloat()))
        assertEquals(270, CompassHelper.headingDegrees((-Math.PI / 2).toFloat()))
        assertEquals(0, CompassHelper.headingDegrees(Math.PI.toFloat() * 2 - 0.001f))
        // Degenerate sensor readings are skipped instead of crashing.
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
