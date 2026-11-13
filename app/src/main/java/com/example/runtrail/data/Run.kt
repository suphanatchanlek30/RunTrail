package com.example.runtrail.data

import java.util.TimeZone
import java.util.UUID

/**
 * One run, stored in the `runs` table.
 *
 * [uuid] is created on the device and used as the server id, so uploads are idempotent.
 * [track] holds every GPS point and is loaded only where needed; [thumb] is a ~120-point preview.
 */
data class Run(
    val id: Long = 0,
    val uuid: String = UUID.randomUUID().toString(),
    val ownerUserId: String? = null,         // null = guest; claimed on sign-in
    val source: RunSource = RunSource.GPS,
    val title: String,
    val note: String? = null,
    val startTime: Long,                      // epoch ms
    val endTime: Long? = null,                // null = still recording (draft)
    val timezone: String? = TimeZone.getDefault().id,
    val distanceM: Double = 0.0,
    val movingTimeS: Long = 0,
    val calories: Double = 0.0,
    val elevationGainM: Int = 0,
    val cadenceSpm: Int = 0,
    val tempC: Int? = null,                   // null = no temperature sensor
    val photoUri: String? = null,             // local file path
    val photoSync: PhotoSync = PhotoSync.NONE,
    val shareCode: String? = null,
    val shareUrl: String? = null,
    val shareHideEnds: Boolean = true,
    val shareShowPhoto: Boolean = true,
    val shareShowKcal: Boolean = false,
    val verified: Boolean? = null,            // null = not yet verified by the server
    val verifyReason: String? = null,
    val serverDistanceM: Double? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncError: String? = null,
    val onServer: Boolean = false,            // uploaded at least once
    val updatedAt: Long = System.currentTimeMillis(), // client_updated_at; last write wins
    val deleted: Boolean = false,             // tombstone waiting to be sent to the server
    val feel: Int = 0,                        // 1–5 stars, 0 = not rated; local only
    val track: List<TrackPoint> = emptyList(),
    val thumb: List<TrackPoint> = emptyList(),
    val pauses: List<Pause> = emptyList(),
    val splits: List<Split> = emptyList(),
) {
    val isGps: Boolean get() = source == RunSource.GPS
    val finished: Boolean get() = endTime != null
}

/** One GPS fix (`run_points` table). */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val time: Long,          // epoch ms
    val altitude: Double,
    val accuracy: Float,
)

/** A paused interval (manual or auto-pause); distance inside it is not counted. */
data class Pause(val start: Long, val end: Long)

enum class RunSource(val api: String) {
    GPS("gps"), MANUAL("manual");

    companion object {
        fun from(api: String?) = entries.firstOrNull { it.api == api } ?: GPS
    }
}

/** Upload state of a run. */
enum class SyncStatus { SYNCED, PENDING, FAILED }

/** Upload state of a run's photo. */
enum class PhotoSync { NONE, PENDING, SYNCED, DELETE }

/** Per-kilometre split (`splits` table). */
data class Split(
    val km: Int,
    val paceSecPerKm: Int,
    val elevationDeltaM: Int,
)

/** Route point normalised to 0..1 for drawing previews and charts. */
data class RoutePoint(
    val x: Float,
    val y: Float,
    val paceSecPerKm: Int,
    val altitudeM: Float,
)

/** A personal best shown on the records screen. */
data class PersonalRecord(
    val title: String,
    val value: String,
    val dateMillis: Long,
    val isNew: Boolean,
)
