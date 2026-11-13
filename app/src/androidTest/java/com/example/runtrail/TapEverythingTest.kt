package com.example.runtrail

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
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
import com.example.runtrail.ui.onboarding.ProfileSetupActivity
import com.example.runtrail.ui.onboarding.WelcomeActivity
import com.example.runtrail.ui.records.RecordsActivity
import com.example.runtrail.ui.replay.ReplayActivity
import com.example.runtrail.ui.sensor.SensorCheckActivity
import com.example.runtrail.ui.settings.SettingsActivity
import com.example.runtrail.ui.share.ShareImageActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Taps every control that has a click listener, one per fresh screen, and checks the app does not crash.
 * A crash shows as "Process crashed"; logcat (tag RunTrailTap) names the last control tapped.
 *
 * Skipped: controls that open other apps (camera, gallery, share targets, system settings, permission
 * dialogs) and controls that change the device's real data or settings.
 */
@RunWith(AndroidJUnit4::class)
class TapEverythingTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val prefs = PrefsHelper(ctx)
    private var wasOnboarded = false
    private var gpsId = 0L
    private var manualId = 0L

    private val skip = setOf(
        "btnCamera", "btnGallery", "photoChip",          // camera / gallery
        "btnShare@Records", "btnShare@ShareImage", "btnSave@ShareImage", "segPhoto@ShareImage",   // share targets / save to gallery
        "cardLocation", "cardActivity", "btnNext@Permissions",                                     // system permission dialogs
        "btnDone@ProfileSetup", "rowAutoPause", "rowKmAlert", "rowRecreate",                       // change the user's settings
        "fabStart", "btnFirstRun",                                                                 // starts a real recording (covered by RunTrackingServiceTest)
    )

    @Before fun setUp() {
        wasOnboarded = prefs.onboarded
        prefs.onboarded = true
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        val start = System.currentTimeMillis() - 3_600_000
        val track = List(600) { i -> TrackPoint(13.7290 + i * 3.0 / 111_194.93, 100.5385, start + i * 1000L, 5.0 + i % 9, 5f) }
        gpsId = RunRepository.insert(Run(title = "GPS", startTime = start, endTime = start + 600_000, distanceM = 1797.0,
            movingTimeS = 600, track = track))
        manualId = RunRepository.insert(Run(title = "Manual", startTime = start - 86_400_000,
            endTime = start - 86_400_000 + 1_800_000, distanceM = 5000.0, movingTimeS = 1800, source = RunSource.MANUAL))
    }

    @After fun tearDown() { prefs.onboarded = wasOnboarded }

    private fun name(v: View) = if (v.id == View.NO_ID) "" else try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "" }

    private fun wired(a: Activity): List<String> {
        val out = mutableListOf<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            val n = name(v)
            if (v.hasOnClickListeners() && n.isNotEmpty()) out += n
            if (v is ViewGroup && v !is RecyclerView) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(a.window.decorView)
        return out.distinct()
    }

    private fun settle() { inst.waitForIdleSync(); Thread.sleep(250); inst.waitForIdleSync() }

    private fun tapAll(label: String, intent: () -> Intent) {
        var names: List<String> = emptyList()
        ActivityScenario.launch<Activity>(intent()).use { s -> settle(); s.onActivity { names = wired(it) } }
        for (n in names) {
            if (n in skip || "$n@$label" in skip) continue
            ActivityScenario.launch<Activity>(intent()).use { s ->
                settle()
                Log.i(TAG, "tap $label.$n")
                s.onActivity { a ->
                    val id = a.resources.getIdentifier(n, "id", a.packageName)
                    a.window.decorView.findViewById<View>(id)?.takeIf { it.isShown }?.performClick()
                }
                settle()
            }
        }
        Log.i(TAG, "$label: tapped ${names.count { it !in skip && "$it@$label" !in skip }} of ${names.size}")
    }

    @Test fun tappingEveryControlNeverCrashes() {
        fun i(cls: Class<out Activity>, runId: Long? = null) = { Intent(ctx, cls).apply { runId?.let { putExtra(RunDetailActivity.EXTRA_RUN_ID, it) } } }
        tapAll("Welcome", i(WelcomeActivity::class.java))
        tapAll("Login") { Intent(ctx, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ONBOARDING, true) }
        tapAll("Register", i(RegisterActivity::class.java))
        tapAll("ProfileSetup", i(ProfileSetupActivity::class.java))
        tapAll("Home", i(MainActivity::class.java))
        tapAll("History", i(HistoryActivity::class.java))
        tapAll("Leaderboard", i(LeaderboardActivity::class.java))
        tapAll("Records", i(RecordsActivity::class.java))
        tapAll("Settings", i(SettingsActivity::class.java))
        tapAll("Notifications", i(NotificationsActivity::class.java))
        tapAll("SensorCheck", i(SensorCheckActivity::class.java))
        tapAll("DetailGps", i(RunDetailActivity::class.java, gpsId))
        tapAll("DetailManual", i(RunDetailActivity::class.java, manualId))
        tapAll("Stats", i(RunStatsActivity::class.java, gpsId))
        tapAll("StatsManual", i(RunStatsActivity::class.java, manualId))
        tapAll("Replay", i(ReplayActivity::class.java, gpsId))
        tapAll("Edit", i(AddEditRunActivity::class.java, gpsId))
        tapAll("AddManual", i(AddEditRunActivity::class.java))
        tapAll("ShareImage", i(ShareImageActivity::class.java, gpsId))
    }

    companion object { const val TAG = "RunTrailTap" }
}
