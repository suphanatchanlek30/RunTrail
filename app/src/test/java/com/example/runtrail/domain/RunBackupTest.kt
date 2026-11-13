package com.example.runtrail.domain

import com.example.runtrail.data.Pause
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunBackup
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RunBackupTest {

    private val gps = Run(
        uuid = "11111111-1111-1111-1111-111111111111", title = "เช้าวันเสาร์", note = "สวนลุม",
        startTime = 1_700_000_000_000, endTime = 1_700_001_800_000, timezone = "Asia/Bangkok",
        distanceM = 5012.5, movingTimeS = 1700, calories = 320.0, elevationGainM = 12, cadenceSpm = 168, tempC = 29,
        syncStatus = SyncStatus.SYNCED, onServer = true, updatedAt = 1_700_002_000_000, feel = 4,
        track = listOf(
            TrackPoint(13.73, 100.54, 1_700_000_000_000, 5.0, 4f),
            TrackPoint(13.731, 100.541, 1_700_000_005_000, 5.5, 3.5f),
        ),
        pauses = listOf(Pause(1_700_000_600_000, 1_700_000_700_000)),
    )
    private val manual = Run(
        uuid = "22222222-2222-2222-2222-222222222222", source = RunSource.MANUAL, title = "ลู่วิ่ง",
        startTime = 1_700_100_000_000, endTime = 1_700_101_000_000, distanceM = 3000.0, movingTimeS = 1000,
    )

    @Test fun roundTripKeepsRunAndTrack() {
        val back = RunBackup.parse(RunBackup.toJson(listOf(gps, manual)).toString())
        assertEquals(2, back.size)
        val g = back[0]
        assertEquals(gps.uuid, g.uuid)
        assertEquals(gps.title, g.title)
        assertEquals(gps.note, g.note)
        assertEquals(gps.startTime, g.startTime)
        assertEquals(gps.endTime, g.endTime)
        assertEquals(gps.distanceM, g.distanceM, 0.001)
        assertEquals(gps.movingTimeS, g.movingTimeS)
        assertEquals(gps.tempC, g.tempC)
        assertEquals(gps.track, g.track)
        assertEquals(gps.pauses, g.pauses)
        assertEquals(gps.updatedAt, g.updatedAt)
        assertEquals(4, g.feel)
        // Imported runs are pending and unowned until inserted.
        assertEquals(SyncStatus.PENDING, g.syncStatus)
        assertFalse(g.onServer)
        assertNull(g.ownerUserId)

        val m = back[1]
        assertEquals(RunSource.MANUAL, m.source)
        assertEquals(emptyList<TrackPoint>(), m.track)
        assertNull(m.tempC)
    }

    @Test fun skipsDraftsAndDeleted() {
        val draft = gps.copy(uuid = "d", endTime = null)
        val gone = gps.copy(uuid = "x", deleted = true)
        assertEquals(listOf(gps.uuid), RunBackup.parse(RunBackup.toJson(listOf(gps, draft, gone)).toString()).map { it.uuid })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherJson() {
        RunBackup.parse("""{"hello":"world"}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJson() {
        RunBackup.parse("date,distance\n2026-11-12,5.0")
    }
}
