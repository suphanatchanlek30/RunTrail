package com.example.runtrail.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.ParseError
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.VolleyError
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.example.runtrail.domain.Lang
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * เตรียม RequestQueue ตัวเดียวทั้งแอปให้หน้าจอใช้ส่งคำขอ (MA11: VolleyApi)
 *
 * ใช้: `VolleyApi.getQueue(this).add(q)` — สร้าง request แล้วต้อง add เข้าคิว จึงจะถูกส่งจริง
 * ส่วนการซิงก์เบื้องหลัง (WorkManager) ยังใช้ OkHttp เพราะต้องเรียกแบบรอผล
 */
object VolleyApi {

    @Volatile private var queue: RequestQueue? = null

    /** ใช้ refresh token เมื่อเจอ 401 (ตั้งใน Services) */
    @Volatile internal var client: ApiClient? = null

    /** thread สำหรับ refresh token (เป็นการเรียกแบบรอผล ห้ามทำบน main thread) */
    internal val refreshExecutor: Executor = Executors.newSingleThreadExecutor()

    /** คิวที่สร้างไว้แล้ว (ใช้ส่งคำขอซ้ำหลัง refresh token) */
    internal fun current(): RequestQueue? = queue

    fun getQueue(context: Context): RequestQueue =
        queue ?: synchronized(this) {
            queue ?: Volley.newRequestQueue(context.applicationContext).also { queue = it }
        }
}

/**
 * JsonObjectRequest ของแอป — 5 พารามิเตอร์เหมือนสไลด์ (method, url, body, listener, errorListener)
 * แล้วเพิ่มสิ่งที่ backend จริงต้องใช้:
 * - แนบ access token (`Authorization: Bearer …`) ให้อัตโนมัติ
 * - เจอ 401 → refresh token แล้วส่งซ้ำ 1 ครั้ง (Volley ไม่มี Authenticator แบบ OkHttp)
 * - คำตอบว่าง (204 เช่น DELETE) → ได้ JSONObject ว่าง แทน ParseError
 * - รอได้ 90 วินาทีและไม่ส่งซ้ำเอง: เซิร์ฟเวอร์ฟรีใช้ ~1 นาทีกว่าจะตื่น และการส่ง POST ซ้ำอาจทำงานซ้ำ
 * - ไม่ใช้ cache ของ Volley (อันดับ/การแจ้งเตือนต้องสดเสมอ)
 */
class ApiRequest(
    method: Int,
    url: String,
    jsonRequest: JSONObject?,
    private val onOk: Response.Listener<JSONObject>,
    private val onFail: Response.ErrorListener,
    private val auth: Boolean = true,
    private val retried: Boolean = false,
) : JsonObjectRequest(method, url, jsonRequest, onOk, onFail) {

    private val payload = jsonRequest

    init {
        setRetryPolicy(DefaultRetryPolicy(TIMEOUT_MS, 0, 1f))
        setShouldCache(false)
    }

    override fun getHeaders(): MutableMap<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        if (auth) VolleyApi.client?.sessions?.get()?.let { h["Authorization"] = "Bearer ${it.accessToken}" }
        return h
    }

    override fun parseNetworkResponse(response: NetworkResponse): Response<JSONObject> {
        if (response.data == null || response.data.isEmpty()) {
            return Response.success(JSONObject(), HttpHeaderParser.parseCacheHeaders(response))
        }
        return super.parseNetworkResponse(response)
    }

    override fun deliverError(error: VolleyError) {
        val client = VolleyApi.client
        if (!auth || retried || client == null || error.networkResponse?.statusCode != 401) {
            deliverOriginal(error)
            return
        }
        // token หมดอายุ → ขอใหม่บน background thread แล้วส่งคำขอเดิมซ้ำ 1 ครั้ง
        val used = headers["Authorization"]?.removePrefix("Bearer ")
        VolleyApi.refreshExecutor.execute {
            val fresh = client.refreshNow(used)
            main.post {
                val queue = VolleyApi.current()
                when {
                    isCanceled -> Unit
                    fresh == null || queue == null -> deliverOriginal(error)
                    else -> queue.add(ApiRequest(method, url, payload, onOk, onFail, auth, retried = true).also { it.setTag(tag) })
                }
            }
        }
    }

    private fun deliverOriginal(error: VolleyError) = super.deliverError(error)

    companion object {
        const val TIMEOUT_MS = 90_000
        private val main = Handler(Looper.getMainLooper())
    }
}

/** แปลง VolleyError เป็น ApiException (รหัส error + ข้อความจาก backend) เพื่อใช้ร่วมกับ userMessage() */
fun VolleyError.toApiException(): ApiException {
    val res = networkResponse
    if (res == null) {
        val parse = this is ParseError || cause is JSONException
        return if (parse) ApiException(200, "bad_response", Lang.t("ข้อมูลจากเซิร์ฟเวอร์ไม่ถูกต้อง", "Invalid response from server"), cause = this)
        else ApiException(0, "network", Lang.t("เชื่อมต่อเซิร์ฟเวอร์ไม่ได้", "Can't reach the server"), cause = this)
    }
    return ApiClient.errorFrom(res.statusCode, res.data?.let { String(it, Charsets.UTF_8) } ?: "")
}
