package com.example.runtrail

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.RunSource
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.detail.RunStatsActivity
import com.example.runtrail.ui.replay.ReplayActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * เปิดหน้าจอด้วยการวิ่งที่ "ไม่มีเส้นทาง GPS" (เพิ่มเอง) — เคยทำให้แอปเด้ง (AUDIT A1, A2)
 * ใช้ฐานข้อมูลในหน่วยความจำ ไม่แตะข้อมูลของแอป
 */
@RunWith(AndroidJUnit4::class)
class ScreenCrashTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private var runId = 0L

    @Before fun setUp() {
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        val start = System.currentTimeMillis() - 3_600_000
        runId = RunRepository.insert(
            Run(title = "เพิ่มเอง", startTime = start, endTime = start + 1_800_000, distanceM = 5000.0,
                movingTimeS = 1800, source = RunSource.MANUAL),
        )
    }

    private fun <T : android.app.Activity> intent(cls: Class<T>) =
        Intent(ctx, cls).putExtra(RunDetailActivity.EXTRA_RUN_ID, runId)

    @Test fun replayOfManualRunDoesNotCrash() {
        ActivityScenario.launch<ReplayActivity>(intent(ReplayActivity::class.java)).use { s ->
            // ไม่มีเส้นทาง → แจ้งผู้ใช้แล้วปิดหน้า หรือแสดงหน้าว่าง แต่ต้องไม่เด้ง
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(300)   // ให้ frame ของการเล่นทำงานอย่างน้อยหนึ่งรอบ
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(true, s.state == Lifecycle.State.RESUMED || s.state == Lifecycle.State.DESTROYED)
        }
    }

    @Test fun elevationChartOfManualRunDoesNotCrash() {
        ActivityScenario.launch<RunStatsActivity>(intent(RunStatsActivity::class.java)).use { s ->
            s.onActivity { a -> a.findViewById<android.view.View>(R.id.chipElevation).performClick() }
            s.onActivity { a -> a.findViewById<android.view.View>(R.id.chipPace).performClick() }
            assertEquals(Lifecycle.State.RESUMED, s.state)
        }
    }

    @Test fun detailOfManualRunOpens() {
        ActivityScenario.launch<RunDetailActivity>(intent(RunDetailActivity::class.java)).use { s ->
            assertEquals(Lifecycle.State.RESUMED, s.state)
        }
    }
}
