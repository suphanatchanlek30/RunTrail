package com.example.runtrail.domain

import com.example.runtrail.data.Run
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    @Test fun weeklyGoalIsAlwaysKilometres() {
        // A goal of 20 is 20 km in both unit modes; the server also stores km.
        assertEquals(20_000.0, Units.goalMeters(20), 0.0)
        assertEquals("20", Units.goalText(20, miles = false))
        assertEquals("12.4", Units.goalText(20, miles = true))
        // 10 km of a 20 km goal is 50% regardless of display unit.
        assertEquals(50, Units.goalPercent(10_000.0, 20))
        assertEquals(0, Units.goalPercent(10_000.0, 0))
    }

    @Test fun axisMarksFollowTheSelectedUnit() {
        // 10 km = 6.21 mi, so the mile axis ends at 7, not 10.
        assertEquals(listOf(0, 2, 4, 6, 7), Units.axisMarks(10_000.0, miles = true))
        assertEquals(listOf(0, 2, 4, 6, 8, 10), Units.axisMarks(10_000.0, miles = false))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), Units.axisMarks(5_000.0, miles = false))
        assertEquals(listOf(0, 1), Units.axisMarks(0.0, miles = false))
        assertEquals(listOf(0, 1), Units.axisMarks(1_000.0, miles = false))
    }

    @Test fun longestRunRecordUsesSelectedUnit() {
        val now = System.currentTimeMillis()
        val runs = listOf(Run(title = "a", startTime = now - 1000, distanceM = 10_000.0, movingTimeS = 3000))
        val longestMi = RunCalculator.records(runs, now, miles = true).last()
        assertEquals("6.2 mi", longestMi.value.replace("ไมล์", "mi"))
        val longestKm = RunCalculator.records(runs, now, miles = false).last()
        assertEquals("10.0 km", longestKm.value.replace("กม.", "km"))
    }
}
