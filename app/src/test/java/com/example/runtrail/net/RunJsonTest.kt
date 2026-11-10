package com.example.runtrail.net

import com.example.runtrail.data.Pause
import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.domain.TestTracks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunJsonTest {

    private val t0 = TestTracks.T0

    @Test fun gpsRunBody() {
        val track = TestTracks.north(3, 3.0)
        val run = Run(uuid = "u-1", title = "วิ่งเช้า", startTime = t0, endTime = t0 + 2000, distanceM = 6.0,
            movingTimeS = 2, tempC = null, pauses = listOf(Pause(t0 + 500, t0 + 900)), updatedAt = t0 + 5000)
        val o = RunJson.toPutBody(run, track)
        assertEquals("gps", o.getString("source"))
        assertEquals("2025-11-10T11:02:10Z", o.getString("started_at"))
        assertTrue(o.isNull("temp_c"))
        assertEquals(3, o.getJSONArray("points").length())
        val p = o.getJSONArray("points").getJSONArray(1)
        assertEquals(track[1].lat, p.getDouble(0), 1e-12)
        assertEquals(track[1].time, p.getLong(2))
        assertEquals(t0 + 500, o.getJSONArray("pauses").getJSONArray(0).getLong(0))
        assertEquals(RunJson.iso(t0 + 5000), o.getString("client_updated_at"))
    }

    @Test fun manualRunHasNoPoints() {
        val run = Run(title = "เพิ่มเอง", startTime = t0, source = RunSource.MANUAL, distanceM = 5000.0, movingTimeS = 1800)
        val o = RunJson.toPutBody(run, emptyList())
        assertEquals("manual", o.getString("source"))
        assertFalse(o.has("points"))
    }

    @Test fun fromServerAppliesVerificationAndShare() {
        val o = JSONObject(
            """{"id":"u-1","title":"วิ่งเย็น","note":null,"source":"gps","started_at":"2026-11-10T11:02:10Z",
               "ended_at":"2026-11-10T11:34:00Z","timezone":"Asia/Bangkok","distance_m":5020.4,"server_distance_m":5011.8,
               "moving_time_s":1868,"calories":342,"elevation_gain_m":12,"cadence_spm":168,"temp_c":null,
               "verified":false,"verify_reason":"too_fast","photo_url":"http://s3/x.jpg",
               "share":{"code":"ABCD2345","url":"http://h/s/ABCD2345","hide_ends":true,"show_photo":false,"show_kcal":true},
               "client_updated_at":"2026-11-10T11:34:05.123Z","splits":[{"km":1,"pace_s":371,"elev_delta_m":3}]}""",
        )
        val local = Run(id = 7, uuid = "u-1", title = "x", startTime = 0, photoUri = "/local.jpg")
        val r = RunJson.fromServer(o, local, "user-1")
        assertEquals(7, r.id)
        assertEquals("user-1", r.ownerUserId)
        assertEquals("วิ่งเย็น", r.title)
        assertNull(r.note)
        assertNull(r.tempC)
        assertEquals(false, r.verified)
        assertEquals("เพซเร็วผิดปกติ", RunJson.verifyReasonText(r.verifyReason))
        assertEquals(5011.8, r.serverDistanceM!!, 0.0)
        assertEquals("http://h/s/ABCD2345", r.shareUrl)
        assertFalse(r.shareShowPhoto)
        assertEquals(PhotoSync.SYNCED, r.photoSync)
        assertEquals(SyncStatus.SYNCED, r.syncStatus)
        assertTrue(r.onServer)
        assertEquals(RunJson.millis("2026-11-10T11:34:05.123Z"), r.updatedAt)
        assertEquals(371, r.splits.single().paceSecPerKm)
        assertEquals("/local.jpg", r.photoUri)
    }

    @Test fun isoRoundTripKeepsMillis() {
        assertEquals(t0 + 123, RunJson.millis(RunJson.iso(t0 + 123)))
    }
}
