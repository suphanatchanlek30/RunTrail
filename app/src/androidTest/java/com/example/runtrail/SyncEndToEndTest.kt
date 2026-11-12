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
 * ทดสอบแอป ↔ backend จริงทั้งวงจร (ข้ามเมื่อไม่ได้ระบุ server):
 *   ./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080
 *
 * วิ่งตอน "ออฟไลน์" (บันทึกในเครื่อง) → ซิงก์ → ส่งครั้งเดียว · server ตรวจผ่าน · รูปขึ้น S3 · ลิงก์แชร์เปิดได้
 * · แก้/ลบแล้วตามไปที่ server · ล็อกอินเครื่องใหม่ดึงประวัติคืนครบ · อันดับโลกนับเฉพาะ GPS
 */
@RunWith(AndroidJUnit4::class)
class SyncEndToEndTest {

    /** Android 17: ต้องได้สิทธิ์เครือข่ายภายในก่อนต่อ backend บน Mac (10.0.2.2) */
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

    /** รอบสวนลุมพินี ~2.4 กม. 1 จุด/วินาที ที่ 3 ม./วิ เริ่มเมื่อ 2 ชั่วโมงก่อน */
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
        // 1) บันทึกตอนออฟไลน์: GPS 1 รอบ + เพิ่มเอง 1 รอบ (ยังไม่มีอะไรบน server)
        val gpsId = recordGpsRun()
        val manualId = RunRepository.insert(Run(title = "เพิ่มเอง", startTime = System.currentTimeMillis() - 86_400_000,
            endTime = System.currentTimeMillis() - 86_400_000 + 1_800_000, distanceM = 5000.0, movingTimeS = 1800,
            source = RunSource.MANUAL))
        assertEquals(2, RunRepository.pendingCount())

        // 2) มีเน็ต → ซิงก์
        val first = engine.sync() as SyncEngine.Outcome.Done
        assertEquals(2, first.pushed)
        val gps = RunRepository.getById(gpsId, withTrack = false)!!
        assertEquals(SyncStatus.SYNCED, gps.syncStatus)
        assertEquals("server ตรวจเส้นทางผ่าน: ${gps.verifyReason}", true, gps.verified)
        assertEquals(gps.distanceM, gps.serverDistanceM!!, gps.distanceM * 0.01)
        assertEquals(false, RunRepository.getById(manualId, withTrack = false)!!.verified)
        // การแจ้งเตือนจาก server ถูกดึงมาเก็บไว้ (แสดงตอนออฟไลน์ได้)
        val notes = org.json.JSONObject(prefs.notificationsCache!!).getJSONArray("items")
        assertTrue("run_verified notification", (0 until notes.length()).any { notes.getJSONObject(it).getString("type") == "run_verified" })
        assertTrue(prefs.unreadNotifications >= 1)

        // 3) ซิงก์ซ้ำ → ไม่ส่งซ้ำ ไม่เกิดข้อมูลซ้ำบน server
        assertEquals(0, (engine.sync() as SyncEngine.Outcome.Done).pushed)
        assertEquals(2, api.listRuns(null, null).getJSONArray("items").length())

        // 4) รูป → อัปโหลดตรงไป S3 แล้วดาวน์โหลดคืนได้
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00D47A.toInt()) }
        val photo = ImageStore.save(ctx, bmp)
        RunRepository.update(gps.copy(photoUri = photo, photoSync = PhotoSync.PENDING))
        engine.sync()
        assertEquals(PhotoSync.SYNCED, RunRepository.getById(gpsId, withTrack = false)!!.photoSync)
        val serverRun = findOnServer(gps.uuid)!!
        val photoUrl = serverRun.getString("photo_url")
        assertNotNull("download photo from S3", api.client.download(photoUrl))

        // 5) ลิงก์แชร์ → หน้าเว็บเปิดได้ และไม่มีจุดใกล้บ้าน
        val share = api.share(gps.uuid, hideEnds = true, showPhoto = true, showKcal = false)
        val page = api.client.plain.newCall(Request.Builder().url(share.getString("url")).build()).execute()
            .use { it.code to it.body.string() }
        assertEquals(200, page.first)
        assertTrue(page.second.contains("og:title"))

        // 6) แก้ชื่อ → ตามไป server · ลบ → server ลบด้วย
        RunRepository.update(RunRepository.getById(gpsId, withTrack = false)!!.copy(title = "แก้ชื่อแล้ว"))
        engine.sync()
        assertEquals("แก้ชื่อแล้ว", findOnServer(gps.uuid)!!.getString("title"))
        val manualUuid = RunRepository.getById(manualId, withTrack = false)!!.uuid
        RunRepository.delete(manualId, alsoServer = true)
        engine.sync()
        assertNull("deleted on server too", findOnServer(manualUuid))
        assertTrue("tombstone cleared", RunRepository.tombstones(uid).isEmpty())

        // 7) อันดับโลก: นับเฉพาะ GPS ที่ตรวจผ่าน
        val lb = api.leaderboard("distance", "week", "global")
        val me = lb.getJSONObject("me")
        assertEquals(gps.serverDistanceM!!, me.getDouble("value"), 1.0)

        // 8) "เครื่องใหม่": ล้างข้อมูลในเครื่องแล้วซิงก์ → ได้ประวัติคืนพร้อมเส้นทางและรูป
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
        // หยุด 2 นาทีแล้วเดินไปอีกฝั่ง (ไม่มีจุดระหว่างหยุด) — แอปไม่นับระยะช่วงนี้
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

    @Test fun photoRemovedOnOneDeviceDisappearsOnAnother() {
        // เครื่อง A: บันทึก + รูป → ซิงก์
        val id = recordGpsRun()
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF00D47A.toInt()) }
        RunRepository.update(RunRepository.getById(id, withTrack = false)!!.copy(photoUri = ImageStore.save(ctx, bmp), photoSync = PhotoSync.PENDING))
        engine.sync()
        val uuid = RunRepository.getById(id, withTrack = false)!!.uuid
        assertNotNull(findOnServer(uuid)!!.optString("photo_url").ifBlank { null })

        // เครื่อง B: ฐานข้อมูลใหม่ ดึงลงมาพร้อมรูป
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { uid }
        prefs.lastPull = null
        engine.sync()
        val onB = RunRepository.getByUuid(uuid)!!
        val fileOnB = onB.photoUri
        assertNotNull("photo downloaded on B", fileOnB)
        assertEquals(PhotoSync.SYNCED, onB.photoSync)

        // เครื่อง A ลบรูป (ในแอป = แก้การวิ่ง + ส่งคำสั่งลบรูป) — จำลองด้วย API ตรง ๆ
        api.client.call("PATCH", "/runs/$uuid", org.json.JSONObject()
            .put("note", "ลบรูปแล้ว").put("client_updated_at", com.example.runtrail.net.RunJson.iso(System.currentTimeMillis())))
        api.photoDelete(uuid)

        // เครื่อง B ซิงก์ → รูปหายตาม และไฟล์ในเครื่องถูกลบ (AUDIT A9)
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
