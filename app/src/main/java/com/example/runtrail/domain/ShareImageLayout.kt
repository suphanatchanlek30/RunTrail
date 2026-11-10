package com.example.runtrail.domain

import com.example.runtrail.data.RoutePoint
import kotlin.math.min

/**
 * ตำแหน่งต่าง ๆ บนภาพแชร์ (Kotlin ล้วน ทดสอบบน JVM ได้) — ตัววาดจริงอยู่ที่ ui/share/ShareImageRenderer
 */
object ShareImageLayout {

    enum class Format(val width: Int, val height: Int) {
        STORY(1080, 1920),   // 9:16 IG Story / สถานะ LINE
        SQUARE(1080, 1080),  // 1:1 โพสต์
    }

    enum class Background { PHOTO, ROUTE }

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    }

    /** ขอบซ้าย/ขวาของเนื้อหา */
    fun margin(f: Format) = if (f == Format.STORY) 90f else 64f

    /**
     * พื้นที่วาดเส้นทาง
     * - พื้นเข้ม: เส้นทางเป็นพระเอก อยู่กลางภาพส่วนบน
     * - รูปถ่าย: เส้นทางเล็ก ๆ มุมขวาบน ไม่บังรูป
     */
    fun routeBox(f: Format, bg: Background): Box {
        val m = margin(f)
        val w = f.width.toFloat()
        return when {
            bg == Background.ROUTE && f == Format.STORY -> Box(m, 300f, w - m, 1180f)
            bg == Background.ROUTE -> Box(m, 170f, w - m, 540f)
            f == Format.STORY -> Box(w - m - 330f, 230f, w - m, 560f)
            else -> Box(w - m - 250f, 140f, w - m, 390f)
        }
    }

    /** แปลงจุดเส้นทาง (0..1 รักษาสัดส่วนแล้ว) ให้อยู่กลางกล่อง โดยไม่ยืด */
    fun fitRoute(points: List<RoutePoint>, box: Box): List<Pair<Float, Float>> {
        if (points.isEmpty()) return emptyList()
        val side = min(box.width, box.height)
        val ox = box.left + (box.width - side) / 2
        val oy = box.top + (box.height - side) / 2
        return points.map { p -> (ox + p.x.coerceIn(0f, 1f) * side) to (oy + p.y.coerceIn(0f, 1f) * side) }
    }
}
