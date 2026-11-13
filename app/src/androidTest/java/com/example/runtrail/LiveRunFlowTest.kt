package com.example.runtrail

import android.Manifest
import android.app.Activity
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.service.RunTrackingService
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.live.LiveRunActivity
import com.example.runtrail.ui.widget.HoldToStopView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * Records a run through the real UI: start → GPS distance → pause (no distance) → resume → pause →
 * hold 1.5 s with real touches to finish → a run with a track and pauses, and the summary opens.
 *
 * GPS comes from a LocationManager test provider, or with `-e gpsSource host` from `adb emu geo fix`
 * sent by the host every second. Uses an in-memory database.
 */
@RunWith(AndroidJUnit4::class)
class LiveRunFlowTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val hostGps = InstrumentationRegistry.getArguments().getString("gpsSource") == "host"
    @Volatile private var feeding = false
    private var feeder: Thread? = null

    @Before fun setUp() {
        val ui = inst.uiAutomation
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            .forEach { ui.grantRuntimePermission(ctx.packageName, it) }
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        if (!hostGps) startMockGps()
    }

    @After fun tearDown() {
        feeding = false
        feeder?.join(3000)
        inst.runOnMainSync { RunTrackingService.current?.discard() }
        if (!hostGps) {
            try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            shell("appops set ${ctx.packageName} android:mock_location default")
        }
    }

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()

    /** Runs north at 4 m/s (above the 3 m jitter threshold), one fix per second with 5 m accuracy. */
    @Suppress("DEPRECATION")
    private fun startMockGps() {
        shell("appops set ${ctx.packageName} android:mock_location allow")
        Thread.sleep(300)
        lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
            ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        feeding = true
        feeder = thread(name = "mock-gps") {
            var i = 0
            while (feeding) {
                val loc = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 13.7290 + i * 4.0 / 111_194.93; longitude = 100.5385; altitude = 5.0
                    accuracy = 5f; speed = 4f; time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                try { lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc) } catch (_: Exception) {}
                i++
                Thread.sleep(1000)
            }
        }
    }

    private fun waitUntil(what: String, timeoutMs: Long, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            var ok = false
            inst.runOnMainSync { ok = cond() }
            if (ok) return
            Thread.sleep(250)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun service() = RunTrackingService.current

    private fun click(a: Activity, id: Int) = inst.runOnMainSync { a.findViewById<View>(id).performClick() }

    /** Press-and-hold with real MotionEvents rather than calling the view directly. */
    private fun holdToStop(view: View) {
        val xy = IntArray(2)
        inst.runOnMainSync { view.getLocationOnScreen(xy) }
        val x = xy[0] + view.width / 2f
        val y = xy[1] + view.height / 2f
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        Thread.sleep(HoldToStopView.HOLD_MS + 600)
        val up = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test fun recordPauseResumeAndFinishThroughTheScreen() {
        ActivityScenario.launch(LiveRunActivity::class.java).use { s ->
            var activity: Activity? = null
            s.onActivity { activity = it }
            val a = activity!!

            // 1) Distance rises from GPS and is shown.
            waitUntil("service started", 10_000) { service() != null }
            waitUntil("distance > 30 m", 60_000) { (service()?.state?.distanceM ?: 0.0) > 30 }
            waitUntil("distance shown", 5_000) { a.findViewById<TextView>(R.id.tvDistance).text.toString() != "0.00" }

            // 2) Paused: distance does not grow while GPS still moves.
            click(a, R.id.btnPause)
            waitUntil("paused sheet", 5_000) { a.findViewById<View>(R.id.sheetPaused).visibility == View.VISIBLE }
            val pausedAt = service()!!.state.distanceM
            Thread.sleep(4_000)
            assertEquals("no distance while paused", pausedAt, service()!!.state.distanceM, 0.01)

            // 3) Resumed: distance grows again.
            click(a, R.id.btnResume)
            waitUntil("running sheet", 5_000) { a.findViewById<View>(R.id.sheetRunning).visibility == View.VISIBLE }
            waitUntil("distance grows after resume", 30_000) { service()!!.state.distanceM > pausedAt + 15 }

            // 4) Pause, then hold to finish.
            click(a, R.id.btnPause)
            waitUntil("paused again", 5_000) { a.findViewById<View>(R.id.sheetPaused).visibility == View.VISIBLE }
            holdToStop(a.findViewById(R.id.btnHoldStop))
            waitUntil("recording stopped", 10_000) { !RunTrackingService.isRunning }
        }

        // 5) One saved run with track and pauses; the summary opens.
        val run = RunRepository.getAll().single()
        val full = RunRepository.getById(run.id)!!
        Log.i("RunTrailLive", "finished: ${"%.1f".format(run.distanceM)} m, ${run.movingTimeS} s, ${full.track.size} points, ${run.pauses.size} pauses (gps=${if (hostGps) "host" else "mock"})")
        assertTrue("distance ${run.distanceM}", run.distanceM > 45)
        assertTrue("track ${full.track.size}", full.track.size >= 12)
        assertTrue("moving time ${run.movingTimeS}", run.movingTimeS > 10)
        assertNotNull(run.endTime)
        assertEquals("pause + final pause", 2, run.pauses.size)
        assertTrue(run.title.isNotBlank())
        var detail = false
        inst.runOnMainSync {
            detail = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is RunDetailActivity }
        }
        assertTrue("summary screen opened", detail)
        assertFalse(RunTrackingService.isRunning)
        inst.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() }
        }
    }
}
