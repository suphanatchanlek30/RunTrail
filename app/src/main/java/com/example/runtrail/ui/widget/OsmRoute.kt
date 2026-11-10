package com.example.runtrail.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import com.example.runtrail.BuildConfig
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.domain.PaceColor
import com.example.runtrail.domain.TrackMath
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Overlay

/**
 * แผนที่จริง OpenStreetMap (osmdroid) + เส้นทาง GPS ไล่สีตามเพซ
 * ใช้ในหน้า วิ่งสด · สรุปผล · Replay
 */
object OsmRoute {

    /** ตั้งค่าครั้งเดียวก่อนสร้าง MapView (ข้อกำหนดการใช้ tile ของ OSM) */
    fun init(context: Context) {
        val config = Configuration.getInstance()
        config.userAgentValue = "RunTrail/${BuildConfig.VERSION_NAME} (Android; CS364 student project)"
        config.osmdroidBasePath = context.cacheDir
        config.osmdroidTileCache = java.io.File(context.cacheDir, "tiles")
    }

    fun setup(map: MapView, interactive: Boolean) {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(interactive)
        map.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        map.isTilesScaledToDpi = true    // tile OSM เป็น 256 px ขยายตามความหนาแน่นจอให้อ่านง่าย
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 19.0
        map.controller.setZoom(17.0)
        map.isClickable = interactive
        map.overlays.add(CopyrightOverlay(map.context).apply { setTextSize(9) })
    }

    /*
     * ภาพแผนที่จาก tile.openstreetmap.org (ฟรี ไม่ต้องใช้ key) — ต้องตั้ง User-Agent ของแอปเอง (ทำใน init)
     * และแสดงเครดิต OpenStreetMap ตาม Tile Usage Policy · เหมาะกับงานสาธิต ถ้าผู้ใช้เยอะควรใช้ผู้ให้บริการแผนที่ของตัวเอง
     * (เคยใช้ CARTO สำหรับโหมดมืด แต่ตอนนี้ CARTO ต้องมี API key และส่งภาพลายน้ำกลับมาแทน)
     */

    /** กลับสี + ลดความสว่างเล็กน้อย ให้ถนนเป็นเส้นสว่างบนพื้นเข้ม */
    private val INVERT = android.graphics.ColorMatrixColorFilter(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 230f,
            0f, -0.85f, 0f, 0f, 230f,
            0f, 0f, -0.85f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    /** โหมดกลางคืน: กลับสีภาพแผนที่ OSM ให้เป็นโทนมืด (ไม่ต้องพึ่งผู้ให้บริการแผนที่อื่น) */
    fun setDark(map: MapView, dark: Boolean) {
        map.overlayManager.tilesOverlay.setColorFilter(if (dark) INVERT else null)
        map.invalidate()
    }

    /** ซูมให้เห็นเส้นทางทั้งหมด เว้นขอบล่างตามความสูงของ sheet */
    fun fit(map: MapView, track: List<TrackPoint>, padTopPx: Int, padBottomPx: Int) {
        if (track.isEmpty()) return
        if (track.size == 1) {
            map.controller.setCenter(GeoPoint(track[0].lat, track[0].lng)); return
        }
        val box = BoundingBox.fromGeoPoints(track.map { GeoPoint(it.lat, it.lng) })
        map.post {
            val h = map.height.toFloat().coerceAtLeast(1f)
            // ขยายกรอบลงล่างตามพื้นที่ที่ถูก sheet บัง
            val latSpan = (box.latNorth - box.latSouth).coerceAtLeast(0.0008)
            val visible = (h - padTopPx - padBottomPx).coerceAtLeast(h * 0.25f)
            val extraBottom = latSpan * padBottomPx / visible
            val extraTop = latSpan * padTopPx / visible
            val padded = BoundingBox(
                box.latNorth + extraTop + latSpan * 0.12, box.lonEast + (box.lonEast - box.lonWest) * 0.15 + 0.0002,
                box.latSouth - extraBottom - latSpan * 0.12, box.lonWest - (box.lonEast - box.lonWest) * 0.15 - 0.0002,
            )
            map.zoomToBoundingBox(padded, false)
        }
    }

    /**
     * Overlay วาดเส้นทาง — progress 0..1 (Replay: ส่วนที่ยังไม่ถึงเป็นสีเทา), showCurrent = จุดตำแหน่งปัจจุบัน
     */
    class RouteOverlay(private val density: Float) : Overlay() {
        var track: List<TrackPoint> = emptyList()
            set(value) { field = value; paces = TrackMath.paces(value, 360) }
        var progress = 1f
        var showCurrent = false
        var trimFraction = 0f
        private var paces = IntArray(0)

        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val p1 = Point()
        private val p2 = Point()

        override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
            if (shadow || track.size < 2) {
                if (!shadow && track.size == 1 && showCurrent) drawCurrent(canvas, map, track[0])
                return
            }
            val proj = map.projection
            val n = track.size
            val trim = (n * trimFraction).toInt()
            val from = trim
            val to = n - 1 - trim
            if (to <= from) return
            val reached = ((n - 1) * progress).toInt().coerceIn(from, to)
            val w = 6 * density

            // ขอบขาว
            line.strokeWidth = w + 4 * density
            line.color = Color.WHITE
            for (i in from until to) segment(canvas, proj, i, line)

            // ส่วนที่ยังไม่ถึง
            line.strokeWidth = w
            line.color = 0xFFB9C2CB.toInt()
            for (i in reached until to) segment(canvas, proj, i, line)

            // ส่วนที่วิ่งแล้ว ไล่สีตามเพซ
            for (i in from until reached) {
                line.color = PaceColor.forPace(paces[i])
                segment(canvas, proj, i, line)
            }

            marker(canvas, map, track[from], PaceColor.FAST)
            if (progress >= 1f && !showCurrent) marker(canvas, map, track[to], 0xFF0F1412.toInt())
            if (showCurrent) drawCurrent(canvas, map, track[reached])
        }

        private fun segment(canvas: Canvas, proj: org.osmdroid.views.Projection, i: Int, paint: Paint) {
            proj.toPixels(GeoPoint(track[i].lat, track[i].lng), p1)
            proj.toPixels(GeoPoint(track[i + 1].lat, track[i + 1].lng), p2)
            canvas.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), paint)
        }

        private fun marker(canvas: Canvas, map: MapView, p: TrackPoint, color: Int) {
            map.projection.toPixels(GeoPoint(p.lat, p.lng), p1)
            dot.color = Color.WHITE
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 10 * density, dot)
            dot.color = color
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 7 * density, dot)
        }

        private fun drawCurrent(canvas: Canvas, map: MapView, p: TrackPoint) {
            map.projection.toPixels(GeoPoint(p.lat, p.lng), p1)
            dot.color = 0x3300D47A
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 22 * density, dot)
            dot.color = Color.WHITE
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 11 * density, dot)
            dot.color = 0xFF0F1412.toInt()
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 8 * density, dot)
        }
    }
}
