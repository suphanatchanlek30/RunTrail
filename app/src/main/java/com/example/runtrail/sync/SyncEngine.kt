package com.example.runtrail.sync

import android.content.Context
import android.util.Log
import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.net.ApiException
import com.example.runtrail.net.RunJson
import com.example.runtrail.net.RunTrailApi
import com.example.runtrail.ui.common.ImageStore
import org.json.JSONObject
import java.io.File

/**
 * ซิงก์ข้อมูลในเครื่อง ↔ server (offline-first)
 *
 * ลำดับ: ผูกการวิ่งของ Guest → ส่งโปรไฟล์ → ส่งคำสั่งลบ → ส่งการวิ่งที่ค้าง → ส่งรูป → ดึงของใหม่ลงเครื่อง
 * - ทำงานทีละรอบทั้งแอป (lock เดียว) ไม่ว่าจะเรียกจาก WorkManager หรือจากปุ่มในหน้าแชร์
 * - ส่งสำเร็จแล้วจะเปลี่ยนเป็น "ซิงก์แล้ว" ก็ต่อเมื่อผู้ใช้ไม่ได้แก้ระหว่างส่ง (เทียบ updated_at)
 * - เน็ตหลุด / server ล่ม → หยุดทั้งรอบแล้วให้ WorkManager ลองใหม่ (ข้อมูลไม่หาย)
 */
