package com.example.runtrail

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.service.RunTrackingService
import com.example.runtrail.ui.live.LiveRunActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recording continues with the screen off: mock GPS at one fix per second, screen on for the first
 * half and off for the second; every point is stored and the finished run is saved.
 */
@RunWith(AndroidJUnit4::class)
class RunTrackingServiceTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun shell(cmd: String) = inst.uiAutomation.executeShellCommand(cmd).close()

    @Before fun setUp() {
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        shell("appops set ${ctx.packageName} android:mock_location allow")
        Thread.sleep(300)
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        lm.addTestProvider(
            LocationManager.GPS_PROVIDER,
            ProviderProperties.Builder().setHasAltitudeSupport(true).setHasSpeedSupport(true)
                .setAccuracy(ProviderProperties.ACCURACY_FINE).setPowerUsage(ProviderProperties.POWER_USAGE_HIGH).build(),
        )
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    @After fun tearDown() {
        shell("input keyevent KEYCODE_WAKEUP")
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        shell("appops set ${ctx.packageName} android:mock_location deny")
    }

    private var lat = 13.7290

    private fun push(speed: Float = 4f) {   // > 3 m per fix, otherwise GpsFilter treats it as standing still
        lat += speed / 111_194.93
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = lat; longitude = 100.5385; altitude = 5.0; accuracy = 4f; this.speed = speed
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
        Thread.sleep(1000)
    }

    private fun waitFor(timeoutMs: Long, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) { if (ok()) return; Thread.sleep(100) }
    }

    @Test fun keepsRecordingWithScreenOff() {
        val scenario = ActivityScenario.launch(LiveRunActivity::class.java)
        waitFor(10_000) { RunTrackingService.current != null }
        val service = assertNotNullAndGet(RunTrackingService.current)

        repeat(15) { push() }
        val screenOn = service.state.track.size
        assertTrue("points recorded with screen on: $screenOn", screenOn >= 10)

        shell("input keyevent KEYCODE_SLEEP")
        Thread.sleep(1500)
        repeat(20) { push() }
        val total = service.state.track.size
        assertTrue("screen off kept recording: $screenOn → $total", total >= screenOn + 15)
        assertTrue("moving time kept counting: ${service.state.movingS}", service.state.movingS >= 30)
        shell("input keyevent KEYCODE_WAKEUP")

        // Points are written to SQLite while running, so the run can be recovered.
        waitFor(6_000) { (RunRepository.activeDraft()?.track?.size ?: 0) >= total - 5 }
        assertTrue((RunRepository.activeDraft()?.track?.size ?: 0) >= total - 5)

        var id = 0L
        inst.runOnMainSync { id = service.finish() }
        val run = RunRepository.getById(id)!!
        assertNotNull(run.endTime)
        assertEquals(total, run.track.size)
        assertEquals(service.state.distanceM, run.distanceM, 0.001)
        assertEquals("distance = 4 m × steps", (total - 1) * 4.0, run.distanceM, (total - 1) * 4.0 * 0.03)
        assertEquals(1, run.splits.size)   // < 1 km gives one partial split
        assertEquals(null, RunTrackingService.current)
        scenario.close()
    }

    private fun assertNotNullAndGet(s: RunTrackingService?): RunTrackingService {
        assertNotNull("service started", s)
        return s!!
    }
}
