package com.example.runtrail.sync

import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.domain.TrackMath
import com.example.runtrail.sync.SyncRules.PullAction
import com.example.runtrail.sync.SyncRules.PushFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** ตรรกะการซิงก์ทุกกรณี บน JVM (ไม่ต้องมี SQLite / เครือข่าย) */
class SyncRulesTest {

    // ---------------------------------------------------------------- push errors

    @Test fun temporaryProblemsAbortTheRoundSoNothingIsLost() {
        for ((status, code) in listOf(0 to "network", 500 to "internal", 503 to "x", 429 to "rate_limited",
            401 to "invalid_token", 409 to "concurrent_upload")) {
            assertEquals("$status $code", PushFailure.ABORT_ROUND, SyncRules.onPushError(status, code))
        }
    }

    @Test fun permanentProblemsAreHandledPerRun() {
        assertEquals(PushFailure.NEW_UUID, SyncRules.onPushError(409, "run_id_taken"))
        assertEquals(PushFailure.DROP_LOCAL, SyncRules.onPushError(410, "run_deleted"))
        assertEquals(PushFailure.MARK_FAILED, SyncRules.onPushError(400, "validation_failed"))
        assertEquals(PushFailure.MARK_FAILED, SyncRules.onPushError(413, "body_too_large"))
    }

    @Test fun photoErrors() {
        assertTrue(SyncRules.photoErrorAbortsRound(0))
        assertTrue(SyncRules.photoErrorAbortsRound(502))
        assertTrue(SyncRules.photoErrorAbortsRound(401))
        assertFalse(SyncRules.photoErrorAbortsRound(400))   // รูปเสีย → ข้าม ไม่ค้างทั้งรอบ
    }

    @Test fun pendingPhotoWorkSurvivesTheServerReply() {
        assertEquals(PhotoSync.PENDING, SyncRules.photoStateAfterPush(PhotoSync.PENDING, serverHasPhoto = false))
        assertEquals(PhotoSync.DELETE, SyncRules.photoStateAfterPush(PhotoSync.DELETE, serverHasPhoto = true))
        assertEquals(PhotoSync.SYNCED, SyncRules.photoStateAfterPush(PhotoSync.NONE, serverHasPhoto = true))
        assertEquals(PhotoSync.NONE, SyncRules.photoStateAfterPush(PhotoSync.SYNCED, serverHasPhoto = false))
    }

    // ---------------------------------------------------------------- long runs

    private fun track(n: Int): List<TrackPoint> =
        List(n) { i -> TrackPoint(13.7 + i * 3.0 / 111_194.93, 100.5, 1_000_000L + i * 1000L, 5.0, 5f) }

    @Test fun shortTracksAreSentUnchanged() {
        val t = track(1000)
        assertSame(t, SyncRules.thinForUpload(t))
        val exactly = track(SyncRules.MAX_UPLOAD_POINTS)
        assertSame(exactly, SyncRules.thinForUpload(exactly))
    }

    @Test fun longTracksFitTheServerLimitAndKeepTheirShape() {
        // 20 ชั่วโมงที่ 1 จุด/วินาที — เดิมได้ 400 ทุกครั้งที่ซิงก์
        for (n in listOf(50_001, 72_000, 100_000, 604_800)) {
            val t = track(n)
            val thin = SyncRules.thinForUpload(t)
            assertEquals("$n → uses the whole budget", SyncRules.MAX_UPLOAD_POINTS, thin.size)
            assertEquals(t.first(), thin.first())
            assertEquals(t.last(), thin.last())
            assertTrue("time order", thin.zipWithNext().all { (a, b) -> b.time > a.time })
            // เส้นตรง → ระยะรวมต้องเท่าเดิม (ต่างไม่เกิน 0.1 %)
            val full = TrackMath.cumulative(t).last()
            assertEquals(full, TrackMath.cumulative(thin).last(), full * 0.001)
        }
    }

    // ---------------------------------------------------------------- pull

    private fun local(status: SyncStatus = SyncStatus.SYNCED, deleted: Boolean = false, finished: Boolean = true,
                      updatedAt: Long = 1000, photo: String? = null, photoSync: PhotoSync = PhotoSync.NONE) =
        SyncRules.Local(status, deleted, finished, updatedAt, photo, photoSync)

