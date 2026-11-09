package com.example.runtrail.data

import android.content.Context
import java.io.File
import java.util.Properties

/**
 * เก็บสถานะการวิ่งที่ยังไม่จบลงไฟล์ run_draft.properties (MA09 File)
 * ถ้าแอปถูกปิดกลางคัน หน้าหลักจะอ่านไฟล์นี้แล้วถามว่าจะกู้คืนหรือไม่
 */
class DraftStore(context: Context) {

    data class Draft(
        val startTime: Long,
        val distanceM: Double,
        val elapsedS: Long,
        val gpsPoints: Int,
        val calories: Double,
        val savedAt: Long,
    )

    private val file = File(context.filesDir, FILE_NAME)
    private val trackFile = File(context.filesDir, TRACK_FILE)

    /** จุด GPS ของการวิ่งที่ยังไม่จบ (CSV: lat,lng,time,alt,acc) */
    fun saveTrack(track: List<TrackPoint>) {
        trackFile.bufferedWriter().use { w ->
            track.forEach { w.write("${it.lat},${it.lng},${it.time},${it.altitude},${it.accuracy}\n") }
        }
    }

    fun loadTrack(): List<TrackPoint> {
        if (!trackFile.exists()) return emptyList()
        return trackFile.readLines().mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 5) null else try {
                TrackPoint(c[0].toDouble(), c[1].toDouble(), c[2].toLong(), c[3].toDouble(), c[4].toFloat())
            } catch (e: NumberFormatException) { null }
        }
    }

    fun save(draft: Draft) {
        val p = Properties()
        p.setProperty("start_time", draft.startTime.toString())
        p.setProperty("distance_m", draft.distanceM.toString())
        p.setProperty("elapsed_s", draft.elapsedS.toString())
        p.setProperty("gps_points", draft.gpsPoints.toString())
        p.setProperty("calories", draft.calories.toString())
        p.setProperty("saved_at", draft.savedAt.toString())
        file.outputStream().use { p.store(it, "RunTrail unfinished run") }
    }

    fun load(): Draft? {
        if (!file.exists()) return null
        return try {
            val p = Properties()
            file.inputStream().use { p.load(it) }
            Draft(
                startTime = p.getProperty("start_time").toLong(),
                distanceM = p.getProperty("distance_m").toDouble(),
                elapsedS = p.getProperty("elapsed_s").toLong(),
                gpsPoints = p.getProperty("gps_points").toInt(),
                calories = p.getProperty("calories").toDouble(),
                savedAt = p.getProperty("saved_at").toLong(),
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        if (file.exists()) file.delete()
        if (trackFile.exists()) trackFile.delete()
    }

    companion object {
        private const val FILE_NAME = "run_draft.properties"
        private const val TRACK_FILE = "run_draft_track.csv"
    }
}
