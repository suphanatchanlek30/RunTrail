package com.example.runtrail.domain

import com.example.runtrail.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** สูตรในแอปต้องได้ผลเท่ากับ backend (backend/internal/track ใช้ตัวเลขชุดเดียวกัน) */
class TrackMathTest {

    @Test fun haversineMatchesBackend() {
        assertEquals(111_194.93, TrackMath.distance(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(648.3, TrackMath.distance(13.7300, 100.5385, 13.7300, 100.5445), 0.5)
    }

    @Test fun splitsAtSteadyPace() {
        val s = TrackMath.splits(TestTracks.north(834, 3.0))   // 2.5 km at 3 m/s
        assertEquals(3, s.size)
        s.forEach { assertEquals(333.0, it.paceSecPerKm.toDouble(), 1.0) }
    }

    @Test fun elevationIgnoresWobble() {
        val pts = listOf(10.0, 11.0, 10.5, 12.0, 15.0, 14.0, 13.0, 16.0, 15.5).map { TrackPoint(0.0, 0.0, 0, it, 5f) }
        assertEquals(8, TrackMath.elevationGain(pts))
    }

    @Test fun gpsFilterDropsBadFixes() {
        val f = GpsFilter()
        val pts = TestTracks.north(5, 3.0)
        assertEquals(0.0, f.accept(pts[0])!!, 0.0)
        assertNull("poor accuracy", f.accept(pts[1].copy(accuracy = 40f)))
        assertNull("teleport", f.accept(pts[1].copy(lat = pts[1].lat + 0.01)))
        assertNotNull(f.accept(pts[2]))   // 6 m in 2 s is fine
    }

    @Test fun paceOfRun() {
        assertEquals(360.0, RunCalculator.paceSecPerKm(1800, 5000.0), 0.001)
        assertEquals(0.0, RunCalculator.paceSecPerKm(100, 0.0), 0.0)
        assertTrue(RunCalculator.calories(5000.0, 1800, 60) in 300.0..450.0)
    }
}
