package com.example.runtrail

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.net.ApiClient
import com.example.runtrail.net.ApiException
import com.example.runtrail.net.MemorySessionStore
import com.example.runtrail.net.RunTrailApi
import com.example.runtrail.sync.SyncEngine
import com.example.runtrail.ui.common.ImageStore
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Full app ↔ backend cycle; skipped unless a server is given:
 *   ./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080
 *
 * Record offline → sync once → server verifies → photo to S3 → share page works → edits and deletes
 * propagate → a new device restores the history → only GPS runs count on the leaderboard.
 */
@RunWith(AndroidJUnit4::class)
class SyncEndToEndTest {

    /** Android 17: local-network permission is needed to reach the host at 10.0.2.2. */
    @get:org.junit.Rule
    val localNetwork: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var api: RunTrailApi
    private lateinit var prefs: PrefsHelper
    private lateinit var engine: SyncEngine
    private lateinit var uid: String

    @Before fun setUp() {
        val url = InstrumentationRegistry.getArguments().getString("e2eApiUrl").orEmpty()
        assumeTrue("ระบุ -Pe2eApiUrl เพื่อรัน end-to-end test", url.isNotBlank())
        ctx.deleteSharedPreferences(PREFS)
        prefs = PrefsHelper(ctx, PREFS)
        api = RunTrailApi(ApiClient({ url }, MemorySessionStore()))
        engine = SyncEngine(ctx, api, prefs)
        RunRepository.init(ctx, RunDb(ctx, null))
        val email = "e2e${System.currentTimeMillis()}@example.com"
        uid = api.register(email, "password123", "E2E Runner", "TH", "emulator").userId
        RunRepository.currentUser = { uid }
    }

    @After fun tearDown() {
        if (::api.isInitialized && api.client.sessions.get() != null) {
            try { api.deleteMe() } catch (_: ApiException) {}
        }
        ctx.deleteSharedPreferences(PREFS)
    }

    /** ~2.4 km loop of Lumphini Park at 3 m/s, one fix per second, starting two hours ago. */
    private fun lumphini(start: Long): List<TrackPoint> {
        val loop = listOf(13.7280 to 100.5385, 13.7280 to 100.5445, 13.7330 to 100.5445, 13.7330 to 100.5385, 13.7280 to 100.5385)
        val mLat = 6_371_000.0 * PI / 180
        val mLng = mLat * cos(13.73 * PI / 180)
        val out = mutableListOf<TrackPoint>()
        var t = start
        for (i in 1 until loop.size) {
            val (a, b) = loop[i - 1] to loop[i]
            val len = hypot((b.first - a.first) * mLat, (b.second - a.second) * mLng)
            var d = 0.0
            while (d < len) {
                val f = d / len
                out += TrackPoint(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f, t, 5.0, 5f)
                d += 3.0; t += 1000
            }
        }
        return out
    }

    private fun recordGpsRun(): Long {
        val start = System.currentTimeMillis() - 2 * 3_600_000
        val track = lumphini(start)
        var dist = 0.0
        for (i in 1 until track.size) dist += com.example.runtrail.domain.TrackMath.distance(track[i - 1], track[i])
        return RunRepository.insert(
            Run(title = "วิ่งรอบสวนลุมพินี", startTime = start, endTime = track.last().time, distanceM = dist,
                movingTimeS = (track.last().time - start) / 1000, calories = 160.0, track = track),
        )
    }

