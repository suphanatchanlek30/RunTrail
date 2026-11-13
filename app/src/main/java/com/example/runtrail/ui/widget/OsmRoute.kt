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

/** OpenStreetMap (osmdroid) helpers and a pace-coloured route overlay for the live, detail and replay screens. */
object OsmRoute {

    /** One-time osmdroid setup. Tiles come from tile.openstreetmap.org, whose policy requires an app User-Agent and visible attribution. */
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
        map.isTilesScaledToDpi = true    // scale 256 px tiles to screen density
        map.minZoomLevel = 3.0
        map.maxZoomLevel = 19.0
        map.controller.setZoom(17.0)
        map.isClickable = interactive
        map.overlays.add(CopyrightOverlay(map.context).apply { setTextSize(9) })
    }

    /** Inverts and slightly dims tiles so roads read as light lines on a dark map. */
    private val INVERT = android.graphics.ColorMatrixColorFilter(
        floatArrayOf(
            -0.85f, 0f, 0f, 0f, 230f,
            0f, -0.85f, 0f, 0f, 230f,
            0f, 0f, -0.85f, 0f, 235f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    /** Applies the dark tile filter for night mode. */
    fun setDark(map: MapView, dark: Boolean) {
        map.overlayManager.tilesOverlay.setColorFilter(if (dark) INVERT else null)
        map.invalidate()
    }

    /** Zooms to fit the whole track, leaving room at the bottom for the sheet. */
    fun fit(map: MapView, track: List<TrackPoint>, padTopPx: Int, padBottomPx: Int) {
        if (track.isEmpty()) return
        if (track.size == 1) {
            map.controller.setCenter(GeoPoint(track[0].lat, track[0].lng)); return
        }
        val box = BoundingBox.fromGeoPoints(track.map { GeoPoint(it.lat, it.lng) })
        map.post {
            val h = map.height.toFloat().coerceAtLeast(1f)
            // Extend the box downward by the area hidden behind the sheet.
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

    /** Draws a track; [progress] (0..1) greys out the unreached part during replay. */
    class RouteOverlay(private val density: Float) : Overlay() {
        var track: List<TrackPoint> = emptyList()
            set(value) { field = value; paces = TrackMath.paces(value, 360) }
        var progress = 1f
        var showCurrent = false
        /**
         * Latest device fix on the live screen. The marker is drawn here rather than at the track's last
         * point, because the track only keeps filtered points and would lag behind the camera.
         */
        var current: TrackPoint? = null
        var trimFraction = 0f
        private var paces = IntArray(0)

        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val p1 = Point()
        private val p2 = Point()

        /** Where the position marker is drawn, or null for none. */
        fun markerPoint(): TrackPoint? {
            if (!showCurrent) return null
            current?.let { return it }
            if (track.isEmpty()) return null
            if (track.size == 1) return track[0]
            val trim = (track.size * trimFraction).toInt()
            return track[((track.size - 1) * progress).toInt().coerceIn(trim, track.size - 1 - trim)]
        }

        override fun draw(canvas: Canvas, map: MapView, shadow: Boolean) {
            if (shadow || track.size < 2) {
                if (!shadow) markerPoint()?.let { drawCurrent(canvas, map, it) }
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

            // White casing.
            line.strokeWidth = w + 4 * density
            line.color = Color.WHITE
            for (i in from until to) segment(canvas, proj, i, line)

            // Not yet reached.
            line.strokeWidth = w
            line.color = 0xFFB9C2CB.toInt()
            for (i in reached until to) segment(canvas, proj, i, line)

            // Covered part, coloured by pace.
            for (i in from until reached) {
                line.color = PaceColor.forPace(paces[i])
                segment(canvas, proj, i, line)
            }

            marker(canvas, map, track[from], PaceColor.FAST)
            if (progress >= 1f && !showCurrent) marker(canvas, map, track[to], 0xFF0F1412.toInt())
            markerPoint()?.let { drawCurrent(canvas, map, it) }
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
            // Accuracy halo scaled to the GPS error, never smaller than the 22 dp marker.
            val accuracyPx = if (p.accuracy > 0f) map.projection.metersToPixels(p.accuracy) else 0f
            dot.color = 0x3300D47A
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), maxOf(22 * density, accuracyPx), dot)
            dot.color = Color.WHITE
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 11 * density, dot)
            dot.color = 0xFF0F1412.toInt()
            canvas.drawCircle(p1.x.toFloat(), p1.y.toFloat(), 8 * density, dot)
        }
    }
}
