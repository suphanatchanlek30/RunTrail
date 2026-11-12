package com.example.runtrail.sync

import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint

/**
 * การตัดสินใจทั้งหมดของ [SyncEngine] แยกเป็นฟังก์ชันล้วน (ไม่แตะ SQLite / เครือข่าย / Context)
 * → ทดสอบบน JVM ได้ครบทุกกรณี (SyncRulesTest) ส่วน SyncEngine เหลือแค่ทำตามผลที่ได้
 */
object SyncRules {

    /** จำนวนจุดสูงสุดที่ server รับต่อการวิ่ง (backend: api.MaxPoints) */
    const val MAX_UPLOAD_POINTS = 50_000

    // ---------------------------------------------------------------- ส่งการวิ่งขึ้น

    enum class PushFailure {
        /** เน็ต/เซิร์ฟเวอร์มีปัญหาชั่วคราว หรือ token ใช้ไม่ได้ → หยุดทั้งรอบ ให้ WorkManager ลองใหม่ (ข้อมูลไม่หาย) */
        ABORT_ROUND,
        /** UUID ชนกับการวิ่งของคนอื่น → สุ่มรหัสใหม่แล้วส่งรอบหน้า */
        NEW_UUID,
        /** server บอกว่าการวิ่งนี้ถูกลบแล้ว (จากอีกเครื่อง) → ลบในเครื่องตาม */
        DROP_LOCAL,
        /** ข้อมูลผิดรูปแบบ → ทำเครื่องหมาย "ซิงก์ไม่สำเร็จ" พร้อมข้อความ (จะลองอีกเมื่อมีการซิงก์ครั้งถัดไป) */
        MARK_FAILED,
    }

    fun onPushError(status: Int, code: String): PushFailure = when {
        status == 0 || status >= 500 || status == 429 || status == 401 || code == "concurrent_upload" -> PushFailure.ABORT_ROUND
        code == "run_id_taken" -> PushFailure.NEW_UUID
        code == "run_deleted" -> PushFailure.DROP_LOCAL
        else -> PushFailure.MARK_FAILED
    }

    /** ข้อผิดพลาดตอนส่ง/ลบรูปที่ต้องหยุดทั้งรอบ (ที่เหลือ เช่น รูปเสีย ข้ามไปได้) */
    fun photoErrorAbortsRound(status: Int): Boolean = status == 0 || status >= 500 || status == 429 || status == 401

    /**
     * ลดจำนวนจุดให้ไม่เกินที่ server รับ (วิ่งเกิน ~13.9 ชม. ที่ 1 จุด/วินาที)
     * เก็บจุดแรกและจุดสุดท้ายเสมอ เว้นช่วงเท่า ๆ กัน → ระยะที่ server คำนวณใหม่ต่างจากเดิมเพียงเล็กน้อย
     * เดิมส่งเกินแล้วได้ 400 ทุกครั้ง การวิ่งนั้นซิงก์ไม่ได้เลย
     */
    fun thinForUpload(points: List<TrackPoint>, max: Int = MAX_UPLOAD_POINTS): List<TrackPoint> {
        if (points.size <= max) return points
        // ระยะห่างแบบทศนิยม → ได้ครบ max จุดพอดี (ระยะห่างจำนวนเต็มจะเหลือแค่ครึ่งเดียวเมื่อเกินมาไม่มาก)
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { i -> points[Math.round(i * step).toInt().coerceAtMost(points.lastIndex)] }
    }

    /** หลังส่งสำเร็จ: สถานะรูปที่เก็บในเครื่อง — งานรูปที่ค้าง (ส่ง/ลบ) ต้องไม่หายเพราะคำตอบของ server */
    fun photoStateAfterPush(local: PhotoSync, serverHasPhoto: Boolean): PhotoSync = when (local) {
        PhotoSync.PENDING, PhotoSync.DELETE -> local
        else -> if (serverHasPhoto) PhotoSync.SYNCED else PhotoSync.NONE
    }

