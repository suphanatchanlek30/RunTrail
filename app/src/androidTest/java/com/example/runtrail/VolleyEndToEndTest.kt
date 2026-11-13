package com.example.runtrail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.volley.Request
import com.android.volley.VolleyError
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunSource
import com.example.runtrail.net.ApiClient
import com.example.runtrail.net.ApiConfig
import com.example.runtrail.net.ApiException
import com.example.runtrail.net.ApiRequest
import com.example.runtrail.net.MemorySessionStore
import com.example.runtrail.net.RunTrailApi
import com.example.runtrail.net.VolleyApi
import com.example.runtrail.net.toApiException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Volley requests against a real backend; skipped unless a server is given:
 *   ./gradlew connectedDebugAndroidTest -Pe2eApiUrl=http://10.0.2.2:8080
 *
 * GET, PATCH, POST and an empty 204 DELETE; expired-token refresh and retry; error mapping.
 */
@RunWith(AndroidJUnit4::class)
class VolleyEndToEndTest {

    @get:org.junit.Rule
    val localNetwork: androidx.test.rule.GrantPermissionRule =
        androidx.test.rule.GrantPermissionRule.grant("android.permission.ACCESS_LOCAL_NETWORK")

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private lateinit var api: RunTrailApi
    private var savedBase: (() -> String)? = null
    private var savedClient: ApiClient? = null

    @Before fun setUp() {
        val url = InstrumentationRegistry.getArguments().getString("e2eApiUrl").orEmpty()
        assumeTrue("ระบุ -Pe2eApiUrl เพื่อรัน end-to-end test", url.isNotBlank())
        api = RunTrailApi(ApiClient({ url }, MemorySessionStore()))
        api.register("volley${System.currentTimeMillis()}@example.com", "password123", "Volley Runner", "TH", "emulator")
        // Point Volley at this test's server and session; restored in tearDown.
        savedBase = ApiConfig.baseUrl
        savedClient = VolleyApi.client
        ApiConfig.baseUrl = { url }
        VolleyApi.client = api.client
    }

    @After fun tearDown() {
        savedBase?.let { ApiConfig.baseUrl = it }
        VolleyApi.client = savedClient
        if (::api.isInitialized && api.client.sessions.get() != null) {
            try { api.deleteMe() } catch (_: ApiException) {}
        }
    }

    /** Sends a request through the Volley queue and waits for its success or error callback. */
    private fun send(method: Int, url: String, body: JSONObject? = null): Pair<JSONObject?, VolleyError?> {
        val latch = CountDownLatch(1)
        var ok: JSONObject? = null
        var err: VolleyError? = null
        inst.runOnMainSync {
            val q = ApiRequest(method, url, body, { ok = it; latch.countDown() }, { err = it; latch.countDown() })
            VolleyApi.getQueue(ctx).add(q)
        }
        assertTrue("Volley ตอบภายใน 100 วินาที", latch.await(100, TimeUnit.SECONDS))
        return ok to err
    }

    @Test fun getLeaderboard() {
        val (o, e) = send(Request.Method.GET, ApiConfig.leaderboard("distance", "week", "global"))
        assertNull(e)
        assertNotNull(o!!.optJSONArray("entries"))
    }

    @Test fun patchCountry() {
        val (_, e) = send(Request.Method.PATCH, ApiConfig.ME, JSONObject().put("country_code", "LA"))
        assertNull(e)
        assertEquals("LA", api.me().optString("country_code"))
    }

    @Test fun shareThenUnshareWithEmptyResponse() {
        val now = System.currentTimeMillis()
        val run = Run(title = "Volley share", source = RunSource.MANUAL, startTime = now - 3_600_000,
            endTime = now - 1_800_000, distanceM = 5000.0, movingTimeS = 1800)
        api.putRun(run, emptyList())

        val body = JSONObject().put("hide_ends", true).put("show_photo", false).put("show_kcal", false)
        val (shared, e1) = send(Request.Method.POST, ApiConfig.share(run.uuid), body)
        assertNull(e1)
        assertTrue(shared!!.getString("url").isNotBlank())

        // DELETE returns 204 with no body: expect an empty object, not a parse error.
        val (gone, e2) = send(Request.Method.DELETE, ApiConfig.share(run.uuid))
        assertNull(e2)
        assertEquals(0, gone!!.length())
    }

    @Test fun expiredTokenIsRefreshedAndRetried() {
        val store = api.client.sessions
        val before = store.get()!!
        store.save(before.copy(accessToken = "expired-or-invalid"))
        val (me, e) = send(Request.Method.GET, ApiConfig.ME)
        assertNull("401 → refresh → ส่งซ้ำสำเร็จ", e)
        assertEquals(before.userId, me!!.optString("id"))
        assertNotEquals("expired-or-invalid", store.get()!!.accessToken)
    }

    @Test fun serverErrorBecomesApiException() {
        val (_, e) = send(Request.Method.GET, ApiConfig.userSummary("00000000-0000-0000-0000-000000000000"))
        assertNotNull(e)
        val ex = e!!.toApiException()
        assertEquals(404, ex.status)
        assertTrue(ex.message.isNotBlank())
    }
}
