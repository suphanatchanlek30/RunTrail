package com.example.runtrail.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.example.runtrail.R
import com.example.runtrail.data.Run
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.PaceColor
import com.example.runtrail.domain.RouteGenerator
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.domain.ShareImageLayout
import com.example.runtrail.domain.ShareImageLayout.Background
import com.example.runtrail.domain.ShareImageLayout.Format
import kotlin.math.max

/** Draws the share image: photo or dark background, pace-coloured route, stats and logo. */
class ShareImageRenderer(private val context: Context) {

    private val bold: Typeface = font(R.font.anuphan_bold)
    private val semi: Typeface = font(R.font.anuphan_semibold)
    private val body: Typeface = font(R.font.plex_medium)

    private fun font(id: Int): Typeface = ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT_BOLD

    private val ink = 0xFF0F1412.toInt()
    private val lime = 0xFFA3F51F.toInt()
    private val green = 0xFF00D47A.toInt()
    private val muted = 0xFFB8C0C8.toInt()

    /**
     * @param run should include its track for a detailed route
     * @param photo background photo, or null for the dark background
     */
    fun render(run: Run, photo: Bitmap?, format: Format, miles: Boolean): Bitmap {
        val bg = if (photo != null) Background.PHOTO else Background.ROUTE
        val w = format.width
        val h = format.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        if (photo != null) drawPhoto(c, photo, w, h) else drawDarkBackground(c, w, h)
        drawRoute(c, run, ShareImageLayout.routeBox(format, bg), bg)
        drawLogo(c, format, bg)
        drawStats(c, run, format, miles)
        return out
    }

    private fun drawDarkBackground(c: Canvas, w: Int, h: Int) {
        c.drawColor(ink)
        val grid = Paint().apply { color = 0xFF18201D.toInt(); strokeWidth = 2f }
        var x = 0f
        while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), grid); x += 90f }
        var y = 0f
        while (y < h) { c.drawLine(0f, y, w.toFloat(), y, grid); y += 90f }
    }

    /** Center-crops the photo and adds top/bottom gradients so text stays readable. */
    private fun drawPhoto(c: Canvas, photo: Bitmap, w: Int, h: Int) {
        val scale = max(w / photo.width.toFloat(), h / photo.height.toFloat())
        val sw = (w / scale).toInt()
        val sh = (h / scale).toInt()
        val sx = (photo.width - sw) / 2
        val sy = (photo.height - sh) / 2
        c.drawBitmap(photo, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val top = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h * 0.22f, 0xA6000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h * 0.22f, top)
        val bottom = Paint().apply {
            shader = LinearGradient(0f, h * 0.42f, 0f, h.toFloat(), 0x00000000, 0xE6000000.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, h * 0.42f, w.toFloat(), h.toFloat(), bottom)
    }

    private fun drawRoute(c: Canvas, run: Run, box: ShareImageLayout.Box, bg: Background) {
        val route = RouteGenerator.route(run)
        if (route.size < 2) return
        val pts = ShareImageLayout.fitRoute(route, box)
        val big = bg == Background.ROUTE
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        // White casing so the route stands out on any background.
        line.strokeWidth = if (big) 22f else 15f
        line.color = if (big) 0x33FFFFFF else Color.WHITE
        for (i in 1 until pts.size) c.drawLine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, line)
        line.strokeWidth = if (big) 12f else 8f
        for (i in 1 until pts.size) {
            line.color = PaceColor.forPace(route[i].paceSecPerKm)
            c.drawLine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, line)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = if (big) 16f else 11f
        dot.color = Color.WHITE
        c.drawCircle(pts.first().first, pts.first().second, r + 5, dot)
        c.drawCircle(pts.last().first, pts.last().second, r + 5, dot)
        dot.color = green
        c.drawCircle(pts.first().first, pts.first().second, r, dot)
        dot.color = ink
        c.drawCircle(pts.last().first, pts.last().second, r, dot)
    }

    private fun drawLogo(c: Canvas, f: Format, bg: Background) {
        val m = ShareImageLayout.margin(f)
        val top = if (f == Format.STORY) 120f else 70f
        val size = 76f
        // Lime badge on the dark background (black would disappear), black on photos.
        val onDark = bg == Background.ROUTE
        val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (onDark) lime else ink }
        c.drawRoundRect(RectF(m, top, m + size, top + size), 22f, 22f, badge)
        text(c, "R", m + size / 2, top + size * 0.72f, 50f, bold, if (onDark) ink else lime, Paint.Align.CENTER)
        text(c, "RunTrail", m + size + 22f, top + size * 0.72f, 46f, bold, Color.WHITE)
    }

    private fun drawStats(c: Canvas, run: Run, f: Format, miles: Boolean) {
        val m = ShareImageLayout.margin(f)
        val w = f.width.toFloat()
        val story = f == Format.STORY
        var y = f.height - (if (story) 170f else 70f)   // baseline of the bottom row

        // Stats row: time · pace · calories.
        val pace = RunCalculator.pace(run)
        val cols = listOf(
            Fmt.duration(run.movingTimeS) to context.getString(R.string.label_time),
            (if (pace > 0) Fmt.pace(pace, miles) else "--:--") to context.getString(R.string.share_image_pace, Fmt.paceUnit(miles)),
            Fmt.number(run.calories) to context.getString(R.string.unit_kcal),
        )
        val colW = (w - 2 * m) / 3
        cols.forEachIndexed { i, (v, label) ->
            val x = m + i * colW
            text(c, label, x, y, 34f, body, muted)
            text(c, v, x, y - 50f, 66f, bold, Color.WHITE)
        }
        y -= if (story) 170f else 165f

        // Distance, large.
        val big = Fmt.distance(run.distanceM, miles)
        val bigSize = if (story) 230f else 180f
        val bigPaint = paint(bigSize, bold, lime)
        c.drawText(big, m - 6f, y, bigPaint)
        text(c, Fmt.unit(miles), m + bigPaint.measureText(big) + 18f, y, 64f, semi, Color.WHITE)
        y -= bigSize * 0.92f

        // Title and date.
        text(c, Fmt.detailDate(run.startTime, run.endTime), m, y, 36f, body, muted)
        y -= 62f
        text(c, ellipsize(run.title.ifBlank { context.getString(R.string.title_morning_run) }, w - 2 * m, 58f), m, y, 58f, bold, Color.WHITE)
    }

    private fun ellipsize(s: String, maxWidth: Float, size: Float): String {
        val p = paint(size, bold, Color.WHITE)
        if (p.measureText(s) <= maxWidth) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }

    private fun paint(size: Float, tf: Typeface, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; typeface = tf; this.color = color
        setShadowLayer(8f, 0f, 2f, 0x66000000)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, tf: Typeface, color: Int,
                     align: Paint.Align = Paint.Align.LEFT) {
        c.drawText(s, x, y, paint(size, tf, color).apply { textAlign = align })
    }
}
