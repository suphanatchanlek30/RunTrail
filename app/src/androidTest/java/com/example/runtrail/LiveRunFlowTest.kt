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
 * บันทึกการวิ่งผ่านหน้าจอจริงตั้งแต่ต้นจนจบ: เริ่ม → ระยะขึ้นจาก GPS → หยุดชั่วคราว (ระยะไม่เพิ่ม) → วิ่งต่อ →
 * หยุด → กดค้าง 1.5 วินาทีด้วยการแตะจริงเพื่อจบ → ได้การวิ่งที่มีเส้นทาง ช่วงหยุด และเปิดหน้าสรุป
 *
 * ตำแหน่ง GPS:
 *  - ค่าเริ่มต้น: test provider "gps" ของ LocationManager (ผ่าน API เดียวกับ GPS จริง)
 *  - `-e gpsSource host`: ไม่จำลองในเครื่อง ใช้ GPS ของ emulator ที่ส่งจาก Mac ด้วย `adb emu geo fix` ทุก 1 วินาที
 * ใช้ฐานข้อมูลในหน่วยความจำ → ไม่มีการวิ่งของ test ไปปนในแอปของผู้ใช้
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

    /** วิ่งไปทางเหนือ 4 ม./วิ (เกินเกณฑ์ตัดจุดสั่น 3 ม. ของ GpsFilter) จุดละ 1 วินาที (ความแม่นยำ 5 ม.) */
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

    /** กดค้างด้วยการแตะจริง (ส่ง MotionEvent ผ่านระบบ ไม่ได้เรียกฟังก์ชันตรง ๆ) */
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

            // 1) เริ่มแล้วระยะขึ้นจาก GPS และแสดงบนหน้าจอ
            waitUntil("service started", 10_000) { service() != null }
            waitUntil("distance > 30 m", 60_000) { (service()?.state?.distanceM ?: 0.0) > 30 }
            waitUntil("distance shown", 5_000) { a.findViewById<TextView>(R.id.tvDistance).text.toString() != "0.00" }

            // 2) หยุดชั่วคราว → ระยะไม่เพิ่ม แม้ GPS ยังเคลื่อนที่
            click(a, R.id.btnPause)
            waitUntil("paused sheet", 5_000) { a.findViewById<View>(R.id.sheetPaused).visibility == View.VISIBLE }
            val pausedAt = service()!!.state.distanceM
            Thread.sleep(4_000)
            assertEquals("no distance while paused", pausedAt, service()!!.state.distanceM, 0.01)

            // 3) วิ่งต่อ → ระยะเพิ่มอีก
            click(a, R.id.btnResume)
            waitUntil("running sheet", 5_000) { a.findViewById<View>(R.id.sheetRunning).visibility == View.VISIBLE }
            waitUntil("distance grows after resume", 30_000) { service()!!.state.distanceM > pausedAt + 15 }

            // 4) หยุด แล้วกดค้างเพื่อจบ
            click(a, R.id.btnPause)
            waitUntil("paused again", 5_000) { a.findViewById<View>(R.id.sheetPaused).visibility == View.VISIBLE }
            holdToStop(a.findViewById(R.id.btnHoldStop))
            waitUntil("recording stopped", 10_000) { !RunTrackingService.isRunning }
        }

        // 5) ได้การวิ่งจริง 1 รายการ พร้อมเส้นทางและช่วงหยุด และเปิดหน้าสรุปให้
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
