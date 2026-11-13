package com.example.runtrail

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Old vs new implementations on the same 1,000 runs. Timings go to logcat (tag RunTrailPerf);
 * assertions only cover results that do not depend on device speed.
 */
@RunWith(AndroidJUnit4::class)
class PerfTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun seed() {
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        val now = System.currentTimeMillis()
        repeat(1000) { i ->
            val start = now - i * 9 * 3_600_000L
            val track = List(60) { k -> TrackPoint(13.72 + k * 3e-5, 100.53, start + k * 1000L, 5.0, 5f) }
            RunRepository.insert(Run(title = "Run $i", startTime = start, endTime = start + 200_000, distanceM = 600.0 + i,
                movingTimeS = 200, track = track))
        }
    }

    private inline fun ms(block: () -> Unit): Double {
        block() // warm-up
        val n = 5
        val t0 = System.nanoTime()
        repeat(n) { block() }
        return (System.nanoTime() - t0) / 1e6 / n
    }

    @Test fun homeLoadsRunsOnce() {
        val now = System.currentTimeMillis()
        val before = ms {
            RunRepository.getAll(); RunRepository.thisWeek(now); RunRepository.weekDailyKm(now)
        }
        val after = ms {
            RunRepository.recent(2)
            val week = RunRepository.since(RunRepository.startOfWeek(now), now)
            RunRepository.weekDailyKm(now, week)
        }
        // Same result as before.
        assertEquals(RunRepository.thisWeek(now).map { it.id }, RunRepository.since(RunRepository.startOfWeek(now), now).map { it.id })
        assertEquals(RunRepository.getAll().take(2).map { it.id }, RunRepository.recent(2).map { it.id })
        Log.i(TAG, "P2 home data path, 1000 runs: before %.1f ms, after %.1f ms".format(before, after))
        assertTrue("after ($after) should not be slower than before ($before)", after < before)
    }

    @Test fun detailRecordBadgeQueriesOnlyLongRuns() {
        val now = System.currentTimeMillis()
        val run = Run(title = "new 10k", startTime = now, endTime = now + 3_000_000, distanceM = 10_000.0, movingTimeS = 3000)
        val before = ms { com.example.runtrail.domain.RunCalculator.newRecordLabel(run, RunRepository.getAll()) }
        val after = ms { com.example.runtrail.domain.RunCalculator.newRecordLabel(run, RunRepository.longRunsBefore(5000.0, run.startTime)) }
        assertEquals(
            com.example.runtrail.domain.RunCalculator.newRecordLabel(run, RunRepository.getAll()),
            com.example.runtrail.domain.RunCalculator.newRecordLabel(run, RunRepository.longRunsBefore(5000.0, run.startTime)),
        )
        Log.i(TAG, "P6 detail record badge, 1000 runs: before %.1f ms, after %.1f ms".format(before, after))
        assertTrue(after < before)
    }

    @Test fun historyKeystrokeUsesMemory() {
        val all = RunRepository.getAll()
        val before = ms { RunRepository.query("Run 1", RunRepository.Filter.ALL, RunRepository.Sort.NEWEST) }
        val after = ms { RunRepository.filterAndSort(all, "Run 1", RunRepository.Filter.ALL, RunRepository.Sort.NEWEST) }
        assertEquals(
            RunRepository.query("Run 1", RunRepository.Filter.ALL, RunRepository.Sort.NEWEST).map { it.id },
            RunRepository.filterAndSort(all, "Run 1", RunRepository.Filter.ALL, RunRepository.Sort.NEWEST).map { it.id },
        )
        Log.i(TAG, "P3 history per keystroke, 1000 runs: before %.1f ms, after %.1f ms".format(before, after))
        assertTrue(after < before)
    }

    @Test fun burstOfWritesNotifiesScreensOnce() {
        val calls = AtomicInteger()
        val l: () -> Unit = { calls.incrementAndGet() }
        val main = Handler(Looper.getMainLooper())
        main.post { RunRepository.addListener(l) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val ids = RunRepository.getAll().take(100)
        ids.forEach { RunRepository.setPhotoPath(it.id, "/nope") }   // 100 writes in a row, as during sync
        Thread.sleep(600)
        val done = CountDownLatch(1)
        main.post { RunRepository.removeListener(l); done.countDown() }
        done.await(2, TimeUnit.SECONDS)
        Log.i(TAG, "P1 listener calls for 100 writes: before 100 (one post per write), after ${calls.get()}")
        assertTrue("got ${calls.get()} refreshes", calls.get() in 1..5)
    }

    @Test fun qrBitmapFill() {
        val size = 720
        val matrix = Array(size) { x -> BooleanArray(size) { y -> (x / 24 + y / 24) % 2 == 0 } }
        val before = ms {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x][y]) Color.BLACK else Color.WHITE)
            bmp.recycle()
        }
        val after = ms {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val px = IntArray(size * size)
            for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (matrix[x][y]) Color.BLACK else Color.WHITE
            bmp.setPixels(px, 0, size, 0, 0, size, size)
            bmp.recycle()
        }
        Log.i(TAG, "P4 QR 720x720 fill: before %.1f ms, after %.1f ms".format(before, after))
        assertTrue(after < before)
    }

    companion object { const val TAG = "RunTrailPerf" }
}
