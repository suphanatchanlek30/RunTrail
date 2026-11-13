package com.example.runtrail

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.net.Session
import com.example.runtrail.sync.SyncEngine
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.home.MainActivity
import com.example.runtrail.ui.leaderboard.LeaderboardActivity
import com.example.runtrail.ui.notifications.NotificationsActivity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With the server unreachable (pointed at 127.0.0.1:9), every network screen must not hang or crash
 * and must show cached data. The device's real settings and session are restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class OfflineScreensTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private lateinit var savedPrefs: Map<String, *>
    private lateinit var savedSessionFile: Map<String, *>
    private var savedSession: Session? = null

    private fun snapshot(name: String) = HashMap(ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all)

    @Suppress("UNCHECKED_CAST")
    private fun restore(name: String, values: Map<String, *>) {
        val e = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        values.forEach { (k, v) ->
            when (v) {
                is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v); is Boolean -> e.putBoolean(k, v); is Set<*> -> e.putStringSet(k, v as Set<String>)
            }
        }
        e.commit()
    }

    @Before fun setUp() {
        savedPrefs = snapshot("runtrail_prefs")
        savedSessionFile = snapshot("runtrail_session")
        savedSession = App.services.sessions.get()
        val p = App.services.prefs
        p.apiUrl = "http://127.0.0.1:9"
        p.onboarded = true
        p.accountUserId = "u-offline"
        App.services.sessions.save(Session("u-offline", "off@example.com", "Offline", "access", "refresh"))
        p.saveLeaderboardCache("distance|week|global", JSONObject()
            .put("entries", JSONArray().put(JSONObject().put("rank", 1).put("user_id", "x").put("display_name", "Cached Runner")
                .put("value", 12_345.0).put("is_me", false)))
            .put("me", JSONObject.NULL).put("total", 1).put("fetched_at", System.currentTimeMillis()).toString())
        p.notificationsCache = JSONObject().put("unread_count", 0).put("items", JSONArray().put(JSONObject()
            .put("id", "n1").put("type", "run_verified").put("title", "Cached note").put("body", "from cache")
            .put("run_id", JSONObject.NULL).put("created_at", "2026-11-12T00:00:00Z").put("read", true))).toString()
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { "u-offline" }
    }

    @After fun tearDown() {
        restore("runtrail_prefs", savedPrefs)
        restore("runtrail_session", savedSessionFile)
        App.services.sessions.clear()
        savedSession?.let { App.services.sessions.save(it) }
    }

    private fun texts(v: View, out: MutableList<String> = mutableListOf()): List<String> {
        if (v is TextView && v.isShown) out += v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    private fun <A : Activity> waitForText(s: ActivityScenario<A>, text: String, timeoutMs: Long = 10_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var seen: List<String> = emptyList()
        while (SystemClock.uptimeMillis() < end) {
            s.onActivity { seen = texts(it.window.decorView) }
            if (seen.any { text in it }) return
            Thread.sleep(250)
        }
        throw AssertionError("'$text' not on screen: $seen")
    }

    @Test fun screensKeepWorkingWhenTheServerIsUnreachable() {
        // Leaderboard: cached result stays after the refresh fails.
        ActivityScenario.launch(LeaderboardActivity::class.java).use { s ->
            waitForText(s, "Cached Runner")
            Thread.sleep(2_000)      // let the request fail first
            waitForText(s, "Cached Runner")
            assertEquals(Lifecycle.State.RESUMED, s.state)
        }
        // Notifications: cached list plus the offline label.
        ActivityScenario.launch(NotificationsActivity::class.java).use { s ->
            waitForText(s, "Cached note")
            var status = ""
            val end = SystemClock.uptimeMillis() + 10_000
            while (status.isEmpty() && SystemClock.uptimeMillis() < end) {
                s.onActivity { a -> a.findViewById<TextView>(R.id.tvStatus).let { if (it.isShown) status = it.text.toString() } }
                Thread.sleep(250)
            }
            assertTrue("offline status shown", status.isNotEmpty())
        }
        // Home opens normally; the notification fetch fails silently.
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            Thread.sleep(1_500)
            assertEquals(Lifecycle.State.RESUMED, s.state)
        }
        // Sync reports a retry; the run stays pending.
        val id = RunRepository.insert(Run(title = "offline", startTime = System.currentTimeMillis() - 3_600_000,
            endTime = System.currentTimeMillis() - 1_800_000, distanceM = 5000.0, movingTimeS = 1800, source = RunSource.MANUAL))
        val outcome = App.services.sync.sync()
        assertTrue("sync asks to retry: $outcome", outcome is SyncEngine.Outcome.Retry)
        assertEquals(SyncStatus.PENDING, RunRepository.getById(id, withTrack = false)!!.syncStatus)
        assertTrue("session kept while offline", App.services.sessions.get() != null)

        // Sign-in shows "can't reach the server" instead of spinning forever.
        App.services.sessions.clear()
        ActivityScenario.launch(LoginActivity::class.java).use { s ->
            s.onActivity {
                it.findViewById<EditText>(R.id.etEmail).setText("someone@example.com")
                it.findViewById<EditText>(R.id.etPassword).setText("password123")
                it.findViewById<View>(R.id.btnLogin).performClick()
            }
            val end = SystemClock.uptimeMillis() + 15_000
            var error = ""
            while (error.isEmpty() && SystemClock.uptimeMillis() < end) {
                s.onActivity { a -> a.findViewById<TextView>(R.id.tvError).let { if (it.isShown) error = it.text.toString() } }
                Thread.sleep(250)
            }
            assertTrue("network error shown", error.isNotEmpty())
            var spinning = true
            s.onActivity { spinning = it.findViewById<View>(R.id.progress).isShown }
            assertEquals("spinner stopped", false, spinning)
        }
    }
}
