package com.example.runtrail

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.auth.RegisterActivity
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.detail.RunStatsActivity
import com.example.runtrail.ui.edit.AddEditRunActivity
import com.example.runtrail.ui.history.HistoryActivity
import com.example.runtrail.ui.home.MainActivity
import com.example.runtrail.ui.leaderboard.LeaderboardActivity
import com.example.runtrail.ui.notifications.NotificationsActivity
import com.example.runtrail.ui.onboarding.PermissionsActivity
import com.example.runtrail.ui.onboarding.ProfileSetupActivity
import com.example.runtrail.ui.onboarding.WelcomeActivity
import com.example.runtrail.ui.records.RecordsActivity
import com.example.runtrail.ui.replay.ReplayActivity
import com.example.runtrail.ui.sensor.SensorCheckActivity
import com.example.runtrail.ui.settings.SettingsActivity
import com.example.runtrail.ui.share.ShareImageActivity
import com.example.runtrail.ui.widget.ToggleSwitch
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ตรวจ "ปุ่มที่กดแล้วไม่มีอะไรเกิดขึ้น" ทุกหน้าจอ (AUDIT §3)
 *
 * เปิดแต่ละหน้า แล้วไล่ทุก view ที่มองเห็น:
 *  - กดได้ (clickable) แต่ไม่มี OnClickListener → ปุ่มตาย
 *  - id ขึ้นต้นด้วย btn / action / chip / seg / row / card แต่ไม่มี OnClickListener → หน้าตาเป็นปุ่มแต่กดไม่ได้
 * รายการ view ที่มีตัวรับการกดทั้งหมดเขียนลง logcat (tag RunTrailControls) เพื่อทำตารางใน AUDIT
 */
@RunWith(AndroidJUnit4::class)
class ControlsAuditTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = PrefsHelper(ctx)
    private var wasOnboarded = false
    private var gpsId = 0L
    private var manualId = 0L

    @Before fun setUp() {
        wasOnboarded = prefs.onboarded
        prefs.onboarded = true       // คืนค่าเดิมใน tearDown
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        val start = System.currentTimeMillis() - 3_600_000
        val track = List(600) { i -> TrackPoint(13.7290 + i * 3.0 / 111_194.93, 100.5385, start + i * 1000L, 5.0 + i % 9, 5f) }
        gpsId = RunRepository.insert(Run(title = "GPS", startTime = start, endTime = start + 600_000, distanceM = 1797.0,
            movingTimeS = 600, track = track))
        manualId = RunRepository.insert(Run(title = "Manual", startTime = start - 86_400_000, endTime = start - 86_400_000 + 1_800_000,
            distanceM = 5000.0, movingTimeS = 1800, source = RunSource.MANUAL))
    }

    @After fun tearDown() { prefs.onboarded = wasOnboarded }

    /** ชื่อที่ใช้กับปุ่มเท่านั้น (row/card ใช้กับกล่องแสดงข้อมูลด้วย จึงนับเฉพาะเมื่อกดได้) */
    private val buttonLike = Regex("^(btn|action|chip|seg|speed|fab)[A-Z0-9].*")

    private fun name(v: View): String =
        if (v.id == View.NO_ID) v.javaClass.simpleName else try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "#${v.id}" }

    private fun audit(a: Activity, wired: MutableList<String>, dead: MutableList<String>) {
        fun walk(v: View) {
            if (!v.isShown) return
            val n = name(v)
            val selfHandled = v is EditText || v is CompoundButton || v is SeekBar || v is AdapterView<*> ||
                v is RecyclerView || v is ToggleSwitch || v is org.osmdroid.views.MapView
            if (v.hasOnClickListeners()) wired += n
            else if (!selfHandled && (v.isClickable || buttonLike.matches(n))) {
                // แถวที่เป็นส่วนหัว/กล่องที่ตั้ง clickable เพื่อกันการแตะทะลุ ไม่ใช่ปุ่ม
                if (!(v is ViewGroup && !buttonLike.matches(n) && v.id == View.NO_ID)) dead += n
            }
            if (v is ViewGroup && v !is RecyclerView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
    }

    private fun check(cls: Class<out Activity>, label: String, extras: Intent.() -> Unit = {}): List<String> {
        val intent = Intent(ctx, cls).apply(extras)
        val wired = mutableListOf<String>()
        val dead = mutableListOf<String>()
        ActivityScenario.launch<Activity>(intent).use { s ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(400)   // ให้ข้อมูลที่โหลดบน background แสดงผลก่อน
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            s.onActivity { audit(it, wired, dead) }
        }
        Log.i(TAG, "$label | wired: ${wired.distinct().sorted().joinToString(", ")}")
        if (dead.isNotEmpty()) Log.w(TAG, "$label | DEAD: ${dead.distinct().joinToString(", ")}")
        return dead.distinct().map { "$label: $it" }
    }

    @Test fun everyVisibleControlHasAHandler() {
        val run: Intent.(Long) -> Unit = { putExtra(RunDetailActivity.EXTRA_RUN_ID, it) }
        val dead = buildList {
            addAll(check(WelcomeActivity::class.java, "Welcome"))
            addAll(check(PermissionsActivity::class.java, "Permissions"))
            addAll(check(LoginActivity::class.java, "Login") { putExtra(LoginActivity.EXTRA_ONBOARDING, true) })
            addAll(check(RegisterActivity::class.java, "Register"))
            addAll(check(ProfileSetupActivity::class.java, "ProfileSetup"))
            addAll(check(MainActivity::class.java, "Home"))
            addAll(check(HistoryActivity::class.java, "History"))
            addAll(check(LeaderboardActivity::class.java, "Leaderboard"))
            addAll(check(RecordsActivity::class.java, "Records"))
            addAll(check(SettingsActivity::class.java, "Settings"))
            addAll(check(NotificationsActivity::class.java, "Notifications"))
            addAll(check(SensorCheckActivity::class.java, "SensorCheck"))
            addAll(check(RunDetailActivity::class.java, "Detail(GPS)") { run(gpsId) })
            addAll(check(RunDetailActivity::class.java, "Detail(manual)") { run(manualId) })
            addAll(check(RunStatsActivity::class.java, "Stats") { run(gpsId) })
            addAll(check(ReplayActivity::class.java, "Replay") { run(gpsId) })
            addAll(check(AddEditRunActivity::class.java, "Edit") { run(gpsId) })
            addAll(check(AddEditRunActivity::class.java, "AddManual"))
            addAll(check(ShareImageActivity::class.java, "ShareImage") { run(gpsId) })
        }
        assertTrue("controls without a handler:\n" + dead.joinToString("\n"), dead.isEmpty())
    }

    companion object { const val TAG = "RunTrailControls" }
}
