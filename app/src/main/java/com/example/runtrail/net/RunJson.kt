package com.example.runtrail.net

import com.example.runtrail.data.PhotoSync
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.Split
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.data.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * แปลงการวิ่งระหว่างโมเดลในแอป ↔ JSON ของ backend (ดู backend/README.md)
 * Kotlin ล้วน ใช้ org.json → ทดสอบบน JVM ได้
 */
object RunJson {

    fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    /** body ของ PUT /runs/{uuid} */
    fun toPutBody(run: Run, track: List<TrackPoint>): JSONObject = JSONObject().apply {
        put("title", run.title.ifBlank { "การวิ่ง" })
        put("note", run.note ?: JSONObject.NULL)
        put("source", run.source.api)
        put("started_at", iso(run.startTime))
        put("ended_at", run.endTime?.let { iso(it) } ?: JSONObject.NULL)
        put("timezone", run.timezone ?: JSONObject.NULL)
        put("distance_m", run.distanceM)
        put("moving_time_s", run.movingTimeS)
        put("calories", run.calories)
        put("elevation_gain_m", run.elevationGainM)
        put("cadence_spm", run.cadenceSpm)
        put("temp_c", run.tempC ?: JSONObject.NULL)
        if (run.isGps) {
            put("points", JSONArray().apply {
                track.forEach { p ->
                    put(JSONArray().put(p.lat).put(p.lng).put(p.time).put(p.altitude).put(p.accuracy.toDouble()))
                }
            })
            put("pauses", JSONArray().apply { run.pauses.forEach { put(JSONArray().put(it.start).put(it.end)) } })
        }
        put("client_updated_at", iso(run.updatedAt))
    }

    /**
     * ใส่ค่าที่ server คำนวณ/ตรวจแล้วกลับเข้า Run (หลังส่งขึ้นสำเร็จ หรือดึงลงจาก server)
     * local = การวิ่งในเครื่องที่มีอยู่แล้ว (null = เพิ่งดึงมาครั้งแรก)
     */
    fun fromServer(o: JSONObject, local: Run?, owner: String?): Run {
        val share = o.optJSONObject("share")
        val base = local ?: Run(uuid = o.getString("id"), title = "", startTime = 0)
        return base.copy(
            uuid = o.getString("id"),
            ownerUserId = owner ?: base.ownerUserId,
            source = RunSource.from(o.optString("source")),
            title = o.getString("title"),
            note = o.str("note"),
            startTime = millis(o.getString("started_at")),
            endTime = o.str("ended_at")?.let { millis(it) } ?: millis(o.getString("started_at")) + o.optLong("moving_time_s") * 1000,
            timezone = o.str("timezone"),
            distanceM = o.optDouble("distance_m", 0.0),
            movingTimeS = o.optLong("moving_time_s"),
            calories = o.dbl("calories") ?: 0.0,
            elevationGainM = o.int("elevation_gain_m") ?: 0,
            cadenceSpm = o.int("cadence_spm") ?: 0,
            tempC = o.int("temp_c"),
            verified = o.optBoolean("verified"),
            verifyReason = o.str("verify_reason"),
            serverDistanceM = o.dbl("server_distance_m"),
            shareCode = share?.optString("code"),
            shareUrl = share?.optString("url"),
            shareHideEnds = share?.optBoolean("hide_ends", true) ?: base.shareHideEnds,
            shareShowPhoto = share?.optBoolean("show_photo", true) ?: base.shareShowPhoto,
            shareShowKcal = share?.optBoolean("show_kcal", false) ?: base.shareShowKcal,
            photoSync = when {
                o.str("photo_url") != null -> PhotoSync.SYNCED
                base.photoSync == PhotoSync.PENDING -> PhotoSync.PENDING
                else -> PhotoSync.NONE
            },
            syncStatus = SyncStatus.SYNCED,
            syncError = null,
            onServer = true,
            updatedAt = millis(o.getString("client_updated_at")),
            deleted = false,
            splits = o.optJSONArray("splits")?.let { parseSplits(it) } ?: base.splits,
        )
    }

    fun parseSplits(a: JSONArray): List<Split> = List(a.length()) { i ->
        val s = a.getJSONObject(i)
        Split(s.getInt("km"), s.getInt("pace_s"), s.optInt("elev_delta_m"))
    }

    fun parseTrack(a: JSONArray): List<TrackPoint> = List(a.length()) { i ->
        val p = a.getJSONArray(i)
        TrackPoint(p.getDouble(0), p.getDouble(1), p.getLong(2), p.getDouble(3), p.getDouble(4).toFloat())
    }

    /** ข้อความภาษาไทยของเหตุผลที่ server ไม่นับเข้าอันดับ */
    fun verifyReasonText(reason: String?): String? = when (reason) {
        null, "" -> null
        "manual" -> "บันทึกเอง ไม่มีเส้นทาง GPS"
        "too_few_points" -> "จุด GPS น้อยเกินไป"
        "sparse_points" -> "สัญญาณ GPS ขาดเป็นช่วง ๆ"
        "distance_mismatch" -> "ระยะจาก GPS ไม่ตรงกับที่บันทึก"
        "moving_time_mismatch" -> "เวลาไม่ตรงกับเส้นทาง"
        "too_fast" -> "เพซเร็วผิดปกติ"
        "too_fast_segment" -> "มีช่วงที่เร็วผิดปกติ (เช่น ขึ้นรถ)"
        "future_start" -> "เวลาเริ่มอยู่ในอนาคต"
        "no_distance" -> "ไม่มีระยะทาง"
        else -> reason
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
    private fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)
    private fun JSONObject.int(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
}
