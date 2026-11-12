package com.example.runtrail.domain

import com.example.runtrail.sensor.LocationTracker
import com.example.runtrail.ui.common.ImageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ตรรกะที่เคยทำให้เด้งบนเครื่องบางรุ่น (AUDIT A3, A4) — ส่วนที่ไม่ต้องใช้ฮาร์ดแวร์จริง */
class DeviceLogicTest {

    @Test fun prefersGpsThenFusedThenNetwork() {
        assertEquals("gps", LocationTracker.pickProvider(listOf("passive", "network", "gps", "fused")))
        assertEquals("fused", LocationTracker.pickProvider(listOf("passive", "network", "fused")))
        assertEquals("network", LocationTracker.pickProvider(listOf("passive", "network")))
        assertNull(LocationTracker.pickProvider(listOf("passive")))
    }

    @Test fun sampleSizeKeepsAtLeastTargetResolution() {
        assertEquals(1, ImageStore.sampleSize(1280, 960))
        assertEquals(1, ImageStore.sampleSize(2000, 1500))
        assertEquals(2, ImageStore.sampleSize(4000, 3000))
        assertEquals(4, ImageStore.sampleSize(8160, 6120))     // 50 MP
        // ด้านยาวหลัง sample ต้องยังไม่ต่ำกว่า 1280 (ไม่เสียความคม) และไม่เกิน 2 เท่า (ไม่เปลืองหน่วยความจำ)
        for (w in listOf(1300, 2600, 3000, 5000, 9000, 12000)) {
            val longest = w / ImageStore.sampleSize(w, w / 2)
            assertTrue("$w → $longest", longest >= ImageStore.MAX_SIDE && longest < ImageStore.MAX_SIDE * 2)
        }
    }
}
