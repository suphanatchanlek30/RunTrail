package com.example.runtrail

import android.Manifest
import android.graphics.Point
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.runtrail.data.RunDb
import com.example.runtrail.data.RunRepository
import com.example.runtrail.domain.TrackMath
import com.example.runtrail.service.RunTrackingService
import com.example.runtrail.ui.live.LiveRunActivity
import com.example.runtrail.ui.widget.OsmRoute
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Simulated walk with realistic GPS: the marker must follow every fix and the map must follow the marker.
 *
 * Walk at 1.4 m/s around a block with turns; noise σ = half the reported accuracy (5–15 m);
 * two 35 m outliers and a 5 s signal gap. Measures what is actually drawn (RouteOverlay.markerPoint).
 */
@RunWith(AndroidJUnit4::class)
class LiveMarkerFollowTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val mLat = 111_194.93
    private val mLng = 111_194.93 * cos(Math.toRadians(13.73))

    @Before fun setUp() {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
            .forEach { inst.uiAutomation.grantRuntimePermission(ctx.packageName, it) }
        RunRepository.init(ctx, RunDb(ctx, null))
        RunRepository.currentUser = { null }
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} android:mock_location allow").close()
        Thread.sleep(300)
        @Suppress("DEPRECATION")
        lm.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true,
            ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE)
        lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    @After fun tearDown() {
        inst.runOnMainSync { RunTrackingService.current?.discard() }
        try { lm.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        inst.uiAutomation.executeShellCommand("appops set ${ctx.packageName} android:mock_location default").close()
    }

    /** True position at [d] metres along the 120 × 80 m loop. */
    private fun truth(d: Double): Pair<Double, Double> {
        val corners = listOf(0.0 to 0.0, 120.0 to 0.0, 120.0 to 80.0, 0.0 to 80.0, 0.0 to 0.0)   // (north, east) metres
        var left = d % 400.0
        for (i in 1 until corners.size) {
            val (n0, e0) = corners[i - 1]; val (n1, e1) = corners[i]
            val len = hypot(n1 - n0, e1 - e0)
            if (left <= len) { val f = left / len; return 13.7290 + (n0 + (n1 - n0) * f) / mLat to 100.5385 + (e0 + (e1 - e0) * f) / mLng }
            left -= len
        }
        return 13.7290 to 100.5385
    }

    private fun meters(aLat: Double, aLng: Double, bLat: Double, bLng: Double) = TrackMath.distance(aLat, aLng, bLat, bLng)

    @Test fun markerAndMapFollowARealisticWalk() {
        val rnd = Random(42)
        val seconds = 90
        val walkSpeed = 1.4
        val lagToFix = mutableListOf<Double>()       // marker ↔ latest smoothed fix
        val lagToTruth = mutableListOf<Double>()     // marker ↔ true position
        val offFocusDp = mutableListOf<Double>()     // marker ↔ map focus point (dp)
        var pausedSeconds = 0
        var distanceRatio = 0.0

        ActivityScenario.launch(LiveRunActivity::class.java).use { s ->
            var map: MapView? = null
            s.onActivity { map = it.findViewById(R.id.map) }
            val end = SystemClock.uptimeMillis() + 10_000
            while (RunTrackingService.current == null && SystemClock.uptimeMillis() < end) Thread.sleep(200)
            val overlay = map!!.overlays.filterIsInstance<OsmRoute.RouteOverlay>().single()
            val density = ctx.resources.displayMetrics.density

            for (i in 0 until seconds) {
                val (tLat, tLng) = truth(i * walkSpeed)
                val dropout = i in 62..66
                if (!dropout) {
                    val outlier = i == 30 || i == 55
                    val acc = if (outlier) 40.0 else 5.0 + 10.0 * abs(sin(i / 7.0))
                    val dn = if (outlier) 35.0 else rnd.nextGaussian() * acc / 2
                    val de = if (outlier) 0.0 else rnd.nextGaussian() * acc / 2
                    val loc = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = tLat + dn / mLat; longitude = tLng + de / mLng; altitude = 5.0
                        accuracy = acc.toFloat(); speed = (1.4 + rnd.nextGaussian() * 0.2).toFloat().coerceAtLeast(0f)
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
                }
                Thread.sleep(1_000)
                inst.runOnMainSync {
                    val m = overlay.markerPoint() ?: return@runOnMainSync
                    val fix = RunTrackingService.current?.state?.lastFix ?: return@runOnMainSync
                    lagToFix += meters(m.lat, m.lng, fix.lat, fix.lng)
                    lagToTruth += meters(m.lat, m.lng, tLat, tLng)
                    val mv = map!!
                    val px = Point(); mv.projection.toPixels(GeoPoint(m.lat, m.lng), px)
                    val fx = mv.width / 2.0 + mv.mapCenterOffsetX
                    val fy = mv.height / 2.0 + mv.mapCenterOffsetY
                    offFocusDp += hypot(px.x - fx, px.y - fy) / density
                    if (RunTrackingService.current?.state?.paused == true) pausedSeconds++
                }
            }
            inst.runOnMainSync { distanceRatio = (RunTrackingService.current?.state?.distanceM ?: 0.0) / (seconds * walkSpeed) }
        }

        fun pct(list: List<Double>, p: Double) = list.sorted()[((list.size - 1) * p).toInt()]
        val within1m = lagToFix.count { it < 1.0 } * 100 / lagToFix.size
        Log.i(TAG, "samples=${lagToFix.size} · marker==latest fix: $within1m% (median ${"%.1f".format(pct(lagToFix, .5))} m, p95 ${"%.1f".format(pct(lagToFix, .95))} m)" +
            " · marker↔truth median ${"%.1f".format(pct(lagToTruth, .5))} m p95 ${"%.1f".format(pct(lagToTruth, .95))} m" +
            " · distance recorded/true ${"%.2f".format(distanceRatio)}" +
            " · marker↔map focus median ${"%.0f".format(pct(offFocusDp, .5))} dp p95 ${"%.0f".format(pct(offFocusDp, .95))} dp · paused ${pausedSeconds}s")

        assertTrue("collected samples", lagToFix.size >= seconds - 10)
        // 1) The marker sits on the latest fix every time.
        assertTrue("marker follows every fix: $within1m% within 1 m", within1m >= 95)
        // 5) Recorded distance stays close to the truth despite per-second noise.
        assertTrue("distance ratio $distanceRatio", distanceRatio in 0.85..1.2)
        // 2) The map keeps the marker near its focus point (above the sheet).
        assertTrue("map keeps the marker in focus (p95 ${pct(offFocusDp, .95)} dp)", pct(offFocusDp, .95) < 48)
        // 3) The marker stays within GPS error of the walker (outliers aside).
        assertTrue("marker near the walker (median ${pct(lagToTruth, .5)} m)", pct(lagToTruth, .5) < 12)
        // 4) Continuous walking is never auto-paused (the 5 s gap is below the 8 s threshold).
        assertTrue("no auto-pause while walking ($pausedSeconds s)", pausedSeconds == 0)
    }

    companion object { const val TAG = "RunTrailFollow" }
}