class SyncEngine(
    private val context: Context,
    private val api: RunTrailApi,
    private val prefs: PrefsHelper,
) {
    sealed class Outcome {
        data class Done(val pushed: Int, val pulled: Int, val failed: Int) : Outcome()
        object NotLoggedIn : Outcome()
        object SessionExpired : Outcome()
        data class Retry(val reason: String) : Outcome()
    }

    fun sync(): Outcome = synchronized(LOCK) {
        val session = api.client.sessions.get() ?: return Outcome.NotLoggedIn
        val uid = session.userId
        try {
            RunRepository.claimGuestRuns(uid)
            pushProfile()
            pushDeletes(uid)
            var pushed = 0
            var failed = 0
            RunRepository.runsToPush(uid).forEach { if (pushRun(it)) pushed++ else failed++ }
            pushPhotos(uid)
            val pulled = pull(uid)
            prefs.lastSyncAt = System.currentTimeMillis()
            prefs.lastSyncError = null
            RunRepository.notifyChanged()
            Outcome.Done(pushed, pulled, failed)
        } catch (e: ApiException) {
            prefs.lastSyncError = e.message
            when {
                api.client.sessions.get() == null -> Outcome.SessionExpired
                else -> Outcome.Retry(e.message)
            }
        }
    }

    /** ส่งการวิ่งเดียวทันที (ใช้ในหน้าแชร์) · คืนการวิ่งล่าสุดในเครื่อง */
    fun pushNow(runId: Long): Run? = synchronized(LOCK) {
        val uid = api.client.sessions.get()?.userId ?: throw ApiException(401, "not_logged_in", "ยังไม่ได้เข้าสู่ระบบ")
        RunRepository.claimGuestRuns(uid)
        val run = RunRepository.getById(runId, withTrack = false) ?: return null
        if (run.syncStatus != SyncStatus.SYNCED || !run.onServer) {
            if (!pushRun(run, rethrow = true)) return RunRepository.getById(runId, withTrack = false)
        }
        RunRepository.getById(runId, withTrack = false)?.let { if (it.photoSync == PhotoSync.PENDING) pushPhoto(it) }
        RunRepository.getById(runId, withTrack = false)
    }

    private fun pushProfile() {
        if (!prefs.profileDirty) return
        val body = JSONObject().apply {
            prefs.firstNameValue.takeIf { it.isNotBlank() }?.let { put("first_name", it); put("display_name", it) }
            put("last_name", prefs.lastName.ifBlank { JSONObject.NULL })
            put("weight_kg", prefs.weightKg)
            put("height_cm", prefs.heightCm)
            put("birth_year", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - prefs.age)
            put("use_miles", prefs.useMiles)
            put("weekly_goal_km", prefs.weeklyGoalKm)
            put("target_pace_s", prefs.targetPaceSec)
            put("leaderboard_opt_in", prefs.leaderboardOptIn)
            put("country_code", prefs.countryCode)
        }
        try {
            api.patchMe(body)
            prefs.profileDirty = false
        } catch (e: ApiException) {
            if (e.status != 400) throw e
            Log.w(TAG, "profile rejected: ${e.fields}")   // ค่าผิดรูปแบบ: ไม่ลองซ้ำไม่รู้จบ
            prefs.profileDirty = false
        }
    }

    private fun pushDeletes(uid: String) {
        RunRepository.tombstones(uid).forEach { run ->
            try {
                api.deleteRun(run.uuid)
            } catch (e: ApiException) {
                if (e.status != 404) throw e   // 404 = ไม่ใช่ของเราแล้ว ลบในเครื่องได้เลย
            }
            RunRepository.hardDelete(run.id)
        }
    }

    /** คืน true ถ้าส่งสำเร็จ · ข้อผิดพลาดที่ลองใหม่ได้จะโยนต่อให้ยกเลิกทั้งรอบ */
    private fun pushRun(run: Run, rethrow: Boolean = false): Boolean {
        val track = if (run.isGps) RunRepository.loadTrack(run.id) else emptyList()
        val resp = try {
            api.putRun(run, track)
        } catch (e: ApiException) {
            when {
                e.isNetwork || e.isServerError || e.status == 401 || e.code == "concurrent_upload" -> throw e
                e.code == "run_id_taken" -> {
                    // ชน UUID กับคนอื่น (แทบเป็นไปไม่ได้) → เปลี่ยนรหัสแล้วส่งรอบหน้า
                    RunRepository.update(run.copy(uuid = java.util.UUID.randomUUID().toString()), touch = false)
                    return false
                }
                e.code == "run_deleted" -> { RunRepository.hardDelete(run.id); return true }
                else -> {
                    RunRepository.setSyncState(run.id, SyncStatus.FAILED, e.message)
                    if (rethrow) throw e
                    return false
                }
            }
        }
        val merged = RunJson.fromServer(resp, run, run.ownerUserId).copy(
            id = run.id, photoUri = run.photoUri, thumb = run.thumb, pauses = run.pauses,
            photoSync = if (run.photoSync == PhotoSync.PENDING || run.photoSync == PhotoSync.DELETE) run.photoSync
            else RunJson.fromServer(resp, run, null).photoSync,
        )
        if (!RunRepository.updateIfUnchanged(merged, run.updatedAt)) {
            RunRepository.markOnServer(run.id)   // ผู้ใช้แก้ระหว่างส่ง → ยังรอซิงก์อยู่ รอบหน้าจะส่งค่าล่าสุด
        }
        return true
    }

    private fun pushPhotos(uid: String) {
        RunRepository.photosToSync(uid).forEach { pushPhoto(it) }
    }

    private fun pushPhoto(run: Run) {
        when (run.photoSync) {
            PhotoSync.PENDING -> {
                val file = run.photoUri?.let { File(it) }
                if (file == null || !file.exists()) {
                    RunRepository.update(run.copy(photoSync = PhotoSync.NONE), touch = false)
                    return
                }
                try {
                    api.uploadPhoto(run.uuid, file.readBytes())
                } catch (e: ApiException) {
                    if (e.isNetwork || e.isServerError || e.status == 401) throw e
                    Log.w(TAG, "photo rejected for ${run.uuid}: ${e.message}")
                }
                RunRepository.setPhotoSync(run.id, PhotoSync.SYNCED)
            }
            PhotoSync.DELETE -> {
                try {
                    api.photoDelete(run.uuid)
                } catch (e: ApiException) {
                    if (e.isNetwork || e.isServerError || e.status == 401) throw e
                }
                RunRepository.setPhotoSync(run.id, PhotoSync.NONE)
            }
            else -> Unit
        }
    }

    /** ดึงการวิ่งที่เปลี่ยนบน server ลงเครื่อง (เครื่องใหม่ = ดึงทั้งหมด) · คืนจำนวนที่เขียนลงเครื่อง */
    private fun pull(uid: String): Int {
        val since = prefs.lastPull
        var cursor: String? = null
        var serverTime: String? = null
        var count = 0
        do {
            val page = api.listRuns(since, cursor)
            if (serverTime == null) serverTime = page.optString("server_time").ifBlank { null }
            val items = page.getJSONArray("items")
            for (i in 0 until items.length()) {
                if (applyPulled(items.getJSONObject(i), uid)) count++
            }
            cursor = if (page.isNull("next_cursor")) null else page.optString("next_cursor").ifBlank { null }
        } while (cursor != null)
        serverTime?.let { prefs.lastPull = it }
        return count
    }

    private fun applyPulled(o: JSONObject, uid: String): Boolean {
        val uuid = o.getString("id")
        val local = RunRepository.getByUuid(uuid)
        if (o.optBoolean("deleted")) {
            if (local != null && local.syncStatus == SyncStatus.SYNCED) {
                RunRepository.hardDelete(local.id)
                return true
            }
            return false
        }
        // แก้ในเครื่องแล้วยังไม่ได้ส่ง → ฝั่งเครื่องชนะ (จะถูกส่งขึ้นไปรอบหน้า)
        if (local != null && (local.syncStatus != SyncStatus.SYNCED || local.deleted || local.endTime == null)) return false
        val serverVersion = RunJson.millis(o.getString("client_updated_at"))
        if (local != null && local.updatedAt >= serverVersion) return false   // มีอยู่แล้ว (เช่น เพิ่งส่งขึ้นไปเอง)

        val merged = RunJson.fromServer(o, local, uid).copy(id = local?.id ?: 0)
        val id = RunRepository.upsertFromServer(merged)
        if (merged.isGps && !RunRepository.hasTrack(id)) {
            RunRepository.replaceTrack(id, api.track(uuid))
        }
        val photoUrl = if (o.isNull("photo_url")) null else o.optString("photo_url").ifBlank { null }
        if (photoUrl != null && merged.photoUri == null) {
            api.client.download(photoUrl)?.let { bytes ->
                ImageStore.saveBytes(context, bytes)?.let { RunRepository.setPhotoPath(id, it) }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "SyncEngine"
        /** lock เดียวทั้งแอป: WorkManager กับปุ่มซิงก์ในหน้าจอจะไม่ทำงานซ้อนกัน */
        val LOCK = Any()
    }
}