    // ---------------------------------------------------------------- ดึงการวิ่งลงเครื่อง

    /** สถานะของการวิ่งเดียวกันในเครื่อง (null = ยังไม่มี) */
    data class Local(
        val syncStatus: SyncStatus,
        val deleted: Boolean,
        val finished: Boolean,
        val updatedAt: Long,
        val photoPath: String?,
        val photoSync: PhotoSync,
    )

    enum class PullAction { IGNORE, DELETE_LOCAL, APPLY }

    fun onPulled(local: Local?, serverDeleted: Boolean, serverClientUpdatedAt: Long): PullAction = when {
        // ลบจากอีกเครื่อง: ลบในเครื่องเฉพาะเมื่อไม่มีการแก้ที่ยังไม่ได้ส่ง
        serverDeleted -> if (local != null && local.syncStatus == SyncStatus.SYNCED) PullAction.DELETE_LOCAL else PullAction.IGNORE
        local == null -> PullAction.APPLY
        // แก้ในเครื่องแล้วยังไม่ได้ส่ง / รอลบ / ยังวิ่งไม่จบ → ฝั่งเครื่องชนะ จะถูกส่งขึ้นไปรอบหน้า
        local.syncStatus != SyncStatus.SYNCED || local.deleted || !local.finished -> PullAction.IGNORE
        // มีฉบับเดียวกันหรือใหม่กว่าอยู่แล้ว (เช่น เพิ่งส่งขึ้นไปเอง)
        local.updatedAt >= serverClientUpdatedAt -> PullAction.IGNORE
        else -> PullAction.APPLY
    }

    /** เครื่องอื่นลบรูปไปแล้ว: เครื่องนี้มีรูปที่เคยซิงก์ แต่ server ไม่มีรูปแล้ว */
    fun photoRemovedElsewhere(local: Local?, serverHasPhoto: Boolean): Boolean =
        !serverHasPhoto && local?.photoPath != null && local.photoSync == PhotoSync.SYNCED

    // ---------------------------------------------------------------- การแจ้งเตือน

    data class Note(val id: String, val read: Boolean)

    /**
     * การแจ้งเตือนที่ควรเด้งในแถบแจ้งเตือนของเครื่อง (items เรียงใหม่ → เก่าแบบที่ server ส่ง)
     * - ครั้งแรกหลังล็อกอิน (ยังไม่เคยจำอะไรไว้) ไม่เด้งของเก่าทั้งหมด
     * - ไม่เด้งซ้ำ ไม่เด้งรายการที่อ่านแล้ว และไม่เด้งเมื่อผู้ใช้ปิดการแจ้งเตือนข่าวสาร
     * คืน (รายการที่ต้องเด้ง เรียงเก่า → ใหม่, ชุด id ที่จำไว้ชุดใหม่)
     */
    fun notificationsToShow(items: List<Note>, shown: Set<String>, alertsOn: Boolean): Pair<List<Note>, Set<String>> {
        val remembered = shown.toMutableSet()
        val firstFetch = remembered.isEmpty()
        val show = mutableListOf<Note>()
        for (n in items.asReversed()) {
            if (n.id in remembered) continue
            remembered += n.id
            if (!firstFetch && !n.read && alertsOn) show += n
        }
        remembered += INIT_MARK
        // ตัดให้เล็กลงโดยเก็บ id ของหน้าปัจจุบันไว้เสมอ (เดิม take(300) จากชุดที่ไม่มีลำดับ อาจลืม id ที่ยังอยู่บน server
        // แล้วเด้งซ้ำ) — รายการที่หลุดจากหน้าล่าสุดไปแล้วจะไม่กลับมาอีก จึงลืมได้
        val trimmed = if (remembered.size > 400) items.map { it.id }.toSet() + INIT_MARK else remembered
        return show to trimmed
    }

    const val INIT_MARK = "_init"
}
