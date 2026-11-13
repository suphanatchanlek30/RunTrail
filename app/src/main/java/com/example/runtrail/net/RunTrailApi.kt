package com.example.runtrail.net

import android.net.Uri
import com.example.runtrail.data.Run
import com.example.runtrail.data.TrackPoint
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Blocking wrappers for every backend endpoint (see backend/README.md). Call from a background thread. */
class RunTrailApi(val client: ApiClient) {

    // ---------------- Account

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

    /** Revokes this device's refresh token. Failures are ignored; the token expires anyway. */
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

    // ---------------- Runs

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

    // ---------------- Photos

    fun photoUploadUrl(uuid: String, contentType: String, size: Int): JSONObject =
        client.call("POST", "/runs/$uuid/photo/upload-url", JSONObject().put("content_type", contentType).put("size", size))!!

    fun photoConfirm(uuid: String, key: String): JSONObject =
        client.call("POST", "/runs/$uuid/photo/confirm", JSONObject().put("key", key))!!

    fun photoDelete(uuid: String) { client.call("DELETE", "/runs/$uuid/photo") }

    /** Requests an upload URL, uploads the file, then confirms it. Returns the updated run. */
    fun uploadPhoto(uuid: String, jpeg: ByteArray): JSONObject {
        val up = photoUploadUrl(uuid, "image/jpeg", jpeg.size)
        val headers = mutableMapOf<String, String>()
        up.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it] = h.getString(it) } }
        client.upload(up.getString("upload_url"), headers, jpeg, "image/jpeg")
        return photoConfirm(uuid, up.getString("key"))
    }

    // ---------------- Sharing

    fun share(uuid: String, hideEnds: Boolean, showPhoto: Boolean, showKcal: Boolean): JSONObject =
        client.call("POST", "/runs/$uuid/share", JSONObject().apply {
            put("hide_ends", hideEnds); put("show_photo", showPhoto); put("show_kcal", showKcal)
        })!!

    fun unshare(uuid: String) { client.call("DELETE", "/runs/$uuid/share") }

    // ---------------- Leaderboard

    fun leaderboard(metric: String, period: String, scope: String): JSONObject =
        client.call("GET", "/leaderboard?metric=$metric&period=$period&scope=$scope&limit=100")!!

    // ---------------- Notifications and other runners

    fun notifications(cursor: String? = null): JSONObject =
        client.call("GET", "/notifications?limit=50" + (cursor?.let { "&cursor=" + Uri.encode(it) } ?: ""))!!

    fun markAllRead() { client.call("POST", "/notifications/read", JSONObject().put("all", true)) }

    fun userSummary(userId: String): JSONObject = client.call("GET", "/users/$userId/summary")!!

    /**
     * Checks whether [baseUrl] is the same account's server (e.g. after the host's IP changed).
     * Tries `GET /me`, refreshing once on 401; keeps the refreshed tokens on success.
     */
    fun sameAccountAt(baseUrl: String): Boolean {
        val s = client.sessions.get() ?: return false
        val root = baseUrl.trim().trimEnd('/') + "/api/v1"
        fun me(token: String): Int = try {
            client.plain.newCall(okhttp3.Request.Builder().url("$root/me").header("Authorization", "Bearer $token").build())
                .execute().use { r ->
                    if (r.isSuccessful) { if (JSONObject(r.body.string()).optString("id") == s.userId) 200 else 409 } else r.code
                }
        } catch (e: Exception) { 0 }
        when (me(s.accessToken)) {
            200 -> return true
            401 -> Unit
            else -> return false
        }
        val refreshed = try {
            val body = JSONObject().put("refresh_token", s.refreshToken).toString()
                .toByteArray().toRequestBody(ApiClient.JSON)
            client.plain.newCall(okhttp3.Request.Builder().url("$root/auth/refresh").post(body).build()).execute().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body.string())
            }
        } catch (e: Exception) { null } ?: return false
        val next = s.copy(accessToken = refreshed.getString("access_token"), refreshToken = refreshed.getString("refresh_token"))
        if (me(next.accessToken) != 200) return false
        client.sessions.save(next)
        return true
    }

    fun health(): Boolean = try {
        client.plain.newCall(okhttp3.Request.Builder().url(client.rootUrl("/healthz")).build())
            .execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }
}
