package com.example.runtrail.net

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

class ApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var sessions: MemorySessionStore
    private var expired = 0

    private fun session(access: String = "old-access", refresh: String = "old-refresh") =
        Session("u1", "a@b.co", "A", access, refresh)

    private fun client() = ApiClient({ server.url("/").toString().trimEnd('/') }, sessions, onSessionExpired = { expired++ })

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        sessions = MemorySessionStore(session())
        expired = 0
    }

    @After fun tearDown() = server.close()

    /** เซิร์ฟเวอร์ฟรีที่เพิ่งตื่น (Render) ตอบช้าเกือบนาที — เดิม readTimeout 30 s ทำให้ครั้งแรกล้มทุกครั้ง */
    @Test fun waitsForASleepingServerToWakeUp() {
        assertTrue("read timeout covers a ~60 s cold start", ApiClient.READ_TIMEOUT_S >= 75)
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
            .body("""{"status":"ok"}""").headersDelay(35, TimeUnit.SECONDS).build())
        val o = client().call("GET", "/me")!!
        assertEquals("ok", o.getString("status"))
    }

    @Test fun sendsBearerTokenAndParsesJson() {
        server.enqueue(json(200, """{"id":"u1","email":"a@b.co"}"""))
        val o = client().call("GET", "/me")!!
        assertEquals("a@b.co", o.getString("email"))
        val req = server.takeRequest()
        assertEquals("Bearer old-access", req.headers["Authorization"])
        assertEquals("/api/v1/me", req.url.encodedPath)
    }

    @Test fun parsesErrorFormat() {
        server.enqueue(json(400, """{"error":{"code":"validation_failed","message":"ข้อมูลไม่ถูกต้อง","fields":{"email":"email"}}}"""))
        try {
            client().call("POST", "/auth/register", JSONObject(), auth = false)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
            assertEquals("validation_failed", e.code)
            assertEquals("email", e.fields["email"])
        }
    }

    @Test fun networkErrorHasStatusZero() {
        server.close()
        try {
            client().call("GET", "/me")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.isNetwork)
        }
    }

    @Test fun refreshesOnceOn401ThenRetries() {
        server.enqueue(json(401, """{"error":{"code":"invalid_token","message":"x"}}"""))
        server.enqueue(json(200, """{"access_token":"new-access","refresh_token":"new-refresh"}"""))
        server.enqueue(json(200, """{"ok":true}"""))
        val o = client().call("GET", "/me")!!
        assertTrue(o.getBoolean("ok"))
        server.takeRequest()
        val refresh = server.takeRequest()
        assertEquals("/api/v1/auth/refresh", refresh.url.encodedPath)
        assertTrue(refresh.body!!.utf8().contains("old-refresh"))
        assertEquals("Bearer new-access", server.takeRequest().headers["Authorization"])
        assertEquals("new-refresh", sessions.get()!!.refreshToken)
    }

    @Test fun failedRefreshSignsOutButKeepsNothingElse() {
        server.enqueue(json(401, """{"error":{"code":"invalid_token","message":"x"}}"""))
        server.enqueue(json(401, """{"error":{"code":"refresh_token_reused","message":"x"}}"""))
        try {
            client().call("GET", "/me")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.status)
        }
        assertNull(sessions.get())
        assertEquals(1, expired)
    }

    /** UI กับ SyncWorker เจอ 401 พร้อมกัน → refresh ครั้งเดียว (ไม่ให้ server มองว่า token ถูกขโมย) */
    @Test fun concurrent401sShareOneRefresh() {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    refreshes.incrementAndGet()
                    Thread.sleep(200)
                    return json(200, """{"access_token":"new-access","refresh_token":"new-refresh"}""")
                }
                return if (request.headers["Authorization"] == "Bearer new-access") json(200, """{"ok":true}""")
                else json(401, """{"error":{"code":"invalid_token","message":"x"}}""")
            }
        }
        val c = client()
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        val ok = AtomicInteger()
        repeat(4) {
            pool.execute {
                try { if (c.call("GET", "/me")!!.getBoolean("ok")) ok.incrementAndGet() } finally { done.countDown() }
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(4, ok.get())
        assertEquals(1, refreshes.get())
    }

    @Test fun gzipBody() {
        server.enqueue(json(201, """{"id":"r1"}"""))
        client().call("PUT", "/runs/r1", JSONObject().put("title", "วิ่งเช้า"), gzip = true)
        val req = server.takeRequest()
        assertEquals("gzip", req.headers["Content-Encoding"])
        val text = GZIPInputStream(req.body!!.toByteArray().inputStream()).bufferedReader().readText()
        assertEquals("วิ่งเช้า", JSONObject(text).getString("title"))
    }

    @Test fun noContentReturnsNull() {
        server.enqueue(MockResponse.Builder().code(204).build())
        assertNull(client().call("DELETE", "/runs/x"))
    }

    @Test fun notLoggedInFailsWithoutNetwork() {
        sessions.clear()
        try {
            client().call("GET", "/me")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals("not_logged_in", e.code)
        }
        assertEquals(0, server.requestCount)
    }
}
