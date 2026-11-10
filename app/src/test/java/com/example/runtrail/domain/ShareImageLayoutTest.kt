package com.example.runtrail.domain

import com.example.runtrail.data.RoutePoint
import com.example.runtrail.domain.ShareImageLayout.Background
import com.example.runtrail.domain.ShareImageLayout.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareImageLayoutTest {

    @Test fun formatsAreStoryAndSquare() {
        assertEquals(9.0 / 16, Format.STORY.width.toDouble() / Format.STORY.height, 1e-9)
        assertEquals(Format.SQUARE.width, Format.SQUARE.height)
    }

    @Test fun routeBoxesFitInsideTheImage() {
        for (f in Format.entries) for (bg in Background.entries) {
            val b = ShareImageLayout.routeBox(f, bg)
            assertTrue("$f/$bg", b.left >= 0 && b.top >= 0 && b.right <= f.width && b.bottom <= f.height && b.width > 100)
        }
    }

    @Test fun routeIsCenteredWithoutStretching() {
        val box = ShareImageLayout.Box(100f, 200f, 900f, 600f)   // กว้างกว่าสูง
        val pts = listOf(RoutePoint(0f, 0f, 300, 0f), RoutePoint(1f, 1f, 300, 0f), RoutePoint(0.5f, 0.5f, 300, 0f))
        val out = ShareImageLayout.fitRoute(pts, box)
        out.forEach { (x, y) -> assertTrue(box.contains(x, y)) }
        assertEquals(400f, out[1].first - out[0].first, 0.01f)   // ใช้ด้านสั้น 400 ทั้งสองแกน
        assertEquals(400f, out[1].second - out[0].second, 0.01f)
        assertEquals(500f, out[2].first, 0.01f)                   // กึ่งกลางกล่อง
    }
}
