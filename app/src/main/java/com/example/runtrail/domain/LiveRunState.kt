package com.example.runtrail.domain

import com.example.runtrail.data.Pause
import com.example.runtrail.data.TrackPoint
import kotlin.math.floor

/**
 * State of a run being recorded.
 *
 * Moving time comes from a monotonic clock, so it stays correct when the device is slow or the
 * screen is off. Pauses use wall-clock time like GPS points, so the server can cut the same intervals.
 */
class LiveRunState(
    val startTime: Long,
    private val smoother: PositionSmoother? = null,
    private val autoPauseEnabled: () -> Boolean = { true },
) {
    val track = mutableListOf<TrackPoint>()
    val pauses = mutableListOf<Pause>()
    var distanceM = 0.0
        private set
    var movingMs = 0L
        private set
    var paused = false
        private set
    var autoPaused = false
        private set
    var lastFix: TrackPoint? = null
        private set

    private val filter = GpsFilter()
    private var lastMono: Long? = null
    private var pauseStartWall = 0L
    private var lastMoveAt = startTime
    private var lastKm = 0

    val movingS: Long get() = movingMs / 1000

    /** Restores a saved draft after the app was killed; the run resumes in the paused state. */
    fun restore(points: List<TrackPoint>, distance: Double, movingSeconds: Long, savedPauses: List<Pause>, wallNow: Long) {
        track.clear(); track += points
        pauses.clear(); pauses += savedPauses
        distanceM = distance
        movingMs = movingSeconds * 1000
        lastKm = floor(distance / 1000).toInt()
        filter.reset(points.lastOrNull())
        smoother?.reset()
        lastFix = points.lastOrNull()
        paused = true
        autoPaused = false
        pauseStartWall = maxOf(points.lastOrNull()?.time ?: wallNow, savedPauses.lastOrNull()?.end ?: 0L)
    }

    /** Called every second. Adds moving time and checks auto-pause; returns true if it just auto-paused. */
    fun tick(mono: Long, wall: Long): Boolean {
        val prev = lastMono
        lastMono = mono
        if (prev != null && !paused) movingMs += (mono - prev).coerceAtLeast(0)
        if (autoPauseEnabled() && !paused && movingMs > AUTO_PAUSE_GRACE_MS && track.isNotEmpty() &&
            wall - lastMoveAt > AUTO_PAUSE_MS
        ) {
            pause(wall, auto = true)
            return true
        }
        return false
    }

    /** Handles a new location. Returns the km just completed (for the split alert), or 0. */
    fun onLocation(raw: TrackPoint, speedMps: Float?, wall: Long): Int {
        // Smooth first so the track, the map marker and the uploaded points all agree.
        val p = smoother?.update(raw, speedMps) ?: raw
        lastFix = p
        if (paused && autoPaused && p.time <= pauseStartWall) {
            // A fix taken before the auto-pause arrived late (Android batches locations with the screen off):
            // the pause was wrong, so undo it and restore the moving time.
            movingMs += (wall - pauseStartWall).coerceAtLeast(0)
            paused = false
            autoPaused = false
        }
        if (paused) {
            val moved = filter.last?.let { TrackMath.distance(it, p) } ?: 0.0
            if (autoPaused && p.accuracy <= GPS_MAX_ACCURACY &&
                ((speedMps ?: 0f) > RESUME_SPEED || moved > RESUME_DISTANCE_M)
            ) {
                resume(maxOf(p.time, pauseStartWall))
                filter.reset(p)
                track += p
            }
            return 0
        }
        // Doppler speed proves movement even when the point is dropped as jitter below.
        // Check it before the filter so slow walking with poor accuracy is not auto-paused.
        if ((speedMps ?: 0f) > STILL_SPEED && p.accuracy <= GPS_MAX_ACCURACY) lastMoveAt = maxOf(lastMoveAt, p.time)
        val added = filter.accept(p) ?: return 0
        if (track.isEmpty() || added > 0 || filter.reanchored) {
            track += p
            distanceM += added
            if (added > 0) lastMoveAt = maxOf(lastMoveAt, p.time)
        }
        val km = floor(distanceM / 1000).toInt()
        if (km > lastKm) {
            lastKm = km
            return km
        }
        return 0
    }

    fun pause(wall: Long, auto: Boolean) {
        if (paused) return
        paused = true
        autoPaused = auto
        pauseStartWall = wall
    }

    fun resume(wall: Long) {
        if (!paused) return
        pauses += Pause(pauseStartWall, maxOf(wall, pauseStartWall))
        paused = false
        autoPaused = false
        lastMoveAt = wall
        filter.reset(lastFix)   // distance during a pause is not counted
    }

    /** All pauses, including one still in progress. */
    fun allPauses(wall: Long): List<Pause> =
        if (paused) pauses + Pause(pauseStartWall, maxOf(wall, pauseStartWall)) else pauses.toList()

    /** Pace over the last 30 seconds in s/km, or 0 when there is not enough data. */
    fun currentPace(): Double {
        if (track.size < 2) return 0.0
        val cutoff = track.last().time - 30_000
        val recent = track.filter { it.time >= cutoff }
        if (recent.size < 2) return 0.0
        var d = 0.0
        for (i in 1 until recent.size) d += TrackMath.distance(recent[i - 1], recent[i])
        val t = (recent.last().time - recent.first().time) / 1000.0
        return if (d < 10 || t <= 0) 0.0 else t / (d / 1000.0)
    }

    /** Pace of a completed kilometre, in s/km. */
    fun splitPace(km: Int): Int? = TrackMath.splits(track).firstOrNull { it.km == km }?.paceSecPerKm

    companion object {
        const val AUTO_PAUSE_MS = 8_000L
        const val AUTO_PAUSE_GRACE_MS = 10_000L
        const val STILL_SPEED = 0.5f
        const val RESUME_SPEED = 1.0f
        const val RESUME_DISTANCE_M = 8.0
        const val GPS_MAX_ACCURACY = 25f
    }
}
