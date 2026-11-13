package com.example.runtrail

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
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
import com.example.runtrail.ui.notifications.NotificationsActivity
import com.example.runtrail.ui.onboarding.PermissionsActivity
import com.example.runtrail.ui.onboarding.ProfileSetupActivity
import com.example.runtrail.ui.onboarding.WelcomeActivity
import com.example.runtrail.ui.records.RecordsActivity
import com.example.runtrail.ui.sensor.SensorCheckActivity
import com.example.runtrail.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/**
 * Captures every screen's layout and pixels to compare before/after a change that should not alter the UI.
 *
 *  -e snap before : record view geometry, text sizes and a screenshot of each screen
 *  -e snap after  : record again and compare; geometry must match and images must be identical
 * Output: /sdcard/Android/data/<app>/files/snapshots/
 */
@RunWith(AndroidJUnit4::class)
class ScreenSnapshotTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val mode = InstrumentationRegistry.getArguments().getString("snap").orEmpty()
    private val prefs = PrefsHelper(ctx)
    private var wasOnboarded = false
    private var gpsId = 0L
    private var manualId = 0L

    @Before fun setUp() {
        assumeTrue("run with -e snap before|after", mode == "before" || mode == "after")
        wasOnboarded = prefs.onboarded
        prefs.onboarded = true
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        // Fixed time (today 06:30) so dates render the same in both passes.
        val base = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val track = List(600) { i -> TrackPoint(13.7290 + i * 3.0 / 111_194.93, 100.5385, base + i * 1000L, 5.0 + i % 9, 5f) }
        gpsId = RunRepository.insert(Run(uuid = "00000000-0000-0000-0000-000000000001", title = "Morning run", startTime = base,
            endTime = base + 600_000, distanceM = 1797.0, movingTimeS = 600, calories = 120.0, track = track))
        manualId = RunRepository.insert(Run(uuid = "00000000-0000-0000-0000-000000000002", title = "Manual", startTime = base - 86_400_000,
            endTime = base - 86_400_000 + 1_800_000, distanceM = 5000.0, movingTimeS = 1800, calories = 300.0, source = RunSource.MANUAL))
    }

    @After fun tearDown() { if (mode.isNotEmpty()) prefs.onboarded = wasOnboarded }

    private val dir get() = File(ctx.getExternalFilesDir(null), "snapshots").apply { mkdirs() }

    private fun name(v: View) = if (v.id == View.NO_ID) "" else try { v.resources.getResourceEntryName(v.id) } catch (e: Exception) { "" }

    /** Position and size of every visible view (independent of time and map loading). */
    private fun geometry(root: View): String {
        val sb = StringBuilder()
        val xy = IntArray(2)
        fun walk(v: View, path: String) {
            if (!v.isShown) return
            v.getLocationInWindow(xy)
            val p = "$path/${v.javaClass.simpleName}#${name(v)}"
            sb.append(p).append(' ').append("${xy[0]},${xy[1]} ${v.width}x${v.height} pad=${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom}")
            if (v is TextView) sb.append(" ts=${v.textSize} lh=${v.lineHeight} dp=${v.compoundDrawablePadding}")
            sb.append(" el=${v.elevation}\n")
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), "$p[$i]")
        }
        walk(root, "")
        return sb.toString()
    }

    private fun capture(label: String, intent: Intent, pixels: Boolean = true): List<String> {
        val problems = mutableListOf<String>()
        ActivityScenario.launch<Activity>(intent).use { s ->
            inst.waitForIdleSync(); Thread.sleep(900); inst.waitForIdleSync()
            var geo = ""
            var bmp: Bitmap? = null
            s.onActivity { a ->
                // The blinking cursor makes screenshots unstable.
                fun hideCursor(v: View) { if (v is EditText) v.isCursorVisible = false; if (v is ViewGroup) for (i in 0 until v.childCount) hideCursor(v.getChildAt(i)) }
                val root = a.findViewById<View>(android.R.id.content)
                hideCursor(root)
                geo = geometry(root)
                if (pixels && root.width > 0) {
                    bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
                }
            }
            val geoFile = File(dir, "$label.$mode.txt").apply { writeText(geo) }
            bmp?.let { b -> File(dir, "$label.$mode.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            if (mode == "after") {
                val before = File(dir, "$label.before.txt").readText()
                if (before != geoFile.readText()) {
                    val a = before.lines(); val b = geo.lines()
                    val firstDiff = a.indices.firstOrNull { it >= b.size || a[it] != b[it] }
                    problems += "$label geometry differs at line $firstDiff:\n  before ${a.getOrNull(firstDiff ?: 0)}\n  after  ${b.getOrNull(firstDiff ?: 0)}"
                }
                val beforePng = File(dir, "$label.before.png")
                val after = bmp
                if (after != null && beforePng.exists()) {
                    val old = BitmapFactory.decodeFile(beforePng.path)
                    if (old.width != after.width || old.height != after.height) problems += "$label image size ${old.width}x${old.height} → ${after.width}x${after.height}"
                    else {
                        val n = old.width * old.height
                        val p1 = IntArray(n); val p2 = IntArray(n)
                        old.getPixels(p1, 0, old.width, 0, 0, old.width, old.height)
                        after.getPixels(p2, 0, after.width, 0, 0, after.width, after.height)
                        var diff = 0
                        for (i in 0 until n) if (p1[i] != p2[i]) diff++
                        Log.i(TAG, "$label: ${geo.lines().size - 1} views identical=${before == geo}, pixels differing $diff / $n")
                        if (diff > 0) problems += "$label: $diff pixels differ"
                        // Side-by-side image: left = before, right = after.
                        val pair = Bitmap.createBitmap(old.width * 2 + 24, old.height, Bitmap.Config.ARGB_8888)
                        Canvas(pair).apply { drawColor(Color.MAGENTA); drawBitmap(old, 0f, 0f, null); drawBitmap(after, old.width + 24f, 0f, null) }
                        File(dir, "$label.compare.png").outputStream().use { pair.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }
            }
        }
        return problems
    }

    @Test fun snapshotEveryScreen() {
        fun i(cls: Class<out Activity>, runId: Long? = null) =
            Intent(ctx, cls).apply { runId?.let { putExtra(RunDetailActivity.EXTRA_RUN_ID, it) } }
        val problems = buildList {
            addAll(capture("welcome", i(WelcomeActivity::class.java)))
            addAll(capture("permissions", i(PermissionsActivity::class.java)))
            addAll(capture("login", i(LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ONBOARDING, true)))
            addAll(capture("register", i(RegisterActivity::class.java)))
            addAll(capture("profile", i(ProfileSetupActivity::class.java)))
            addAll(capture("home", i(MainActivity::class.java)))
            addAll(capture("history", i(HistoryActivity::class.java)))
            addAll(capture("records", i(RecordsActivity::class.java)))
            addAll(capture("settings", i(SettingsActivity::class.java)))
            addAll(capture("notifications", i(NotificationsActivity::class.java)))
            addAll(capture("sensors", i(SensorCheckActivity::class.java), pixels = false))      // live sensor values change
            addAll(capture("detail_manual", i(RunDetailActivity::class.java, manualId)))
            addAll(capture("detail_gps", i(RunDetailActivity::class.java, gpsId), pixels = false)) // map tiles load from the network
            addAll(capture("stats", i(RunStatsActivity::class.java, gpsId)))
            addAll(capture("edit", i(AddEditRunActivity::class.java, gpsId)))
            addAll(capture("add_manual", i(AddEditRunActivity::class.java), pixels = false))   // shows the current time
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    companion object { const val TAG = "RunTrailSnap" }
}
