package com.example.runtrail.domain

import com.example.runtrail.data.Pause
import com.example.runtrail.data.TrackPoint
import kotlin.math.floor

/**
 * สถานะการวิ่งสด (Kotlin ล้วน ไม่ผูก Android → ทดสอบบน JVM ได้)
 *
 * - เวลาเคลื่อนที่นับจากนาฬิกาแบบ monotonic (SystemClock.elapsedRealtime) ไม่ใช่การนับจำนวน tick
 *   จึงไม่เพี้ยนเมื่อเครื่องช้าหรือหน้าจอดับ
 * - ช่วงหยุดบันทึกเป็นเวลา epoch เหมือนเวลาของจุด GPS เพื่อให้ server ตัดระยะช่วงนั้นออกได้ตรงกัน
 */
class LiveRunState(
    val startTime: Long,
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

    /** ฟื้นสถานะจาก draft ที่บันทึกไว้ (แอปถูกปิดกลางคัน) — กลับมาในสถานะหยุดชั่วคราว */
    fun restore(points: List<TrackPoint>, distance: Double, movingSeconds: Long, savedPauses: List<Pause>, wallNow: Long) {
        track.clear(); track += points
        pauses.clear(); pauses += savedPauses
        distanceM = distance
        movingMs = movingSeconds * 1000
        lastKm = floor(distance / 1000).toInt()
        filter.reset(points.lastOrNull())
        lastFix = points.lastOrNull()
        paused = true
        autoPaused = false
        pauseStartWall = maxOf(points.lastOrNull()?.time ?: wallNow, savedPauses.lastOrNull()?.end ?: 0L)
    }

    /** เรียกทุกวินาที: สะสมเวลาเคลื่อนที่ + ตรวจ auto-pause · คืน true ถ้าเพิ่งหยุดอัตโนมัติ */
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

    /** ได้ตำแหน่งใหม่ · คืนกิโลเมตรที่เพิ่งผ่าน (> 0) สำหรับแจ้งเตือน หรือ 0 */
    fun onLocation(p: TrackPoint, speedMps: Float?, wall: Long): Int {
        lastFix = p
        if (paused) {
            val moved = filter.last?.let { TrackMath.distance(it, p) } ?: 0.0
            if (autoPaused && p.accuracy <= GPS_MAX_ACCURACY &&
                ((speedMps ?: 0f) > RESUME_SPEED || moved > RESUME_DISTANCE_M)
            ) {
                resume(wall)
                filter.reset(p)
                track += p
            }
            return 0
        }
        val added = filter.accept(p) ?: return 0
        if (track.isEmpty() || added > 0) {
            track += p
            distanceM += added
            if (added > 0) lastMoveAt = wall
        }
        if ((speedMps ?: 0f) > STILL_SPEED) lastMoveAt = wall
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
        filter.reset(lastFix)   // ไม่นับระยะช่วงที่หยุด
    }

    /** รายการช่วงหยุดทั้งหมด รวมช่วงที่ยังหยุดค้างอยู่ (ใช้ตอนบันทึก/จบการวิ่ง) */
    fun allPauses(wall: Long): List<Pause> =
        if (paused) pauses + Pause(pauseStartWall, maxOf(wall, pauseStartWall)) else pauses.toList()

    /** เพซปัจจุบันจากระยะที่วิ่งใน 30 วินาทีล่าสุด (วินาที/กม.) 0 = ยังไม่มีข้อมูล */
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

    /** เพซของกิโลเมตรที่เพิ่งวิ่งครบ (แจ้งเตือนทุก 1 กม.) */
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
