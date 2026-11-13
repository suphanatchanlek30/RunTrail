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
import com.example.runtrail.ui.notifications.Notifier
import org.json.JSONObject
import java.io.File

/**
 * Offline-first sync between the local database and the server.
 *
 * Order: claim guest runs → push profile → push deletions → push runs → push photos → pull changes.
 * Only one round runs at a time app-wide. A run is marked synced only if it was not edited during
 * the upload. Network or server failures abort the round so WorkManager can retry.
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
            fetchNotifications()
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

    /** Uploads one run immediately (used before sharing). Returns the latest local copy. */
    fun pushNow(runId: Long): Run? = synchronized(LOCK) {
        val uid = api.client.sessions.get()?.userId ?: throw ApiException(401, "not_logged_in", com.example.runtrail.domain.Lang.t("ยังไม่ได้เข้าสู่ระบบ", "Not signed in"))
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
            Log.w(TAG, "profile rejected: ${e.fields}")   // invalid data: don't retry forever
            prefs.profileDirty = false
        }
    }

    private fun pushDeletes(uid: String) {
        RunRepository.tombstones(uid).forEach { run ->
            try {
                api.deleteRun(run.uuid)
            } catch (e: ApiException) {
                if (e.status != 404) throw e   // 404: already gone on the server
            }
            RunRepository.hardDelete(run.id)
        }
    }

    /** Returns true if uploaded. Retryable errors are rethrown to abort the round. */
    private fun pushRun(run: Run, rethrow: Boolean = false): Boolean {
        // Thin very long tracks to the server limit; the local track stays complete.
        val track = if (run.isGps) SyncRules.thinForUpload(RunRepository.loadTrack(run.id)) else emptyList()
        val resp = try {
            api.putRun(run, track)
        } catch (e: ApiException) {
            when (SyncRules.onPushError(e.status, e.code)) {
                SyncRules.PushFailure.ABORT_ROUND -> throw e
                SyncRules.PushFailure.NEW_UUID -> {
                    // UUID taken by another user: pick a new one and upload next round.
                    RunRepository.update(run.copy(uuid = java.util.UUID.randomUUID().toString()), touch = false)
                    return false
                }
                SyncRules.PushFailure.DROP_LOCAL -> { RunRepository.hardDelete(run.id); return true }
                SyncRules.PushFailure.MARK_FAILED -> {
                    RunRepository.setSyncState(run.id, SyncStatus.FAILED, e.message)
                    if (rethrow) throw e
                    return false
                }
            }
        }
        val merged = RunJson.fromServer(resp, run, run.ownerUserId).copy(
            id = run.id, photoUri = run.photoUri, thumb = run.thumb, pauses = run.pauses,
            photoSync = SyncRules.photoStateAfterPush(run.photoSync, serverHasPhoto = !resp.isNull("photo_url") && resp.optString("photo_url").isNotBlank()),
        )
        if (!RunRepository.updateIfUnchanged(merged, run.updatedAt)) {
            RunRepository.markOnServer(run.id)   // edited during upload: stays pending
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
                    if (SyncRules.photoErrorAbortsRound(e.status)) throw e
                    Log.w(TAG, "photo rejected for ${run.uuid}: ${e.message}")
                }
                RunRepository.setPhotoSync(run.id, PhotoSync.SYNCED)
            }
            PhotoSync.DELETE -> {
                try {
                    api.photoDelete(run.uuid)
                } catch (e: ApiException) {
                    if (SyncRules.photoErrorAbortsRound(e.status)) throw e
                }
                RunRepository.setPhotoSync(run.id, PhotoSync.NONE)
            }
            else -> Unit
        }
    }

    /** Downloads runs changed on the server (everything on a new device). Returns the number written. */
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

    /** Fetches notifications for offline display and posts new ones to the tray. Failures do not affect run sync. */
    fun fetchNotifications() {
        val page = try { api.notifications() } catch (e: ApiException) {
            if (e.status == 401) throw e
            return
        }
        prefs.notificationsCache = page.toString()
        prefs.unreadNotifications = page.optInt("unread_count")
        val items = page.getJSONArray("items")
        val byId = (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.getString("id") }
        val notes = byId.values.map { SyncRules.Note(it.getString("id"), it.optBoolean("read")) }
        val (show, remembered) = SyncRules.notificationsToShow(notes, prefs.notifiedIds, prefs.updatesAlert)
        show.forEach { Notifier.show(context, byId.getValue(it.id)) }
        prefs.notifiedIds = remembered
    }

    private fun applyPulled(o: JSONObject, uid: String): Boolean {
        val uuid = o.getString("id")
        val local = RunRepository.getByUuid(uuid)
        val state = local?.let { SyncRules.Local(it.syncStatus, it.deleted, it.finished, it.updatedAt, it.photoUri, it.photoSync) }
        val serverDeleted = o.optBoolean("deleted")
        val serverVersion = if (serverDeleted) 0L else RunJson.millis(o.getString("client_updated_at"))
        when (SyncRules.onPulled(state, serverDeleted, serverVersion)) {
            SyncRules.PullAction.IGNORE -> return false
            SyncRules.PullAction.DELETE_LOCAL -> { RunRepository.hardDelete(local!!.id); return true }
            SyncRules.PullAction.APPLY -> Unit
        }

        val merged = RunJson.fromServer(o, local, uid).copy(id = local?.id ?: 0)
        val photoUrl = if (o.isNull("photo_url")) null else o.optString("photo_url").ifBlank { null }
        val photoRemovedElsewhere = SyncRules.photoRemovedElsewhere(state, serverHasPhoto = photoUrl != null)
        if (photoRemovedElsewhere) ImageStore.delete(context, local?.photoUri)
        val id = RunRepository.upsertFromServer(merged, clearLocalPhoto = photoRemovedElsewhere)
        if (merged.isGps && !RunRepository.hasTrack(id)) {
            RunRepository.replaceTrack(id, api.track(uuid))
        }
        if (photoUrl != null && merged.photoUri == null) {
            api.client.download(photoUrl)?.let { bytes ->
                ImageStore.saveBytes(context, bytes)?.let { RunRepository.setPhotoPath(id, it) }
            }
        }
        return true
    }

    companion object {
        private const val TAG = "SyncEngine"
        /** Serialises sync rounds from WorkManager and from screens. */
        val LOCK = Any()
    }
}
