package com.example.runtrail.data

import com.example.runtrail.net.RunJson
import org.json.JSONArray
import org.json.JSONObject

/**
 * ไฟล์สำรองประวัติการวิ่ง (JSON) สำหรับส่งออก/นำเข้าผ่าน SAF (MA09 Shared Storage)
 *
 * รูปแบบแต่ละการวิ่งเหมือน body ของ PUT /runs/{uuid} + "id" (uuid) → นำเข้าแล้วซิงก์ขึ้น server ได้ทันที
 * Kotlin ล้วน ใช้ org.json → ทดสอบบน JVM ได้
 */
object RunBackup {

    const val FORMAT = "runtrail-backup"
    const val VERSION = 1
    const val MIME = "application/json"

    fun toJson(runs: List<Run>, now: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("exported_at", RunJson.iso(now))
        put("runs", JSONArray().apply {
            runs.filter { it.finished && !it.deleted }.forEach { r ->
                put(RunJson.toPutBody(r, r.track).put("id", r.uuid).put("feel", r.feel))
            }
        })
    }

    /**
     * อ่านไฟล์สำรอง → รายการการวิ่ง (สถานะ "รอซิงก์" ยังไม่มีเจ้าของ: ตอนบันทึกจะผูกกับบัญชีปัจจุบัน)
     * ไฟล์ผิดรูปแบบ → IllegalArgumentException
     */
    fun parse(text: String): List<Run> {
        val root = try { JSONObject(text) } catch (e: Exception) { throw IllegalArgumentException("not json", e) }
        require(root.optString("format") == FORMAT) { "not a RunTrail backup" }
        require(root.optInt("version") <= VERSION) { "newer backup version" }
        val arr = root.optJSONArray("runs") ?: return emptyList()
        return List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
    }

    private fun fromJson(o: JSONObject): Run {
        val start = RunJson.millis(o.getString("started_at"))
        val moving = o.optLong("moving_time_s")
        val pauses = o.optJSONArray("pauses")?.let { a ->
            List(a.length()) { Pause(a.getJSONArray(it).getLong(0), a.getJSONArray(it).getLong(1)) }
        } ?: emptyList()
        return Run(
            uuid = o.getString("id"),
            source = RunSource.from(o.optString("source")),
            title = o.optString("title"),
            note = o.str("note"),
            startTime = start,
            endTime = o.str("ended_at")?.let { RunJson.millis(it) } ?: (start + moving * 1000),
            timezone = o.str("timezone"),
            distanceM = o.optDouble("distance_m", 0.0),
            movingTimeS = moving,
            calories = o.optDouble("calories", 0.0),
            elevationGainM = o.optInt("elevation_gain_m"),
            cadenceSpm = o.optInt("cadence_spm"),
            tempC = if (o.isNull("temp_c") || !o.has("temp_c")) null else o.optInt("temp_c"),
            updatedAt = o.str("client_updated_at")?.let { RunJson.millis(it) } ?: System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING,
            onServer = false,
            track = o.optJSONArray("points")?.let { RunJson.parseTrack(it) } ?: emptyList(),
            pauses = pauses,
            feel = o.optInt("feel").coerceIn(0, 5),
        )
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
}
