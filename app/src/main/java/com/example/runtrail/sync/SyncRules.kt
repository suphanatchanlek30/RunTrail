package com.example.runtrail.sync

import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint

/** Pure decision logic for [SyncEngine], kept free of I/O so it can be unit tested. */
object SyncRules {

    /** Maximum points the server accepts per run. */
    const val MAX_UPLOAD_POINTS = 50_000

    // ---------------------------------------------------------------- Push

    enum class PushFailure {
        /** Temporary network/server problem or unusable token: stop the round and let WorkManager retry. */
        ABORT_ROUND,
        /** UUID collides with another user's run: assign a new one and upload next round. */
        NEW_UUID,
        /** The run was deleted on another device: delete it locally too. */
        DROP_LOCAL,
        /** Invalid data: mark as failed with the server message. */
        MARK_FAILED,
    }

    fun onPushError(status: Int, code: String): PushFailure = when {
        status == 0 || status >= 500 || status == 429 || status == 401 || code == "concurrent_upload" -> PushFailure.ABORT_ROUND
        code == "run_id_taken" -> PushFailure.NEW_UUID
        code == "run_deleted" -> PushFailure.DROP_LOCAL
        else -> PushFailure.MARK_FAILED
    }

    /** Photo errors that abort the round; others (e.g. a broken file) are skipped. */
    fun photoErrorAbortsRound(status: Int): Boolean = status == 0 || status >= 500 || status == 429 || status == 401

    /** Evenly thins a track to at most [max] points, keeping the first and last, so the server accepts it. */
    fun thinForUpload(points: List<TrackPoint>, max: Int = MAX_UPLOAD_POINTS): List<TrackPoint> {
        if (points.size <= max) return points
        // Fractional step so exactly [max] points are kept.
        val step = (points.size - 1).toDouble() / (max - 1)
        return List(max) { i -> points[Math.round(i * step).toInt().coerceAtMost(points.lastIndex)] }
    }

    /** Local photo state after a successful upload; pending photo work must survive the server response. */
    fun photoStateAfterPush(local: PhotoSync, serverHasPhoto: Boolean): PhotoSync = when (local) {
        PhotoSync.PENDING, PhotoSync.DELETE -> local
        else -> if (serverHasPhoto) PhotoSync.SYNCED else PhotoSync.NONE
    }

    // ---------------------------------------------------------------- Pull

    /** Local state of the same run; null when it is not on the device. */
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
        // Deleted elsewhere: delete locally only if there are no unsent edits.
        serverDeleted -> if (local != null && local.syncStatus == SyncStatus.SYNCED) PullAction.DELETE_LOCAL else PullAction.IGNORE
        local == null -> PullAction.APPLY
        // Unsent local edits, pending deletion or still recording: the local copy wins and is pushed later.
        local.syncStatus != SyncStatus.SYNCED || local.deleted || !local.finished -> PullAction.IGNORE
        // Already have this version or newer.
        local.updatedAt >= serverClientUpdatedAt -> PullAction.IGNORE
        else -> PullAction.APPLY
    }

    /** True when this device has a synced photo that the server no longer has. */
    fun photoRemovedElsewhere(local: Local?, serverHasPhoto: Boolean): Boolean =
        !serverHasPhoto && local?.photoPath != null && local.photoSync == PhotoSync.SYNCED

    // ---------------------------------------------------------------- Notifications

    data class Note(val id: String, val read: Boolean)

    /**
     * Notifications to post to the tray, oldest first, and the updated set of shown ids.
     * Nothing is posted on the first fetch after sign-in, for read items, or when alerts are off.
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
        // Bound the set by keeping only ids still on the latest page; older ones never come back.
        val trimmed = if (remembered.size > 400) items.map { it.id }.toSet() + INIT_MARK else remembered
        return show to trimmed
    }

    const val INIT_MARK = "_init"
}
