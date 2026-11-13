package com.example.runtrail

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Accounts
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.net.ApiClient
import com.example.runtrail.net.ApiException
import com.example.runtrail.net.MemorySessionStore
import com.example.runtrail.net.RunTrailApi
import com.example.runtrail.net.Session
import com.example.runtrail.sync.SyncManager
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.auth.RegisterActivity
import com.example.runtrail.ui.settings.SettingsActivity
import org.hamcrest.Matchers.startsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ปุ่มที่มีเฉพาะตอนล็อกอิน กดผ่านหน้าจอจริงกับ backend จริง (AUDIT §6):
 * สมัคร → ประเทศ → ซ่อนจากอันดับ → แจ้งเตือนข่าวสาร → เปลี่ยนรหัสผ่าน → ออกจากระบบ → เข้าสู่ระบบ → ลบบัญชี
 *
 * ใช้บัญชีทดสอบใหม่ทุกครั้ง · ค่าตั้ง (SharedPreferences) และ session ของผู้ใช้จริงบนเครื่องถูกสำรองไว้แล้วคืนให้ครบตอนจบ
 * การวิ่งอยู่ในฐานข้อมูลในหน่วยความจำ · รันเมื่อส่ง -e e2eApiUrl เท่านั้น
 */
@RunWith(AndroidJUnit4::class)
class AccountFlowTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val url = InstrumentationRegistry.getArguments().getString("e2eApiUrl").orEmpty()
    private val email = "acct${System.currentTimeMillis()}@example.com"
    private val password = "password123"
    private val newPassword = "newpassword456"

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
        assumeTrue("ระบุ -e e2eApiUrl เพื่อรัน", url.isNotBlank())
        savedPrefs = snapshot("runtrail_prefs")
        savedSessionFile = snapshot("runtrail_session")
        savedSession = App.services.sessions.get()
        App.services.prefs.apiUrl = url
        App.services.prefs.accountUserId = null      // ให้เครื่องนี้เป็น Guest ระหว่างทดสอบ
        App.services.sessions.clear()
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { App.services.prefs.accountUserId }
    }

    @After fun tearDown() {
        if (!::savedPrefs.isInitialized) return
        // บัญชีทดสอบยังอยู่ (เช่น test ล้มกลางทาง) → ลบทิ้งที่ server
        if (Accounts.isLoggedIn) try { App.services.api.deleteMe() } catch (_: Exception) {}
        SyncManager.cancelAll(ctx)
        restore("runtrail_prefs", savedPrefs)
        restore("runtrail_session", savedSessionFile)
        App.services.sessions.clear()
        savedSession?.let { App.services.sessions.save(it) }
        SyncManager.schedulePeriodic(ctx)            // เหมือนตอนเปิดแอปปกติ
        inst.runOnMainSync {
            androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).forEach { it.finish() }
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) { if (cond()) return; Thread.sleep(200) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun type(a: Activity, id: Int, text: String) = inst.runOnMainSync { a.findViewById<EditText>(id).setText(text) }
    private fun tap(a: Activity, id: Int) = inst.runOnMainSync { a.findViewById<View>(id).performClick() }
    private fun shown(a: Activity, id: Int): Boolean { var v = false; inst.runOnMainSync { v = a.findViewById<View>(id).isShown }; return v }

    /** API แยกต่างหาก (ไม่แตะ session ของแอป) ใช้ตรวจผลที่ server */
    private fun probe() = RunTrailApi(ApiClient({ url }, MemorySessionStore()))

    @Test fun everyAccountControlWorksAgainstTheRealBackend() {
        // 1) สมัครผ่านหน้าจอ
        ActivityScenario.launch(RegisterActivity::class.java).use { s ->
            var a: Activity? = null; s.onActivity { a = it }
            type(a!!, R.id.etName, "Account Test"); type(a!!, R.id.etEmail, email)
            type(a!!, R.id.etPassword, password); type(a!!, R.id.etConfirm, password)
            tap(a!!, R.id.btnRegister)
            waitUntil("registered") { Accounts.isLoggedIn }
        }

        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            var a: Activity? = null; s.onActivity { a = it }
            val st = a!!
            for (id in listOf(R.id.rowPassword, R.id.rowLogout, R.id.rowDeleteAccount, R.id.rowCountry, R.id.rowLeaderboard, R.id.rowUpdates)) {
                assertTrue("account row visible", shown(st, id))
            }

            // 2) ประเทศ → พิมพ์ "LA" ในช่อง AutoComplete → แตะ "LA · …" ในรายการ → ซิงก์ → server เก็บค่าใหม่
            tap(st, R.id.rowCountry)
            Thread.sleep(800)   // รอ dialog แสดงจนนิ่ง
            onView(withId(R.id.etCountry)).inRoot(isDialog()).perform(typeText("LA"))
            onView(withText(startsWith("LA ·"))).inRoot(isPlatformPopup()).perform(click())
            waitUntil("country saved locally") { App.services.prefs.countryCode == "LA" }
            App.services.sync.sync()
            assertEquals("LA", App.services.api.me().optString("country_code"))

            // 3) ซ่อนชื่อจากอันดับโลก
            assertTrue(App.services.prefs.leaderboardOptIn)
            inst.runOnMainSync { st.findViewById<View>(R.id.rowLeaderboard).performClick() }
            waitUntil("opt-out saved") { !App.services.prefs.leaderboardOptIn }
            App.services.sync.sync()
            assertFalse(App.services.api.me().optBoolean("leaderboard_opt_in", true))

            // 4) แจ้งเตือนข่าวสาร เปิด/ปิด (เก็บในเครื่อง)
            val alerts = App.services.prefs.updatesAlert
            inst.runOnMainSync { st.findViewById<View>(R.id.rowUpdates).performClick() }
            waitUntil("updates toggled") { App.services.prefs.updatesAlert != alerts }

            // 5) เปลี่ยนรหัสผ่านผ่าน dialog → รหัสใหม่เข้าได้ รหัสเก่าเข้าไม่ได้
            tap(st, R.id.rowPassword)
            Thread.sleep(500)
            onView(withId(R.id.etOld)).inRoot(isDialog()).perform(replaceText(password))
            onView(withId(R.id.etNew)).inRoot(isDialog()).perform(replaceText(newPassword))
            onView(withId(android.R.id.button1)).inRoot(isDialog()).perform(click())
            waitUntil("password changed on server") {
                try { probe().login(email, newPassword, "probe"); true } catch (e: ApiException) { false }
            }
            assertEquals(401, try { probe().login(email, password, "probe"); 0 } catch (e: ApiException) { e.status })
            assertTrue("still signed in after password change", Accounts.isLoggedIn)

            // 6) ออกจากระบบ (ยืนยันใน dialog)
            tap(st, R.id.rowLogout)
            Thread.sleep(500)
            onView(withId(R.id.btnConfirm)).inRoot(isDialog()).perform(click())
            waitUntil("logged out") { !Accounts.isLoggedIn }
        }

        // 7) เข้าสู่ระบบด้วยรหัสใหม่ผ่านหน้าจอ
        ActivityScenario.launch(LoginActivity::class.java).use { s ->
            var a: Activity? = null; s.onActivity { a = it }
            type(a!!, R.id.etEmail, email); type(a!!, R.id.etPassword, newPassword)
            tap(a!!, R.id.btnLogin)
            waitUntil("logged in again") { Accounts.isLoggedIn }
        }
        // โปรไฟล์จาก server กลับมา (ประเทศที่ตั้งไว้)
        assertEquals("LA", App.services.prefs.countryCode)

        // 8) ลบบัญชี (ยืนยันใน dialog) → เข้าสู่ระบบไม่ได้อีก
        ActivityScenario.launch(SettingsActivity::class.java).use { s ->
            var a: Activity? = null; s.onActivity { a = it }
            tap(a!!, R.id.rowDeleteAccount)
            Thread.sleep(500)
            onView(withId(R.id.btnConfirm)).inRoot(isDialog()).perform(click())
            waitUntil("account deleted") { !Accounts.isLoggedIn }
        }
        assertEquals(401, try { probe().login(email, newPassword, "probe"); 0 } catch (e: ApiException) { e.status })
    }
}
