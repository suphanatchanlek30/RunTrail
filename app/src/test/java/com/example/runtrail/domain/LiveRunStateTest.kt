package com.example.runtrail.domain

import com.example.runtrail.data.Pause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRunStateTest {

    private val t0 = TestTracks.T0

    /** ป้อนจุดทีละวินาที เหมือน LocationManager + ตัวจับเวลาของ service */
    private fun LiveRunState.feed(points: List<com.example.runtrail.data.TrackPoint>, speed: Float, mono0: Long): Long {
        var mono = mono0
        points.forEach { p ->
            onLocation(p, speed, p.time)
            mono += 1000
            tick(mono, p.time)
        }
        return mono
    }

    @Test fun countsDistanceAndMovingTime() {
        val s = LiveRunState(t0)
        s.tick(0, t0)
        s.feed(TestTracks.north(101, 3.0), 3f, 0)
        assertEquals(300.0, s.distanceM, 1.0)
        assertEquals(101, s.movingS.toInt())
        assertEquals(333.0, s.currentPace(), 5.0)
    }

    @Test fun movingTimeUsesClockNotTicks() {
        val s = LiveRunState(t0)
        s.tick(0, t0)
        s.tick(30_000, t0 + 30_000)   // ตัวจับเวลาช้าไป 30 วินาที (เครื่องหลับ) ก็ยังนับครบ
        assertEquals(30, s.movingS.toInt())
    }

    @Test fun manualPauseIsRecordedAndDistanceSkipped() {
        val s = LiveRunState(t0) { false }
        var mono = s.feed(TestTracks.north(100, 3.0), 3f, 0)
        val before = s.distanceM
        val pauseAt = t0 + 100_000
        s.pause(pauseAt, auto = false)
        // walk 300 m while paused: no distance, no moving time
        val walk = TestTracks.north(120, 2.5, startLat = s.lastFix!!.lat, t0 = pauseAt)
        mono = s.feed(walk, 2.5f, mono)
        assertEquals(before, s.distanceM, 0.001)
        val resumeAt = walk.last().time + 1000
        s.resume(resumeAt)
        assertEquals(listOf(Pause(pauseAt, resumeAt)), s.pauses)
        s.feed(TestTracks.north(50, 3.0, startLat = walk.last().lat, t0 = resumeAt + 1000), 3f, mono)
        assertEquals(before + 147.0, s.distanceM, 5.0)   // the jump across the pause is not counted
    }

    @Test fun autoPauseAndResume() {
        val s = LiveRunState(t0) { true }
        var mono = s.feed(TestTracks.north(20, 3.0), 3f, 0)
        val last = s.lastFix!!
        // standing still for 10 s
        repeat(10) { i ->
            val p = last.copy(time = last.time + (i + 1) * 1000)
            s.onLocation(p, 0f, p.time); mono += 1000; s.tick(mono, p.time)
        }
        assertTrue(s.paused)
        assertTrue(s.autoPaused)
        // start running again → resumes by itself
        val again = TestTracks.north(5, 3.0, startLat = last.lat, t0 = last.time + 12_000)
        s.feed(again, 3f, mono)
        assertFalse(s.paused)
        assertEquals(1, s.pauses.size)
    }

    @Test fun kmAlertFiresOnce() {
        val s = LiveRunState(t0) { false }
        val kms = mutableListOf<Int>()
        TestTracks.north(700, 3.0).forEach { p -> s.onLocation(p, 3f, p.time).takeIf { it > 0 }?.let { kms += it } }
        assertEquals(listOf(1, 2), kms)
        assertEquals(333.0, s.splitPace(1)!!.toDouble(), 2.0)
    }

    @Test fun restoreStartsPaused() {
        val s = LiveRunState(t0)
        val pts = TestTracks.north(50, 3.0)
        s.restore(pts, 147.0, 49, listOf(Pause(t0 + 10_000, t0 + 12_000)), t0 + 60_000)
        assertTrue(s.paused)
        assertEquals(49, s.movingS.toInt())
        assertEquals(2, s.allPauses(t0 + 70_000).size)
    }
}
