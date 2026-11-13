package com.example.runtrail.net

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Changing the server while signed in is allowed only when it is the same account's server. */
class ServerSwitchTest {

    private lateinit var oldServer: MockWebServer
    private lateinit var newServer: MockWebServer
    private lateinit var sessions: MemorySessionStore

    private fun json(code: Int, body: String) = MockResponse.Builder().code(code)
        .addHeader("Content-Type", "application/json").body(body).build()

    private fun api() = RunTrailApi(ApiClient({ oldServer.url("/").toString().trimEnd('/') }, sessions))

    private val newUrl get() = newServer.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        oldServer = MockWebServer().apply { start() }
        newServer = MockWebServer().apply { start() }
        sessions = MemorySessionStore(Session("u1", "a@b.co", "A", "access-1", "refresh-1"))
    }

    @After fun tearDown() { oldServer.close(); newServer.close() }

    @Test fun sameServerAtNewAddressIsAccepted() {
        newServer.enqueue(json(200, """{"id":"u1"}"""))
        assertTrue(api().sameAccountAt(newUrl))
        val req = newServer.takeRequest()
        assertEquals("/api/v1/me", req.url.encodedPath)
        assertEquals("Bearer access-1", req.headers["Authorization"])
        assertEquals(0, oldServer.requestCount)
    }

    @Test fun differentAccountIsRejected() {
        newServer.enqueue(json(200, """{"id":"someone-else"}"""))
        assertFalse(api().sameAccountAt(newUrl))
    }

    @Test fun unknownServerIsRejectedAndTokensKept() {
        newServer.enqueue(json(401, """{"error":{"code":"invalid_token","message":"x"}}"""))
        newServer.enqueue(json(401, """{"error":{"code":"invalid_refresh_token","message":"x"}}"""))
        assertFalse(api().sameAccountAt(newUrl))
        assertEquals("refresh-1", sessions.get()!!.refreshToken)   // the old server's session is kept
    }

    @Test fun expiredAccessIsRefreshedAtNewAddress() {
        newServer.enqueue(json(401, """{"error":{"code":"invalid_token","message":"x"}}"""))
        newServer.enqueue(json(200, """{"access_token":"access-2","refresh_token":"refresh-2"}"""))
        newServer.enqueue(json(200, """{"id":"u1"}"""))
        assertTrue(api().sameAccountAt(newUrl))
        assertEquals("access-2", sessions.get()!!.accessToken)
        assertEquals("refresh-2", sessions.get()!!.refreshToken)
    }

    @Test fun unreachableAddressIsRejected() {
        assertFalse(api().sameAccountAt("http://127.0.0.1:1"))
    }
}
