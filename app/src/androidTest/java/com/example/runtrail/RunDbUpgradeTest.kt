package com.example.runtrail

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ฐานข้อมูลบนเครื่องผู้ใช้จริงเป็น v1 (ยังไม่มีคอลัมน์ feel) → เปิดแอปเวอร์ชันใหม่ต้อง onUpgrade ได้
 * โดยการวิ่งเดิมไม่หาย และอ่านได้ทุกหน้า (readRun ใช้ getColumnIndexOrThrow("feel"))
 */
@RunWith(AndroidJUnit4::class)
class RunDbUpgradeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "upgrade_test.db"

    @After fun tearDown() {
        ctx.deleteDatabase(name)
    }

    @Test fun v1DatabaseUpgradesWithoutLosingRuns() {
        ctx.deleteDatabase(name)
        // 1) สร้างฐานข้อมูลแล้วใส่การวิ่ง 1 รายการ
        val v2 = RunDb(ctx, name)
        RunRepository.init(ctx, v2)
        RunRepository.currentUser = { null }
        val now = System.currentTimeMillis()
        RunRepository.insert(Run(title = "ก่อนอัปเดตแอป", startTime = now - 3_600_000, endTime = now - 1_800_000,
            distanceM = 5000.0, movingTimeS = 1800))
        // 2) ทำให้เหมือนไฟล์ v1: ลบคอลัมน์ feel แล้วตั้ง version = 1
        v2.writableDatabase.apply {
            execSQL("ALTER TABLE runs DROP COLUMN feel")
            version = 1
        }
        v2.close()

        // 3) เปิดด้วยแอปเวอร์ชันใหม่ → onUpgrade(1 → 2)
        val upgraded = RunDb(ctx, name)
        assertEquals(RunDb.VERSION, upgraded.readableDatabase.version)
        RunRepository.init(ctx, upgraded)
        val runs = RunRepository.getAll()
        assertEquals(1, runs.size)
        assertEquals("ก่อนอัปเดตแอป", runs[0].title)
        assertEquals(0, runs[0].feel)

        // ให้คะแนนหลังอัปเกรดได้ตามปกติ
        RunRepository.update(runs[0].copy(feel = 5))
        assertEquals(5, RunRepository.getById(runs[0].id, withTrack = false)!!.feel)
        upgraded.close()
    }
}
