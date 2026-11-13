package com.example.runtrail.net

import com.example.runtrail.domain.Lang
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Server error in the form `{"error":{"code","message","fields"}}`. Status 0 means the server was unreachable. */
class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val fields: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : IOException("$status $code: $message", cause) {
    val isNetwork get() = status == 0
    val isServerError get() = status >= 500 || status == 429
}

/**
 * Blocking HTTP client for the backend API. Call from a background thread.
 *
 * Adds the access token and, on 401, refreshes it once and retries. Refreshes are serialised so
 * concurrent 401s trigger a single refresh. If the refresh is rejected the session is cleared and
 * [onSessionExpired] is called; local data is kept.
 */
class ApiClient(
    private val baseUrl: () -> String,
    val sessions: SessionStore,
    private val onSessionExpired: () -> Unit = {},
    builder: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    private val refreshLock = Any()

    /** Client without the token authenticator, for refresh calls and presigned uploads. */
    val plain: OkHttpClient = builder
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private val authed: OkHttpClient = plain.newBuilder()
        .authenticator(TokenAuthenticator())
        .build()

    fun url(path: String) = baseUrl().trimEnd('/') + "/api/v1" + path

    fun rootUrl(path: String) = baseUrl().trimEnd('/') + path

    /** Calls the API and returns the JSON body, or null for 204. [gzip] compresses the request body. */
    fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = true, gzip: Boolean = false): JSONObject? {
        val rb = body?.let { jsonBody(it, gzip) }
        val req = Request.Builder().url(url(path)).method(method, rb ?: emptyBodyFor(method)).apply {
            header("Accept", "application/json")
            if (gzip && rb != null) header("Content-Encoding", "gzip")
            if (auth) sessions.get()?.let { header("Authorization", "Bearer ${it.accessToken}") }
                ?: throw ApiException(401, "not_logged_in", Lang.t("ยังไม่ได้เข้าสู่ระบบ", "Not signed in"))
        }.build()
        val res = try {
            (if (auth) authed else plain).newCall(req).execute()
        } catch (e: IOException) {
            throw ApiException(0, "network", Lang.t("เชื่อมต่อเซิร์ฟเวอร์ไม่ได้", "Can't reach the server"), cause = e)
        }
        return res.use { parse(it) }
    }

    private fun emptyBodyFor(method: String): RequestBody? =
        if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody(JSON) else null

    private fun jsonBody(o: JSONObject, gzip: Boolean): RequestBody {
        val bytes = o.toString().toByteArray()
        if (!gzip) return bytes.toRequestBody(JSON)
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray().toRequestBody(JSON)
    }

    private fun parse(res: Response): JSONObject? {
        val text = res.body.string()
        if (res.isSuccessful) {
            if (text.isBlank()) return null
            return try { JSONObject(text) } catch (e: JSONException) {
                throw ApiException(res.code, "bad_response", Lang.t("ข้อมูลจากเซิร์ฟเวอร์ไม่ถูกต้อง", "Invalid response from server"))
            }
        }
        throw errorFrom(res.code, text)
    }

    /** PUTs a file to a presigned storage URL (no app token is sent). */
    fun upload(url: String, headers: Map<String, String>, bytes: ByteArray, contentType: String) {
        val req = Request.Builder().url(url).put(bytes.toRequestBody(contentType.toMediaType())).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            plain.newCall(req).execute().use { if (!it.isSuccessful) throw ApiException(it.code, "upload_failed", Lang.t("อัปโหลดรูปไม่สำเร็จ", "Photo upload failed")) }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(0, "network", Lang.t("อัปโหลดรูปไม่สำเร็จ", "Photo upload failed"), cause = e)
        }
    }

    fun download(url: String): ByteArray? = try {
        plain.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
    } catch (e: IOException) {
        null
    }

    /** Refreshes the access token. Returns the new token, or null if the session has expired. */
    fun refreshNow(usedAccess: String?): String? = synchronized(refreshLock) {
        val s = sessions.get() ?: return null
        if (usedAccess != null && s.accessToken != usedAccess) return s.accessToken   // another request already refreshed
        val req = Request.Builder().url(url("/auth/refresh"))
            .post(JSONObject().put("refresh_token", s.refreshToken).toString().toByteArray().toRequestBody(JSON))
            .build()
        val res = try { plain.newCall(req).execute() } catch (e: IOException) { return null }
        res.use {
            if (it.isSuccessful) {
                val o = JSONObject(it.body.string())
                val next = s.copy(accessToken = o.getString("access_token"), refreshToken = o.getString("refresh_token"))
                sessions.save(next)
                return next.accessToken
            }
            if (it.code == 401) {
                sessions.clear()
                onSessionExpired()
            }
            return null
        }
    }

    private inner class TokenAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.priorResponse != null) return null          // already retried once
            val used = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
            val fresh = refreshNow(used) ?: return null
            return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
        }
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        const val CONNECT_TIMEOUT_S = 15L
        /** A sleeping free-tier server can take about a minute to answer the first request. */
        const val READ_TIMEOUT_S = 90L
        const val WRITE_TIMEOUT_S = 60L

        fun errorFrom(status: Int, text: String): ApiException = try {
            val e = JSONObject(text).getJSONObject("error")
            val fields = mutableMapOf<String, String>()
            e.optJSONObject("fields")?.let { f -> f.keys().forEach { k -> fields[k] = f.optString(k) } }
            ApiException(status, e.optString("code", "error"), e.optString("message", Lang.t("เกิดข้อผิดพลาด", "Something went wrong")), fields)
        } catch (ex: JSONException) {
            ApiException(status, "http_$status", Lang.t("เซิร์ฟเวอร์ตอบกลับผิดพลาด ($status)", "Server error ($status)"))
        }
    }
}
