package com.example.runtrail

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunSource
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.domain.ShareImageLayout.Format
import com.example.runtrail.ui.share.ShareImageRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** วาดภาพแชร์จริงทั้ง 4 แบบ (รูป/เส้นทาง × 9:16/1:1) และเก็บไฟล์ไว้ให้เปิดดูได้ */
@RunWith(AndroidJUnit4::class)
class ShareImageRendererTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun run(): Run {
        val start = 1_762_772_530_000L
        val track = List(1700) { i ->
            val a = i / 1700.0 * 2 * Math.PI
            TrackPoint(13.730 + 0.004 * Math.sin(a), 100.541 + 0.006 * Math.cos(a), start + i * 1000L, 5.0, 5f)
        }
        return Run(title = "วิ่งเย็นรอบสวนลุมพินี", startTime = start, endTime = start + 1_700_000, distanceM = 5020.0,
            movingTimeS = 1700, calories = 342.0, track = track)
    }

    private fun photo() = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 200)) }

    private fun hasLime(b: Bitmap): Boolean {
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) {
            val p = b.getPixel(x, y)
            if (Color.red(p) in 150..175 && Color.green(p) > 235 && Color.blue(p) < 60) return true
        }
        return false
    }

    @Test fun rendersAllFourVariants() {
        val out = File(ctx.getExternalFilesDir(null), "share-test").apply { mkdirs() }
        val r = ShareImageRenderer(ctx)
        for (f in Format.entries) for (withPhoto in listOf(true, false)) {
            val b = r.render(run(), if (withPhoto) photo() else null, f, miles = false)
            assertEquals(f.width, b.width)
            assertEquals(f.height, b.height)
            assertTrue("big distance text drawn in lime ($f, photo=$withPhoto)", hasLime(b))
            File(out, "${f.name.lowercase()}_${if (withPhoto) "photo" else "route"}.png").outputStream()
                .use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun manualRunWithoutRouteStillRenders() {
        val manual = Run(title = "เพิ่มเอง", startTime = 1_762_772_530_000L, distanceM = 3000.0, movingTimeS = 1100,
            source = RunSource.MANUAL)
        val b = ShareImageRenderer(ctx).render(manual, null, Format.SQUARE, miles = true)
        assertEquals(1080, b.width)
    }
}
