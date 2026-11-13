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

/** A v1 database on a user's device (no `feel` column) upgrades without losing runs. */
@RunWith(AndroidJUnit4::class)
class RunDbUpgradeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "upgrade_test.db"

    @After fun tearDown() {
        ctx.deleteDatabase(name)
    }

    @Test fun v1DatabaseUpgradesWithoutLosingRuns() {
        ctx.deleteDatabase(name)
        // 1) Create a database with one run.
        val v2 = RunDb(ctx, name)
        RunRepository.init(ctx, v2)
        RunRepository.currentUser = { null }
        val now = System.currentTimeMillis()
        RunRepository.insert(Run(title = "ก่อนอัปเดตแอป", startTime = now - 3_600_000, endTime = now - 1_800_000,
            distanceM = 5000.0, movingTimeS = 1800))
        // 2) Make it look like v1: drop `feel` and set version 1.
        v2.writableDatabase.apply {
            execSQL("ALTER TABLE runs DROP COLUMN feel")
            version = 1
        }
        v2.close()

        // 3) Reopen with the current schema → onUpgrade(1 → 2).
        val upgraded = RunDb(ctx, name)
        assertEquals(RunDb.VERSION, upgraded.readableDatabase.version)
        RunRepository.init(ctx, upgraded)
        val runs = RunRepository.getAll()
        assertEquals(1, runs.size)
        assertEquals("ก่อนอัปเดตแอป", runs[0].title)
        assertEquals(0, runs[0].feel)

        // Ratings work after the upgrade.
        RunRepository.update(runs[0].copy(feel = 5))
        assertEquals(5, RunRepository.getById(runs[0].id, withTrack = false)!!.feel)
        upgraded.close()
    }
}
