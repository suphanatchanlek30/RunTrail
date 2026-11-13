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

/** App-wide Volley [RequestQueue] for requests made from screens. Background sync uses [ApiClient]. */
object VolleyApi {

    @Volatile private var queue: RequestQueue? = null

    /** Used to refresh the token after a 401; set at startup. */
    @Volatile internal var client: ApiClient? = null

    /** Runs blocking token refreshes off the main thread. */
    internal val refreshExecutor: Executor = Executors.newSingleThreadExecutor()

    internal fun current(): RequestQueue? = queue

    fun getQueue(context: Context): RequestQueue =
        queue ?: synchronized(this) {
            queue ?: Volley.newRequestQueue(context.applicationContext).also { queue = it }
        }
}

/**
 * [JsonObjectRequest] for the backend API.
 *
 * - Adds the bearer token and, on 401, refreshes it and retries once.
 * - Treats an empty body (204) as an empty object instead of a parse error.
 * - Waits up to 90 s without automatic retries, so a waking server is not hit twice with the same POST.
 * - Bypasses the Volley cache.
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

/** Maps a Volley error to [ApiException] so it can be shown with `userMessage()`. */
fun VolleyError.toApiException(): ApiException {
    val res = networkResponse
    if (res == null) {
        val parse = this is ParseError || cause is JSONException
        return if (parse) ApiException(200, "bad_response", Lang.t("ข้อมูลจากเซิร์ฟเวอร์ไม่ถูกต้อง", "Invalid response from server"), cause = this)
        else ApiException(0, "network", Lang.t("เชื่อมต่อเซิร์ฟเวอร์ไม่ได้", "Can't reach the server"), cause = this)
    }
    return ApiClient.errorFrom(res.statusCode, res.data?.let { String(it, Charsets.UTF_8) } ?: "")
}
