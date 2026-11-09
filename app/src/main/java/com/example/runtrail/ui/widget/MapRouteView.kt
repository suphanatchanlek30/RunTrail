package com.example.runtrail.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.data.RoutePoint
import com.example.runtrail.domain.PaceColor
import kotlin.math.max
import kotlin.math.min

/**
 * แผนที่จำลอง + เส้นทางไล่สีตามเพซ (วาดด้วย Canvas)
 * ช่วง UI ใช้แทน osmdroid MapView เพื่อให้หน้าตาตรงกับ Figma และใช้งานได้แบบออฟไลน์
 *
 * - routeTop / routeBottom: ช่วงความสูง (0..1) ที่ใช้วางเส้นทาง เผื่อส่วนที่ถูก bottom sheet บัง
 * - progress: 0..1 สำหรับ Replay (ส่วนที่ยังไม่ถึงเป็นสีเทา) / การวิ่งสด
 */
class MapRouteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var dark = false
        set(value) { field = value; invalidate() }
    var thumbnail = false
        set(value) { field = value; invalidate() }
    var routeTop = 0.12f
        set(value) { field = value; invalidate() }
    var routeBottom = 0.92f
        set(value) { field = value; invalidate() }
    var progress = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var showStartEnd = true
        set(value) { field = value; invalidate() }
    var showCurrent = false
        set(value) { field = value; invalidate() }
    /** ซ่อนต้น/ปลายเส้นทาง (ความเป็นส่วนตัว) เป็นสัดส่วนของเส้นทาง */
    var privacyTrim = 0f
        set(value) { field = value; invalidate() }
    var seed = 1
        set(value) { field = value; invalidate() }

    private var points: List<RoutePoint> = emptyList()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val road = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dp = resources.displayMetrics.density

    fun setRoute(route: List<RoutePoint>) {
        points = route
        invalidate()
    }

    private fun c(id: Int) = ContextCompat.getColor(context, id)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        drawBaseMap(canvas, w, h)
        if (points.size > 1) drawRoute(canvas, w, h)
    }

    // ---------- พื้นแผนที่ ----------
    private fun drawBaseMap(canvas: Canvas, w: Float, h: Float) {
        canvas.drawColor(c(if (dark) R.color.map_dark_land else R.color.map_land))
        val unit = max(w, h)

        // บล็อกอาคาร
        fill.color = c(if (dark) R.color.map_dark_block else R.color.map_block)
        val cell = if (thumbnail) unit / 4f else 64f * dp
        var y = -cell * 0.3f
        var row = 0
        while (y < h) {
            var x = -cell * (0.2f + (row % 2) * 0.35f)
            while (x < w) {
                val r = RectF(x + 6 * dp, y + 6 * dp, x + cell - 6 * dp, y + cell * 0.8f - 6 * dp)
                canvas.drawRoundRect(r, 6 * dp, 6 * dp, fill)
                x += cell
            }
            y += cell * 0.8f
            row++
        }

        // สวน (พื้นที่สีเขียวรอบเส้นทาง)
        fill.color = c(if (dark) R.color.map_dark_park else R.color.map_park)
        val top = h * routeTop
        val bottom = h * routeBottom
        val park = RectF(w * 0.24f, top + (bottom - top) * 0.22f, w * 0.76f, top + (bottom - top) * 0.80f)
        canvas.drawRoundRect(park, 40 * dp, 40 * dp, fill)
        if (!thumbnail) {
            canvas.drawCircle(w * 0.08f, h * 0.18f, 30 * dp, fill)
            canvas.drawCircle(w * 0.9f, h * 0.86f, 42 * dp, fill)
        }

        // แม่น้ำ
        fill.color = c(if (dark) R.color.map_dark_water else R.color.map_water)
        road.color = fill.color
        road.strokeWidth = if (thumbnail) 7 * dp else 22 * dp
        val river = Path().apply {
            moveTo(-20 * dp, h * 0.06f)
            cubicTo(w * 0.35f, h * 0.02f, w * 0.55f, h * 0.16f, w + 20 * dp, h * 0.10f)
        }
        canvas.drawPath(river, road)

        // ถนนหลัก / ถนนรอง
        road.color = c(if (dark) R.color.map_dark_road else R.color.map_road)
        road.strokeWidth = if (thumbnail) 3 * dp else 9 * dp
        canvas.drawLine(-10f, h * 0.30f, w + 10f, h * 0.24f, road)
        canvas.drawLine(w * 0.14f, -10f, w * 0.22f, h + 10f, road)
        canvas.drawLine(w * 0.86f, -10f, w * 0.80f, h + 10f, road)
        canvas.drawLine(-10f, h * 0.70f, w + 10f, h * 0.76f, road)
        road.strokeWidth = if (thumbnail) 1.5f * dp else 4 * dp
        canvas.drawLine(w * 0.48f, -10f, w * 0.52f, h + 10f, road)
        canvas.drawLine(-10f, h * 0.50f, w + 10f, h * 0.47f, road)
        canvas.drawLine(-10f, h * 0.92f, w + 10f, h * 0.90f, road)
    }

    // ---------- เส้นทาง ----------
    private fun map(p: RoutePoint, w: Float, h: Float): Pair<Float, Float> {
        val top = h * routeTop
        val bottom = h * routeBottom
        val areaH = bottom - top
        val size = min(w * 0.96f, areaH * 1.05f)
        val left = (w - size) / 2f
        return (left + p.x * size) to (top + (areaH - size) / 2f + p.y * size)
    }

    private fun drawRoute(canvas: Canvas, w: Float, h: Float) {
        val n = points.size
        val trimStart = (privacyTrim * n).toInt()
        val trimEnd = n - 1 - (privacyTrim * n).toInt()
        val reached = ((n - 1) * progress).toInt()
        val width = if (thumbnail) 3f * dp else 6f * dp

        // เส้นขอบขาวให้เส้นทางเด่นจากพื้นแผนที่
        line.strokeWidth = width + (if (thumbnail) 2f else 4f) * dp
        line.color = if (dark) 0x66000000 else Color.WHITE
        val outline = Path()
        for (i in trimStart..trimEnd) {
            val (x, y) = map(points[i], w, h)
            if (i == trimStart) outline.moveTo(x, y) else outline.lineTo(x, y)
        }
        canvas.drawPath(outline, line)

        // ส่วนที่ยังไม่ถึง (Replay) สีเทา
        line.strokeWidth = width
        if (reached < trimEnd) {
            line.color = if (dark) 0xFF3A4540.toInt() else 0xFFC4CCD4.toInt()
            val rest = Path()
            for (i in max(reached, trimStart)..trimEnd) {
                val (x, y) = map(points[i], w, h)
                if (i == max(reached, trimStart)) rest.moveTo(x, y) else rest.lineTo(x, y)
            }
            canvas.drawPath(rest, line)
        }

        // ส่วนที่วิ่งแล้ว ไล่สีตามเพซ
        var i = trimStart
        while (i < min(reached, trimEnd)) {
            val color = PaceColor.forPace(points[i].paceSecPerKm)
            val seg = Path()
            val (sx, sy) = map(points[i], w, h)
            seg.moveTo(sx, sy)
            var j = i + 1
            while (j <= min(reached, trimEnd)) {
                val (x, y) = map(points[j], w, h)
                seg.lineTo(x, y)
                if (PaceColor.forPace(points[j].paceSecPerKm) != color) break
                j++
            }
            line.color = color
            canvas.drawPath(seg, line)
            i = j
        }

        val ringW = if (thumbnail) 1.5f * dp else 3f * dp
        if (showStartEnd) {
            val (sx, sy) = map(points[trimStart], w, h)
            dot(canvas, sx, sy, if (thumbnail) 3.5f * dp else 7f * dp, PaceColor.FAST, ringW)
            if (progress >= 1f && !showCurrent) {
                val (ex, ey) = map(points[trimEnd], w, h)
                dot(canvas, ex, ey, if (thumbnail) 3.5f * dp else 7f * dp, c(R.color.run_ink), ringW)
            }
        }
        if (showCurrent) {
            val (cx, cy) = map(points[reached.coerceIn(0, n - 1)], w, h)
            fill.color = 0x3300D47A
            canvas.drawCircle(cx, cy, 22 * dp, fill)
            dot(canvas, cx, cy, 8 * dp, c(R.color.run_ink), 3 * dp)
        }
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, ring: Float) {
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, r + ring, fill)
        fill.color = color
        canvas.drawCircle(x, y, r, fill)
    }

    /** ตำแหน่งปัจจุบันบนจอ (ใช้วางป้ายบน Replay) */
    fun currentPoint(): RoutePoint? =
        if (points.isEmpty()) null else points[((points.size - 1) * progress).toInt()]
}
