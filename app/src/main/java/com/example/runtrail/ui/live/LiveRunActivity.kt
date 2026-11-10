package com.example.runtrail.ui.live

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.databinding.ActivityLiveRunBinding
import com.example.runtrail.databinding.ViewStatBinding
import com.example.runtrail.databinding.ViewTileBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.domain.TrackMath
import com.example.runtrail.sensor.CompassHelper
import com.example.runtrail.service.RunTrackingService
import com.example.runtrail.ui.common.ImageStore
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.widget.OsmRoute
import org.osmdroid.util.GeoPoint
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 07 · Live Run / 08 · Paused — หน้าจอแสดงผลของการวิ่งสด
 *
 * ตัวบันทึกจริงคือ [RunTrackingService] (foreground service) จึงปิดจอ สลับแอป หรือออกจากหน้านี้ได้
 * โดย GPS และเวลายังเดินต่อ หน้านี้แค่อ่านค่ามาแสดง และสั่ง หยุด / วิ่งต่อ / จบ / ถ่ายรูป
 */
class LiveRunActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveRunBinding
    private lateinit var prefs: PrefsHelper
    private lateinit var compass: CompassHelper
    private lateinit var overlay: OsmRoute.RouteOverlay
    private val handler = Handler(Looper.getMainLooper())
    private var service: RunTrackingService? = null
    private var locked = false
    private var mapDark = false
    private var followUntil = 0L
    private var shownPaused: Boolean? = null
    private var drawnPoints = -1
    private var pendingPhoto: File? = null
    private var startRequested = false

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pendingPhoto
        pendingPhoto = null
        if (ok && file != null) {
            // ย่อ + แก้ทิศตาม EXIF ก่อนเก็บ แล้วลบไฟล์ชั่วคราว
            val path = ImageStore.saveFromUri(this, Uri.fromFile(file))
            file.delete()
            if (path != null) {
                service?.photoPath = path
                toast(getString(R.string.live_photo_saved))
            }
        } else file?.delete()
    }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasLocation()) startService() else {
            toast(getString(R.string.perm_location_denied))
            renderGpsChip()
        }
    }

    /** ต่อกับ service ทันทีที่มันพร้อม (start ทำงานแบบ async) */
    private val attachLoop = object : Runnable {
        override fun run() {
            if (service == null) attach()
            if (service == null) handler.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmRoute.init(this)
        setupEdgeToEdge()
        binding = ActivityLiveRunBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsHelper(this)
        mapDark = savedInstanceState?.getBoolean(KEY_DARK) ?: false
        pendingPhoto = savedInstanceState?.getString(KEY_PENDING_PHOTO)?.let { File(it) }
        binding.overlay.applySystemBarsPadding(top = true)
        binding.sheetRunning.applySystemBarsPadding(top = false, bottom = true)
        binding.sheetPaused.applySystemBarsPadding(top = false, bottom = true)

        OsmRoute.setup(binding.map, interactive = true)
        overlay = OsmRoute.RouteOverlay(resources.displayMetrics.density).apply { showCurrent = true }
        binding.map.overlays.add(overlay)
        binding.map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) followUntil = System.currentTimeMillis() + 10_000
            false
        }
        binding.map.controller.setCenter(GeoPoint(13.7563, 100.5018))   // ก่อนได้ตำแหน่ง: กรุงเทพฯ
        setupTiles()
        binding.map.setDarkIfNeeded()

        compass = CompassHelper(this) { deg ->
            binding.ivCompass.rotation = -deg.toFloat()
            binding.tvHeading.text = CompassHelper.label(deg)
        }
        if (!compass.available) binding.tvHeading.text = getString(R.string.live_no_compass)

        binding.btnPause.setOnClickListener { if (!locked) service?.pause() }
        binding.btnResume.setOnClickListener { service?.resume() }
        binding.btnHoldStop.onCompleted = { finishRun() }
        binding.btnLayers.setOnClickListener { mapDark = !mapDark; binding.map.setDarkIfNeeded() }
        binding.btnLocate.setOnClickListener {
            followUntil = 0
            currentPosition()?.let { binding.map.controller.animateTo(GeoPoint(it.lat, it.lng), 17.5, 400L) }
        }
        binding.tvStatusChip.setOnClickListener {
            when {
                !hasLocation() -> requestLocation()
                service?.gpsEnabled == false -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        binding.btnCamera.setOnClickListener { if (!locked) openCamera() }
        binding.btnLock.setOnClickListener {
            if (!locked) { setLocked(true); toast(getString(R.string.live_locked)) }
            else toast(getString(R.string.live_unlock_hint))
        }
        binding.btnLock.setOnLongClickListener {
            if (locked) { setLocked(false); toast(getString(R.string.live_unlocked)) }
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val s = service
                when {
                    locked -> toast(getString(R.string.live_unlock_hint))
                    s != null && !s.state.paused -> s.pause()
                    else -> {
                        if (s != null) toast(getString(R.string.live_still_recording))
                        finish()
                    }
                }
            }
        })

        if (RunTrackingService.isRunning) attach()
        else if (savedInstanceState == null || !savedInstanceState.getBoolean(KEY_STARTED)) {
            if (hasLocation()) startService() else requestLocation()
        }
        render()
        renderGpsChip()
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        askLocation.launch(perms.toTypedArray())
    }

    private fun startService() {
        if (RunTrackingService.isRunning) { attach(); return }
        startRequested = true
        RunTrackingService.start(this, resume = intent.getBooleanExtra(EXTRA_RESUME, false))
        handler.post(attachLoop)
    }

    private fun attach() {
        val s = RunTrackingService.current ?: return
        service = s
        s.listener = { if (!isFinishing) render() }
        drawnPoints = -1
        s.state.lastFix?.let { binding.map.controller.setCenter(GeoPoint(it.lat, it.lng)) }
            ?: s.lastKnown?.let { binding.map.controller.setCenter(GeoPoint(it.latitude, it.longitude)) }
        render()
    }

    private fun currentPosition(): TrackPoint? {
        val s = service ?: return null
        return s.state.lastFix ?: s.lastKnown?.let { TrackPoint(it.latitude, it.longitude, 0, 0.0, 0f) }
    }

    /** ใช้กล้องแบบเต็มความละเอียด (เดิมใช้ภาพ preview เล็ก ๆ) ผ่าน FileProvider */
    private fun openCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "live_${System.currentTimeMillis()}.jpg")
        pendingPhoto = file
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        try {
            takePhoto.launch(uri)
        } catch (e: android.content.ActivityNotFoundException) {
            pendingPhoto = null
            toast(getString(R.string.live_no_camera))
        }
    }

    private fun org.osmdroid.views.MapView.setDarkIfNeeded() {
        OsmRoute.setDark(this, mapDark)
        binding.topFade.visibility = if (mapDark) View.INVISIBLE else View.VISIBLE
    }

    /** ให้ตำแหน่งนักวิ่งอยู่กลางพื้นที่แผนที่ที่มองเห็น (เหนือ bottom sheet) */
    private fun updateMapOffset(paused: Boolean) {
        val sheet = if (paused) binding.sheetPaused else binding.sheetRunning
        sheet.post {
            val hidden = sheet.height + resources.displayMetrics.density * 24
            binding.map.setMapCenterOffset(0, -(hidden / 2).toInt())
            currentPosition()?.let { binding.map.controller.setCenter(GeoPoint(it.lat, it.lng)) }
        }
    }

    private fun setupTiles() {
        fun tile(t: ViewTileBinding, icon: Int, label: Int, unit: String) {
            t.ivIcon.setImageResource(icon); t.tvLabel.setText(label); t.tvUnit.text = unit
        }
        val miles = prefs.useMiles
        tile(binding.tileCurrentPace, R.drawable.ic_gauge, R.string.label_current_pace, Fmt.paceUnit(miles))
        tile(binding.tileAvgPace, R.drawable.ic_activity, R.string.label_avg_pace, Fmt.paceUnit(miles))
        tile(binding.tileCalories, R.drawable.ic_flame, R.string.label_calories, getString(R.string.unit_kcal))
        tile(binding.tileCadence, R.drawable.ic_zap, R.string.label_cadence, getString(R.string.unit_spm))
        binding.tvDistanceUnit.text = Fmt.unit(miles)
        binding.chipElevation.setStartIcon(R.drawable.ic_mountain, 14f, R.color.text_tertiary)
        binding.chipTemp.setStartIcon(R.drawable.ic_thermo, 14f, R.color.text_tertiary)
        binding.chipBattery.setStartIcon(R.drawable.ic_battery, 14f, R.color.text_tertiary)

        fun stat(s: ViewStatBinding, label: Int, unit: String) {
            s.tvLabel.setText(label); s.tvUnit.text = unit
            s.tvUnit.visibility = if (unit.isEmpty()) View.GONE else View.VISIBLE
        }
        stat(binding.pausedDistance, R.string.label_distance, Fmt.unit(miles))
        stat(binding.pausedTime, R.string.label_time, "")
        stat(binding.pausedPace, R.string.label_avg_pace, Fmt.paceUnit(miles))
    }

    private fun render() {
        val s = service
        val st = s?.state
        val paused = st?.paused ?: false
        if (shownPaused != paused) {
            shownPaused = paused
            binding.sheetRunning.visibility = if (paused) View.GONE else View.VISIBLE
            binding.sheetPaused.visibility = if (paused) View.VISIBLE else View.GONE
            binding.tvPaceBanner.visibility = if (paused) View.GONE else View.VISIBLE
            binding.compass.visibility = if (paused) View.GONE else View.VISIBLE
            binding.btnHoldStop.reset()
            updateMapOffset(paused)
        }
        if (paused) {
            binding.tvPausedReason.text = getString(
                if (st?.autoPaused == true) R.string.live_paused_auto_reason else R.string.live_paused_manual_reason,
            )
        }

        val miles = prefs.useMiles
        val distance = st?.distanceM ?: 0.0
        val moving = st?.movingS ?: 0L
        val avg = RunCalculator.paceSecPerKm(moving, distance)
        val now = st?.currentPace() ?: 0.0
        val cadence = s?.liveCadence() ?: 0

        binding.tvDistance.text = Fmt.distance(distance, miles)
        binding.tvElapsed.text = Fmt.duration(moving)
        binding.tileCurrentPace.tvValue.text = if (now > 0) Fmt.pace(now, miles) else "--:--"
        binding.tileAvgPace.tvValue.text = if (distance >= 20) Fmt.pace(avg, miles) else "--:--"
        binding.tileCalories.tvValue.text = Fmt.number(s?.calories() ?: 0.0)
        binding.tileCadence.tvValue.text = if (cadence > 0) cadence.toString() else "--"
        binding.chipElevation.text = getString(R.string.live_elevation, st?.let { TrackMath.elevationGain(it.track) } ?: 0)
        val temp = s?.tempC
        binding.chipTemp.visibility = if (temp != null) View.VISIBLE else View.GONE
        temp?.let { binding.chipTemp.text = getString(R.string.live_temp, it.roundToInt()) }
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        binding.chipBattery.text = getString(R.string.live_battery, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))

        binding.pausedDistance.tvValue.text = Fmt.distance(distance, miles)
        binding.pausedTime.tvValue.text = Fmt.duration(moving)
        binding.pausedPace.tvValue.text = if (distance >= 20) Fmt.pace(avg, miles) else "--:--"

        // ป้ายเทียบเพซเป้าหมาย
        val target = prefs.targetPaceSec
        if (now > 0) {
            val diff = target - now.roundToInt()
            binding.tvPaceBanner.text = if (diff >= 0) getString(R.string.live_faster, diff, Fmt.pace(target))
            else getString(R.string.live_slower, abs(diff), Fmt.pace(target))
            binding.tvPaceBanner.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (diff >= 0) R.drawable.dot_lime else R.drawable.dot_moderate, 0, 0, 0,
            )
        } else {
            binding.tvPaceBanner.text = getString(R.string.live_target, Fmt.pace(target))
            binding.tvPaceBanner.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.dot_lime, 0, 0, 0)
        }

        // เส้นทางบนแผนที่ (วาดใหม่เฉพาะเมื่อมีจุดเพิ่ม)
        if (st != null) {
            val fix = st.lastFix
            if (st.track.size != drawnPoints) {
                drawnPoints = st.track.size
                overlay.track = st.track.toList().ifEmpty { listOfNotNull(fix) }
            }
            if (fix != null && System.currentTimeMillis() > followUntil) {
                binding.map.controller.animateTo(GeoPoint(fix.lat, fix.lng))
            }
            binding.map.invalidate()
        }
        renderGpsChip()
    }

    /** ป้ายสถานะ GPS มุมซ้ายบน */
    private fun renderGpsChip() {
        val s = service
        val (text, dot) = when {
            !hasLocation() -> getString(R.string.live_gps_no_permission) to R.drawable.dot_danger
            s == null -> getString(R.string.live_gps_searching) to R.drawable.dot_moderate
            !s.gpsEnabled -> getString(R.string.live_gps_off) to R.drawable.dot_danger
            s.state.paused -> getString(if (s.state.autoPaused) R.string.live_auto_paused else R.string.live_paused_chip) to R.drawable.dot_moderate
            s.state.lastFix == null -> getString(R.string.live_gps_searching) to R.drawable.dot_moderate
            else -> {
                val acc = s.state.lastFix!!.accuracy.roundToInt()
                when {
                    acc <= 10 -> getString(R.string.live_gps_good, acc) to R.drawable.dot_green
                    acc <= 25 -> getString(R.string.live_gps_fair, acc) to R.drawable.dot_moderate
                    else -> getString(R.string.live_gps_weak, acc) to R.drawable.dot_danger
                }
            }
        }
        binding.tvStatusChip.text = text
        binding.tvStatusChip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
    }

    private fun setLocked(value: Boolean) {
        locked = value
        val alpha = if (value) 0.35f else 1f
        binding.btnPause.alpha = alpha
        binding.btnCamera.alpha = alpha
        binding.btnLayers.alpha = alpha
        binding.btnLock.background = ContextCompat.getDrawable(
            this, if (value) R.drawable.bg_circle_lime else R.drawable.bg_circle_canvas,
        )
    }

    private fun finishRun() {
        val s = service ?: return
        s.listener = null
        val id = s.finish()
        service = null
        startActivity(
            Intent(this, RunDetailActivity::class.java)
                .putExtra(RunDetailActivity.EXTRA_RUN_ID, id)
                .putExtra(RunDetailActivity.EXTRA_JUST_FINISHED, true),
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        compass.start()
        service?.let { it.listener = { if (!isFinishing) render() }; it.restartGps() }
        if (service == null && RunTrackingService.isRunning) attach()
        render()
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        compass.stop()
        service?.listener = null   // ไม่ต้องวาดหน้าจอขณะมองไม่เห็น service ยังบันทึกต่อ
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (service?.listener != null) service?.listener = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DARK, mapDark)
        outState.putBoolean(KEY_STARTED, startRequested || service != null)
        outState.putString(KEY_PENDING_PHOTO, pendingPhoto?.absolutePath)
    }

    companion object {
        const val EXTRA_RESUME = "resume_draft"
        private const val KEY_DARK = "dark"
        private const val KEY_STARTED = "started"
        private const val KEY_PENDING_PHOTO = "pending_photo"
    }
}