    @Test fun newRunFromAnotherDeviceIsApplied() {
        assertEquals(PullAction.APPLY, SyncRules.onPulled(null, serverDeleted = false, serverClientUpdatedAt = 5))
    }

    @Test fun newerServerVersionWins() {
        assertEquals(PullAction.APPLY, SyncRules.onPulled(local(updatedAt = 1000), false, 2000))
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(updatedAt = 2000), false, 2000))   // ฉบับเดียวกัน
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(updatedAt = 3000), false, 2000))
    }

    @Test fun unsentLocalEditsAreNeverOverwritten() {
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(status = SyncStatus.PENDING), false, 9_999))
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(status = SyncStatus.FAILED), false, 9_999))
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(deleted = true), false, 9_999))
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(finished = false), false, 9_999))
    }

    @Test fun deletionFromAnotherDevice() {
        assertEquals(PullAction.DELETE_LOCAL, SyncRules.onPulled(local(), serverDeleted = true, serverClientUpdatedAt = 0))
        // มีการแก้ที่ยังไม่ได้ส่ง → เก็บไว้ (จะได้ 410 แล้วลบตอนส่ง)
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(local(status = SyncStatus.PENDING), true, 0))
        assertEquals(PullAction.IGNORE, SyncRules.onPulled(null, true, 0))
    }

    @Test fun photoRemovedOnAnotherDevice() {
        assertTrue(SyncRules.photoRemovedElsewhere(local(photo = "/p.jpg", photoSync = PhotoSync.SYNCED), serverHasPhoto = false))
        assertFalse(SyncRules.photoRemovedElsewhere(local(photo = "/p.jpg", photoSync = PhotoSync.SYNCED), serverHasPhoto = true))
        // รูปใหม่ที่ยังไม่ได้อัปโหลด → ห้ามลบ
        assertFalse(SyncRules.photoRemovedElsewhere(local(photo = "/p.jpg", photoSync = PhotoSync.PENDING), serverHasPhoto = false))
        assertFalse(SyncRules.photoRemovedElsewhere(local(), serverHasPhoto = false))
        assertFalse(SyncRules.photoRemovedElsewhere(null, serverHasPhoto = false))
    }

    // ---------------------------------------------------------------- notifications

    private fun notes(vararg ids: String, read: Boolean = false) = ids.map { SyncRules.Note(it, read) }

    @Test fun firstFetchAfterLoginShowsNothingOld() {
        val (show, remembered) = SyncRules.notificationsToShow(notes("c", "b", "a"), emptySet(), alertsOn = true)
        assertTrue(show.isEmpty())
        assertTrue(remembered.containsAll(listOf("a", "b", "c", SyncRules.INIT_MARK)))
    }

    @Test fun onlyNewUnreadOnesPopOldestFirst() {
        val seen = setOf("a", SyncRules.INIT_MARK)
        val items = listOf(SyncRules.Note("c", false), SyncRules.Note("b", true), SyncRules.Note("a", false))
        val (show, _) = SyncRules.notificationsToShow(items, seen, alertsOn = true)
        assertEquals(listOf("c"), show.map { it.id })
        assertTrue(SyncRules.notificationsToShow(items, seen, alertsOn = false).first.isEmpty())
    }

    @Test fun trimmingNeverForgetsWhatTheServerStillShows() {
        val old = (1..450).map { "old$it" }.toSet() + SyncRules.INIT_MARK
        val page = notes(*(1..50).map { "p$it" }.toTypedArray())
        val (firstShow, remembered) = SyncRules.notificationsToShow(page, old + page.map { it.id }, alertsOn = true)
        assertTrue(firstShow.isEmpty())
        assertTrue(remembered.size <= 400)
        // ดึงหน้าเดิมอีกรอบ → ต้องไม่เด้งซ้ำ (เดิม take(300) จากชุดไม่มีลำดับอาจลืมแล้วเด้งซ้ำ)
        assertTrue(SyncRules.notificationsToShow(page, remembered, alertsOn = true).first.isEmpty())
    }
}
