package com.example.runtrail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Pause
import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.RunRepository.Filter
import com.example.runtrail.data.RunRepository.Sort
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ฐานข้อมูลในเครื่อง (SQLite จริงบนเครื่อง แต่เป็นแบบในหน่วยความจำ ไม่แตะข้อมูลของแอป) */
@RunWith(AndroidJUnit4::class)
class RunRepositoryTest {

    private var user: String? = null

    @Before fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        RunRepository.init(ctx, RunDb(ctx, null))
        user = null
        RunRepository.currentUser = { user }
    }

    private fun track(n: Int, start: Long = System.currentTimeMillis() - 3_600_000): List<TrackPoint> =
        List(n) { i -> TrackPoint(13.7290 + i * 3.0 / 111_194.93, 100.5385, start + i * 1000L, 5.0 + (i % 7), 5f) }

    private fun gpsRun(title: String, n: Int, start: Long = System.currentTimeMillis() - 3_600_000): Run {
        val t = track(n, start)
        return Run(title = title, startTime = start, endTime = start + n * 1000L, distanceM = (n - 1) * 3.0,
            movingTimeS = n.toLong(), track = t)
    }

    @Test fun insertStoresTrackSplitsAndThumbnail() {
        val id = RunRepository.insert(gpsRun("ยาว", 1500))
        val summary = RunRepository.getAll().single()
        assertTrue("list has no full track", summary.track.isEmpty())
        assertEquals(120, summary.thumb.size)
        assertEquals(5, summary.splits.size)     // 4.5 km → 5 splits
        val full = RunRepository.getById(id)!!
        assertEquals(1500, full.track.size)
        assertEquals(full.track.first(), summary.thumb.first())
    }

    @Test fun queryFiltersAndSorts() {
        RunRepository.insert(gpsRun("เช้าวันนี้", 100))
        RunRepository.insert(Run(title = "เก่า", note = "สวนรถไฟ", startTime = System.currentTimeMillis() - 40L * 86_400_000,
            endTime = System.currentTimeMillis() - 40L * 86_400_000 + 3_600_000, distanceM = 10_000.0, movingTimeS = 3600,
            source = RunSource.MANUAL))
        assertEquals(listOf("เช้าวันนี้", "เก่า"), RunRepository.query("", Filter.ALL, Sort.NEWEST).map { it.title })
        assertEquals(listOf("เก่า"), RunRepository.query("รถไฟ", Filter.ALL, Sort.NEWEST).map { it.title })
        assertEquals(listOf("เก่า"), RunRepository.query("", Filter.OVER_5K, Sort.NEWEST).map { it.title })
        assertEquals(listOf("เช้าวันนี้"), RunRepository.query("", Filter.THIS_WEEK, Sort.NEWEST).map { it.title })
        assertEquals("เก่า", RunRepository.query("", Filter.ALL, Sort.LONGEST).first().title)
    }

    @Test fun draftLifecycleKeepsEveryPoint() {
        val start = System.currentTimeMillis() - 600_000
        val draft = RunRepository.startDraft(start)
        assertTrue("draft is not listed", RunRepository.getAll().isEmpty())
        val pts = track(300, start)
        RunRepository.saveDraft(draft.copy(distanceM = 300.0, movingTimeS = 150, pauses = listOf(Pause(start + 1, start + 2))),
            pts.take(150), 0)
        RunRepository.saveDraft(draft.copy(distanceM = 897.0, movingTimeS = 300), pts.drop(150), 150)

        val recovered = RunRepository.activeDraft()!!
        assertEquals(300, recovered.track.size)
        assertEquals(897.0, recovered.distanceM, 0.0)

        RunRepository.finishDraft(recovered.copy(title = "กู้คืน"), pts.last().time, recovered.track)
        assertNull(RunRepository.activeDraft())
        val run = RunRepository.getAll().single()
        assertEquals("กู้คืน", run.title)
        assertEquals(SyncStatus.PENDING, run.syncStatus)
        assertEquals(300, RunRepository.loadTrack(run.id).size)
    }

    @Test fun guestRunsAreClaimedAtLogin() {
        RunRepository.insert(gpsRun("ตอนเป็น Guest", 50))
        user = "user-1"
        assertEquals("guest runs stay visible", 1, RunRepository.getAll().size)
        assertTrue(RunRepository.runsToPush("user-1").isEmpty())
        assertEquals(1, RunRepository.claimGuestRuns("user-1"))
        assertEquals(1, RunRepository.runsToPush("user-1").size)
        user = "user-2"
        assertTrue("another account does not see it", RunRepository.getAll().isEmpty())
    }

    @Test fun deleteKeepsTombstoneOnlyForSyncedRuns() {
        user = "u"
        val local = RunRepository.insert(gpsRun("ยังไม่ซิงก์", 20))
        RunRepository.delete(local)
        assertNull(RunRepository.getById(local))

        val synced = RunRepository.insert(gpsRun("ซิงก์แล้ว", 20).copy(onServer = true, syncStatus = SyncStatus.SYNCED))
        RunRepository.delete(synced, alsoServer = true)
        assertTrue(RunRepository.getAll().isEmpty())
        assertEquals(1, RunRepository.tombstones("u").size)

        val keepOnServer = RunRepository.insert(gpsRun("เก็บบน server", 20).copy(onServer = true))
        RunRepository.delete(keepOnServer, alsoServer = false)
        assertNull(RunRepository.getById(keepOnServer))
    }

    @Test fun editMarksPendingAndOptimisticUpdate() {
        val id = RunRepository.insert(gpsRun("เดิม", 20).copy(syncStatus = SyncStatus.SYNCED))
        val before = RunRepository.getById(id, withTrack = false)!!
        RunRepository.update(before.copy(title = "ใหม่"))
        val edited = RunRepository.getById(id, withTrack = false)!!
        assertEquals(SyncStatus.PENDING, edited.syncStatus)
        assertTrue(edited.updatedAt > before.updatedAt)
        // ตัวซิงก์ถือค่าเก่าอยู่ → เขียนทับไม่ได้ (ผู้ใช้แก้ระหว่างส่ง)
        assertFalse(RunRepository.updateIfUnchanged(before.copy(syncStatus = SyncStatus.SYNCED), before.updatedAt))
        assertTrue(RunRepository.updateIfUnchanged(edited.copy(syncStatus = SyncStatus.SYNCED), edited.updatedAt))
    }

    @Test fun photoStateAndServerUpsert() {
        user = "u"
        val id = RunRepository.insert(gpsRun("รูป", 20).copy(photoUri = "/x.jpg", photoSync = PhotoSync.PENDING))
        assertTrue("not on server yet", RunRepository.photosToSync("u").isEmpty())
        RunRepository.markOnServer(id)
        assertEquals(1, RunRepository.photosToSync("u").size)

        val pulled = Run(uuid = "server-only", title = "จากเครื่องอื่น", startTime = 1, endTime = 2, ownerUserId = "u",
            syncStatus = SyncStatus.SYNCED, onServer = true)
        val newId = RunRepository.upsertFromServer(pulled)
        assertNotNull(RunRepository.getByUuid("server-only"))
        assertEquals(newId, RunRepository.upsertFromServer(pulled.copy(title = "แก้จาก server")))
        assertEquals("แก้จาก server", RunRepository.getById(newId)!!.title)
    }
}
