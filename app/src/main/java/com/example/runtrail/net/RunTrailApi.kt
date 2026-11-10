package com.example.runtrail.net

import android.net.Uri
import com.example.runtrail.data.Run
import com.example.runtrail.data.TrackPoint
import org.json.JSONObject

/** endpoint ทั้งหมดของ backend (เรียกจาก background thread) — รายละเอียดใน backend/README.md */
class RunTrailApi(val client: ApiClient) {

    // ---------------- บัญชี

    fun register(email: String, password: String, displayName: String, country: String?, device: String): Session =
        session(client.call("POST", "/auth/register", JSONObject().apply {
            put("email", email); put("password", password); put("display_name", displayName)
            country?.let { put("country_code", it) }; put("device_name", device)
        }, auth = false)!!)

    fun login(email: String, password: String, device: String): Pair<Session, JSONObject> {
        val o = client.call("POST", "/auth/login", JSONObject().apply {
            put("email", email); put("password", password); put("device_name", device)
        }, auth = false)!!
        return session(o) to o.getJSONObject("user")
    }

    private fun session(o: JSONObject): Session {
        val u = o.getJSONObject("user")
        return Session(u.getString("id"), u.getString("email"), u.getString("display_name"),
            o.getString("access_token"), o.getString("refresh_token")).also { client.sessions.save(it) }
    }

    /** ยกเลิก refresh token ของเครื่องนี้ (ไม่มีเน็ตก็ไม่เป็นไร token จะหมดอายุเอง) */
    fun logout() {
        val s = client.sessions.get() ?: return
        try { client.call("POST", "/auth/logout", JSONObject().put("refresh_token", s.refreshToken), auth = false) } catch (_: ApiException) {}
    }

    fun changePassword(old: String, new: String, device: String) {
        val o = client.call("POST", "/auth/change-password", JSONObject().apply {
            put("old_password", old); put("new_password", new); put("device_name", device)
        })!!
        val s = client.sessions.get() ?: return
        client.sessions.save(s.copy(accessToken = o.getString("access_token"), refreshToken = o.getString("refresh_token")))
    }

    fun me(): JSONObject = client.call("GET", "/me")!!
    fun patchMe(body: JSONObject): JSONObject = client.call("PATCH", "/me", body)!!
    fun deleteMe() { client.call("DELETE", "/me") }

    // ---------------- การวิ่ง

    fun putRun(run: Run, track: List<TrackPoint>): JSONObject =
        client.call("PUT", "/runs/${run.uuid}", RunJson.toPutBody(run, track), gzip = true)!!

    fun deleteRun(uuid: String) { client.call("DELETE", "/runs/$uuid") }

    fun listRuns(updatedSince: String?, cursor: String?): JSONObject {
        val q = Uri.Builder().apply {
            appendQueryParameter("limit", "100")
            updatedSince?.let { appendQueryParameter("updated_since", it) }
            cursor?.let { appendQueryParameter("cursor", it) }
        }.build().encodedQuery
        return client.call("GET", "/runs?$q")!!
    }

    fun track(uuid: String): List<TrackPoint> =
        RunJson.parseTrack(client.call("GET", "/runs/$uuid/track")!!.getJSONArray("points"))

    // ---------------- รูป

    fun photoUploadUrl(uuid: String, contentType: String, size: Int): JSONObject =
        client.call("POST", "/runs/$uuid/photo/upload-url", JSONObject().put("content_type", contentType).put("size", size))!!

    fun photoConfirm(uuid: String, key: String): JSONObject =
        client.call("POST", "/runs/$uuid/photo/confirm", JSONObject().put("key", key))!!

    fun photoDelete(uuid: String) { client.call("DELETE", "/runs/$uuid/photo") }

    /** ขอ URL → PUT ไฟล์ไป S3 → confirm · คืนการวิ่งล่าสุดจาก server */
    fun uploadPhoto(uuid: String, jpeg: ByteArray): JSONObject {
        val up = photoUploadUrl(uuid, "image/jpeg", jpeg.size)
        val headers = mutableMapOf<String, String>()
        up.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.getString(it) } }
        client.upload(up.getString("upload_url"), headers, jpeg, "image/jpeg")
        return photoConfirm(uuid, up.getString("key"))
    }

    // ---------------- แชร์

    fun share(uuid: String, hideEnds: Boolean, showPhoto: Boolean, showKcal: Boolean): JSONObject =
        client.call("POST", "/runs/$uuid/share", JSONObject().apply {
            put("hide_ends", hideEnds); put("show_photo", showPhoto); put("show_kcal", showKcal)
        })!!

    fun unshare(uuid: String) { client.call("DELETE", "/runs/$uuid/share") }

    // ---------------- อันดับ

    fun leaderboard(metric: String, period: String, scope: String): JSONObject =
        client.call("GET", "/leaderboard?metric=$metric&period=$period&scope=$scope&limit=100")!!

    fun health(): Boolean = try {
        client.plain.newCall(okhttp3.Request.Builder().url(client.rootUrl("/healthz")).build())
            .execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }
}
