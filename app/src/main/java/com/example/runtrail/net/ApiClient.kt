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

/**
 * ข้อผิดพลาดจาก server ตามรูปแบบ {"error":{"code","message","fields"}} (status 0 = เชื่อมต่อไม่ได้)
 */
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
 * HTTP client ของแอป (ทำงานแบบรอผล → เรียกจาก background thread เท่านั้น)
 *
 * - แนบ access token ให้อัตโนมัติ
 * - เจอ 401 → ขอ token ใหม่ด้วย refresh token แล้วลองซ้ำ 1 ครั้ง
 *   มี lock เดียวทั้งแอป: ถ้าหลายคำขอเจอ 401 พร้อมกัน จะ refresh แค่ครั้งเดียว
 * - refresh ไม่ผ่าน → ล้าง session แล้วเรียก [onSessionExpired] (ข้อมูลในเครื่องไม่หาย)
 */
class ApiClient(
    private val baseUrl: () -> String,
    val sessions: SessionStore,
    private val onSessionExpired: () -> Unit = {},
    builder: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    private val refreshLock = Any()

    /** client ไม่มี authenticator สำหรับ refresh เอง (กันวนซ้ำ) และสำหรับอัปโหลดไป S3 */
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

    /** เรียก API แล้วคืน JSON (null เมื่อ 204) · gzip = บีบอัด body (ใช้กับการวิ่งที่มีจุดเยอะ) */
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

    /** อัปโหลดไฟล์ไปที่ลิงก์ presigned ของ S3 (ไม่ส่ง token ของแอป) */
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

    /** ขอ token ใหม่ — คืน access token ใหม่ หรือ null ถ้า session หมดอายุ */
    fun refreshNow(usedAccess: String?): String? = synchronized(refreshLock) {
        val s = sessions.get() ?: return null
        if (usedAccess != null && s.accessToken != usedAccess) return s.accessToken   // อีกคำขอเพิ่ง refresh ไปแล้ว
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
            if (response.priorResponse != null) return null          // ลองไปแล้ว 1 ครั้ง
            val used = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
            val fresh = refreshNow(used) ?: return null
            return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
        }
    }

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        const val CONNECT_TIMEOUT_S = 15L
        /**
         * รอคำตอบได้ถึง 90 วินาที: เซิร์ฟเวอร์แพ็กเกจฟรี (Render) หลับเมื่อว่าง 15 นาที และใช้ ~1 นาทีกว่าจะตื่น
         * ระหว่างนั้นการเชื่อมต่อสำเร็จแล้ว (proxy ของ Render รับไว้) แค่ยังไม่ตอบ — เดิม 30 วินาทีจึง timeout ทุกครั้งแรก
         */
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