    @Test fun fullOfflineFirstCycle() {
        // 1) Record offline: one GPS run and one manual run; nothing on the server yet.
        val gpsId = recordGpsRun()
        val manualId = RunRepository.insert(Run(title = "เพิ่มเอง", startTime = System.currentTimeMillis() - 86_400_000,
            endTime = System.currentTimeMillis() - 86_400_000 + 1_800_000, distanceM = 5000.0, movingTimeS = 1800,
            source = RunSource.MANUAL))
        assertEquals(2, RunRepository.pendingCount())

        // 2) Online → sync.
        val first = engine.sync() as SyncEngine.Outcome.Done
        assertEquals(2, first.pushed)
        val gps = RunRepository.getById(gpsId, withTrack = false)!!
        assertEquals(SyncStatus.SYNCED, gps.syncStatus)
        assertEquals("server ตรวจเส้นทางผ่าน: ${gps.verifyReason}", true, gps.verified)
        assertEquals(gps.distanceM, gps.serverDistanceM!!, gps.distanceM * 0.01)
        assertEquals(false, RunRepository.getById(manualId, withTrack = false)!!.verified)
        // Notifications are fetched and cached for offline display.
        val notes = org.json.JSONObject(prefs.notificationsCache!!).getJSONArray("items")
        assertTrue("run_verified notification", (0 until notes.length()).any { notes.getJSONObject(it).getString("type") == "run_verified" })
        assertTrue(prefs.unreadNotifications >= 1)

        // 3) Syncing again uploads nothing and creates no duplicates.
        assertEquals(0, (engine.sync() as SyncEngine.Outcome.Done).pushed)
        assertEquals(2, api.listRuns(null, null).getJSONArray("items").length())

        // 4) Photo uploads to S3 and downloads back.
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00D47A.toInt()) }
        val photo = ImageStore.save(ctx, bmp)
        RunRepository.update(gps.copy(photoUri = photo, photoSync = PhotoSync.PENDING))
        engine.sync()
        assertEquals(PhotoSync.SYNCED, RunRepository.getById(gpsId, withTrack = false)!!.photoSync)
        val serverRun = findOnServer(gps.uuid)!!
        val photoUrl = serverRun.getString("photo_url")
        assertNotNull("download photo from S3", api.client.download(photoUrl))

        // 5) The share page opens and contains no points near home.
        val share = api.share(gps.uuid, hideEnds = true, showPhoto = true, showKcal = false)
        val page = api.client.plain.newCall(Request.Builder().url(share.getString("url")).build()).execute()
            .use { it.code to it.body.string() }
        assertEquals(200, page.first)
        assertTrue(page.second.contains("og:title"))

        // 6) Rename propagates to the server; delete removes it there too.
        RunRepository.update(RunRepository.getById(gpsId, withTrack = false)!!.copy(title = "แก้ชื่อแล้ว"))
        engine.sync()
        assertEquals("แก้ชื่อแล้ว", findOnServer(gps.uuid)!!.getString("title"))
        val manualUuid = RunRepository.getById(manualId, withTrack = false)!!.uuid
        RunRepository.delete(manualId, alsoServer = true)
        engine.sync()
        assertNull("deleted on server too", findOnServer(manualUuid))
        assertTrue("tombstone cleared", RunRepository.tombstones(uid).isEmpty())

        // 7) Leaderboard counts only verified GPS runs.
        val lb = api.leaderboard("distance", "week", "global")
        val me = lb.getJSONObject("me")
        assertEquals(gps.serverDistanceM!!, me.getDouble("value"), 1.0)

        // 8) "New device": wipe local data and sync → history returns with track and photo.
        val trackSize = RunRepository.loadTrack(gpsId).size
        RunRepository.clearAll()
        prefs.lastPull = null
        val restored = engine.sync() as SyncEngine.Outcome.Done
        assertEquals(1, restored.pulled)
        val back = RunRepository.getAll().single()
        assertEquals("แก้ชื่อแล้ว", back.title)
        assertEquals(gps.uuid, back.uuid)
        assertEquals(trackSize, RunRepository.loadTrack(back.id).size)
        assertNotNull("photo downloaded", back.photoUri)
        assertTrue(back.splits.isNotEmpty())
    }

    @Test fun pausedDistanceIsNotCountedByServer() {
        val start = System.currentTimeMillis() - 3 * 3_600_000
        val a = lumphini(start)
        val half = a.size / 2
        // A 2-minute stop, then walk on from the other side (no points during the stop); not counted.
        val after = a.drop(half).map { it.copy(time = it.time + 120_000) }
        val track = a.take(half - 40) + after
        var dist = 0.0
        for (i in 1 until track.size) {
            if (track[i].time - track[i - 1].time > 60_000) continue
            dist += com.example.runtrail.domain.TrackMath.distance(track[i - 1], track[i])
        }
        val id = RunRepository.insert(Run(title = "มีหยุดพัก", startTime = start, endTime = track.last().time,
            distanceM = dist, movingTimeS = (track.last().time - start) / 1000 - 120 - 40,
            pauses = listOf(com.example.runtrail.data.Pause(track[half - 41].time, after.first().time)), track = track))
        engine.sync()
        val r = RunRepository.getById(id, withTrack = false)!!
        assertEquals("paused gap must not fail verification: ${r.verifyReason}", true, r.verified)
        assertEquals(dist, r.serverDistanceM!!, dist * 0.02)
    }

    @Test fun veryLongRunStillSyncs() {
        // 60,000 points ≈ 16.7 h at 1 Hz, above the server's 50,000-point limit.
        val n = 60_000
        val start = System.currentTimeMillis() - n * 1000L - 3_600_000
        val track = List(n) { i -> TrackPoint(13.0 + i * 3.0 / 111_194.93, 100.5, start + i * 1000L, 5.0, 5f) }
        val dist = (n - 1) * 3.0
        val id = RunRepository.insert(Run(title = "อัลตร้า", startTime = start, endTime = track.last().time,
            distanceM = dist, movingTimeS = n.toLong(), track = track))
        val done = engine.sync() as SyncEngine.Outcome.Done
        assertEquals(1, done.pushed)
        val r = RunRepository.getById(id, withTrack = false)!!
        assertEquals("sync error: ${r.syncError}", SyncStatus.SYNCED, r.syncStatus)
        assertEquals("server distance ≈ app distance", dist, r.serverDistanceM!!, dist * 0.01)
        assertEquals(n, RunRepository.loadTrack(id).size)                  // local track keeps every point
        val onServer = api.track(r.uuid)
        assertEquals("server keeps 50,000 points", 50_000, onServer.size)
    }

    @Test fun photoRemovedOnOneDeviceDisappearsOnAnother() {
        // Device A: record with a photo, sync.
        val id = recordGpsRun()
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00D47A.toInt()) }
        RunRepository.update(RunRepository.getById(id, withTrack = false)!!.copy(photoUri = ImageStore.save(ctx, bmp), photoSync = PhotoSync.PENDING))
        engine.sync()
        val uuid = RunRepository.getById(id, withTrack = false)!!.uuid
        assertNotNull(findOnServer(uuid)!!.optString("photo_url").ifBlank { null })

        // Device B: fresh database, pulls the run with its photo.
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { uid }
        prefs.lastPull = null
        engine.sync()
        val onB = RunRepository.getByUuid(uuid)!!
        val fileOnB = onB.photoUri
        assertNotNull("photo downloaded on B", fileOnB)
        assertEquals(PhotoSync.SYNCED, onB.photoSync)

        // Device A removes the photo (simulated through the API).
        api.client.call("PATCH", "/runs/$uuid", org.json.JSONObject()
            .put("note", "ลบรูปแล้ว").put("client_updated_at", com.example.runtrail.net.RunJson.iso(System.currentTimeMillis())))
        api.photoDelete(uuid)

        // Device B syncs → the photo and its local file are removed.
        engine.sync()
        val after = RunRepository.getByUuid(uuid)!!
        assertNull("photo cleared on B", after.photoUri)
        assertEquals(PhotoSync.NONE, after.photoSync)
        assertTrue("local file deleted", !java.io.File(fileOnB!!).exists())
    }

    private fun findOnServer(uuid: String): org.json.JSONObject? {
        val items = api.listRuns(null, null).getJSONArray("items")
        for (i in 0 until items.length()) if (items.getJSONObject(i).getString("id") == uuid) return items.getJSONObject(i)
        return null
    }

    companion object {
        private const val PREFS = "e2e_test_prefs"
    }
}
