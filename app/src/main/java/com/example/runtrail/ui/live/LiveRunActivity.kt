package com.example.runtrail.ui.live

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.data.DraftStore
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.TrackPoint
import com.example.runtrail.databinding.ActivityLiveRunBinding
import com.example.runtrail.databinding.ViewStatBinding
import com.example.runtrail.databinding.ViewTileBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.GpsFilter
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.domain.TrackMath
import com.example.runtrail.sensor.CadenceTracker
import com.example.runtrail.sensor.CompassHelper
import com.example.runtrail.sensor.LocationTracker
import com.example.runtrail.ui.common.ImageStore
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.widget.OsmRoute
import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 07 · Live Run / 08 · Paused — บันทึกการวิ่งจาก GPS จริง (MA12)
 *
 * - LocationManager ทุก 1 วินาที → กรองจุด (GpsFilter) → ระยะทาง เพซ เส้นทางบนแผนที่ OpenStreetMap
 * - Cadence จาก Step Counter · เข็มทิศจาก rotation vector · แบตจาก BatteryManager
 * - Auto-pause เมื่อไม่ขยับเกิน 8 วินาที (ตั้งค่าได้) และวิ่งต่อเองเมื่อเริ่มเคลื่อนที่
 * - ทุก 5 วินาทีเขียน draft (+ จุด GPS) ลงไฟล์ เพื่อกู้คืนได้ถ้าแอปถูกปิด (MA09)
 * - จบการวิ่งด้วย "กดค้างเพื่อจบ" เท่านั้น
 */
class LiveRunActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveRunBinding
    private lateinit var prefs: PrefsHelper
    private lateinit var drafts: DraftStore
    private lateinit var compass: CompassHelper
    private lateinit var tracker: LocationTracker
    private lateinit var cadenceTracker: CadenceTracker
    private lateinit var overlay: OsmRoute.RouteOverlay
    private val handler = Handler(Looper.getMainLooper())
    private val filter = GpsFilter()

    private var startTime = System.currentTimeMillis()
    private var elapsedS = 0L
    private var distanceM = 0.0
    private val track = mutableListOf<TrackPoint>()
    private var lastFix: TrackPoint? = null
    private var lastMoveAt = System.currentTimeMillis()
    private var cadenceSum = 0L
    private var cadenceCount = 0
    private var paused = false
    private var autoPaused = false
    private var locked = false
    private var mapDark = false
    private var followUntil = 0L
    private var photoPath: String? = null
    private var tempC: Float? = null

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
        if (bmp != null) {
            photoPath = ImageStore.save(this, bmp)
            toast(getString(R.string.live_photo_saved))
        }
    }

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (tracker.start()) renderGpsChip() else {
            toast(getString(R.string.perm_location_denied))
            renderGpsChip()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000)
        }
    }

    private val tempListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) { if (e.values[0] in -40f..80f) tempC = e.values[0] }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmRoute.init(this)
        setupEdgeToEdge()
        binding = ActivityLiveRunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = PrefsHelper(this)
        drafts = DraftStore(this)
        binding.overlay.applySystemBarsPadding(top = true)
        binding.sheetRunning.applySystemBarsPadding(top = false, bottom = true)
        binding.sheetPaused.applySystemBarsPadding(top = false, bottom = true)

        // แผนที่จริง
        OsmRoute.setup(binding.map, interactive = true)
        overlay = OsmRoute.RouteOverlay(resources.displayMetrics.density).apply { showCurrent = true }
        binding.map.overlays.add(overlay)
        binding.map.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) followUntil = System.currentTimeMillis() + 10_000
            false
        }

        setupTiles()

        when {
            savedInstanceState != null -> restore(savedInstanceState)
            intent.getBooleanExtra(EXTRA_RESUME, false) -> drafts.load()?.let { d ->
                startTime = d.startTime; distanceM = d.distanceM; elapsedS = d.elapsedS
                track += drafts.loadTrack()
                filter.reset(track.lastOrNull())
            }
        }
        overlay.track = track.toList()
        binding.map.setDarkIfNeeded()

        tracker = LocationTracker(this, ::onLocation) { renderGpsChip() }
        cadenceTracker = CadenceTracker(this)
        compass = CompassHelper(this) { deg ->
            binding.ivCompass.rotation = -deg.toFloat()
            binding.tvHeading.text = CompassHelper.label(deg)
        }
        if (!compass.available) binding.tvHeading.text = getString(R.string.live_no_compass)

        if (tracker.hasPermission) tracker.start()
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        cadenceTracker.start()
        (getSystemService(SENSOR_SERVICE) as SensorManager).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)?.let { sm.registerListener(tempListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        centerOnStart()

        binding.btnPause.setOnClickListener { if (!locked) setPaused(true, auto = false) }
        binding.btnResume.setOnClickListener { setPaused(false, auto = false) }
        binding.btnHoldStop.onCompleted = { finishRun() }
        binding.btnLayers.setOnClickListener {
            mapDark = !mapDark
            binding.map.setDarkIfNeeded()
        }
        binding.btnLocate.setOnClickListener {
            followUntil = 0
            (lastFix ?: tracker.lastKnown?.let { TrackPoint(it.latitude, it.longitude, 0, 0.0, 0f) })?.let {
                binding.map.controller.animateTo(GeoPoint(it.lat, it.lng), 17.5, 400L)
            }
        }
        binding.tvStatusChip.setOnClickListener {
            when {
                !tracker.hasPermission -> askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                !tracker.gpsEnabled -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        binding.btnCamera.setOnClickListener { if (!locked) takePhoto.launch(null) }
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
                when {
                    locked -> toast(getString(R.string.live_unlock_hint))
                    !paused -> setPaused(true, auto = false)
                    else -> { saveDraft(); finish() }   // ออกโดยยังไม่จบ → หน้าหลักจะถามกู้คืน
                }
            }
        })

        renderState()
        renderNumbers()
        renderGpsChip()
        updateMapOffset()
    }

    /** ให้ตำแหน่งนักวิ่งอยู่กลางพื้นที่แผนที่ที่มองเห็น (เหนือ bottom sheet) */
    private fun updateMapOffset() {
        val sheet = if (paused) binding.sheetPaused else binding.sheetRunning
        sheet.post {
            val hidden = sheet.height + resources.displayMetrics.density * 24
            binding.map.setMapCenterOffset(0, -(hidden / 2).toInt())
            lastFix?.let { binding.map.controller.setCenter(GeoPoint(it.lat, it.lng)) }
        }
    }

    private fun org.osmdroid.views.MapView.setDarkIfNeeded() {
        OsmRoute.setDark(this, mapDark)
        binding.topFade.visibility = if (mapDark) View.INVISIBLE else View.VISIBLE
    }

    private fun centerOnStart() {
        val p = track.lastOrNull() ?: tracker.lastKnown?.let { TrackPoint(it.latitude, it.longitude, 0, 0.0, 0f) }
        if (p != null) binding.map.controller.setCenter(GeoPoint(p.lat, p.lng))
        else binding.map.controller.setCenter(GeoPoint(13.7563, 100.5018))   // ยังไม่มีตำแหน่ง: กรุงเทพฯ
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

    /** ได้ตำแหน่งใหม่จาก GPS */
    private fun onLocation(p: TrackPoint, speed: Float?) {
        lastFix = p
        renderGpsChip()
        val now = System.currentTimeMillis()

        if (paused) {
            // หยุดอัตโนมัติอยู่ แล้วเริ่มเคลื่อนที่ → วิ่งต่อเอง
            val moved = filter.last?.let { TrackMath.distance(it, p) } ?: 0.0
            if (autoPaused && ((speed ?: 0f) > RESUME_SPEED || moved > 8)) {
                filter.reset(p)
                track += p
                setPaused(false, auto = false)
            } else {
                followMap(p)
            }
            return
        }

        val added = filter.accept(p) ?: run { followMap(p); return }
        if (track.isEmpty() || added > 0) {
            track += p
            distanceM += added
            if (added > 0) lastMoveAt = now
            overlay.track = track.toList()
        }
        if ((speed ?: 0f) > STILL_SPEED) lastMoveAt = now
        followMap(p)
        binding.map.invalidate()
        renderNumbers()
    }

    private fun followMap(p: TrackPoint) {
        if (System.currentTimeMillis() > followUntil) binding.map.controller.animateTo(GeoPoint(p.lat, p.lng))
        if (track.isEmpty()) { overlay.track = listOf(p) }
        binding.map.invalidate()
    }

    private fun tick() {
        if (!paused) elapsedS++
        val now = System.currentTimeMillis()
        val c = cadenceTracker.cadence(now)
        if (c > 0 && !paused) { cadenceSum += c; cadenceCount++ }

        // Auto-pause: ไม่ขยับเกิน 8 วินาทีหลังเริ่มวิ่ง
        if (prefs.autoPause && !paused && elapsedS > 10 && track.isNotEmpty() && now - lastMoveAt > AUTO_PAUSE_MS) {
            setPaused(true, auto = true)
        }
        if (elapsedS % 5 == 0L) saveDraft()
        renderNumbers()
    }

    /** เพซปัจจุบันจากระยะที่วิ่งใน 30 วินาทีล่าสุด */
    private fun currentPace(): Double {
        if (track.size < 2) return 0.0
        val cutoff = track.last().time - 30_000
        val recent = track.filter { it.time >= cutoff }
        if (recent.size < 2) return 0.0
        var d = 0.0
        for (i in 1 until recent.size) d += TrackMath.distance(recent[i - 1], recent[i])
        val t = (recent.last().time - recent.first().time) / 1000.0
        return if (d < 10 || t <= 0) 0.0 else t / (d / 1000.0)
    }

    private fun renderNumbers() {
        val miles = prefs.useMiles
        val avg = RunCalculator.paceSecPerKm(elapsedS, distanceM)
        val now = currentPace()
        val kcal = RunCalculator.calories(distanceM, elapsedS, prefs.weightKg)
        val cadence = cadenceTracker.cadence()

        binding.tvDistance.text = Fmt.distance(distanceM, miles)
        binding.tvElapsed.text = Fmt.duration(elapsedS)
        binding.tileCurrentPace.tvValue.text = if (now > 0) Fmt.pace(now, miles) else "--:--"
        binding.tileAvgPace.tvValue.text = if (distanceM >= 20) Fmt.pace(avg, miles) else "--:--"
        binding.tileCalories.tvValue.text = Fmt.number(kcal)
        binding.tileCadence.tvValue.text = if (cadence > 0) cadence.toString() else "--"
        binding.chipElevation.text = getString(R.string.live_elevation, TrackMath.elevationGain(track))
        binding.chipTemp.visibility = if (tempC != null) View.VISIBLE else View.GONE
        tempC?.let { binding.chipTemp.text = getString(R.string.live_temp, it.roundToInt()) }
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        binding.chipBattery.text = getString(R.string.live_battery, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))

        binding.pausedDistance.tvValue.text = Fmt.distance(distanceM, miles)
        binding.pausedTime.tvValue.text = Fmt.duration(elapsedS)
        binding.pausedPace.tvValue.text = if (distanceM >= 20) Fmt.pace(avg, miles) else "--:--"

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
    }

    /** ป้ายสถานะ GPS มุมซ้ายบน */
    private fun renderGpsChip() {
        if (!::tracker.isInitialized) return
        val chip = binding.tvStatusChip
        val (text, dot) = when {
            !tracker.hasPermission -> getString(R.string.live_gps_no_permission) to R.drawable.dot_danger
            !tracker.gpsEnabled -> getString(R.string.live_gps_off) to R.drawable.dot_danger
            paused -> getString(if (autoPaused) R.string.live_auto_paused else R.string.live_paused_chip) to R.drawable.dot_moderate
            lastFix == null -> getString(R.string.live_gps_searching) to R.drawable.dot_moderate
            else -> {
                val acc = lastFix!!.accuracy.roundToInt()
                when {
                    acc <= 10 -> getString(R.string.live_gps_good, acc) to R.drawable.dot_green
                    acc <= 25 -> getString(R.string.live_gps_fair, acc) to R.drawable.dot_moderate
                    else -> getString(R.string.live_gps_weak, acc) to R.drawable.dot_danger
                }
            }
        }
        chip.text = text
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, 0, 0, 0)
    }

    private fun setPaused(value: Boolean, auto: Boolean) {
        paused = value
        autoPaused = value && auto
        binding.btnHoldStop.reset()
        if (!value) {
            lastMoveAt = System.currentTimeMillis()
            filter.reset(lastFix)        // ไม่นับระยะช่วงที่หยุด
        } else saveDraft()
        renderState()
    }

    private fun renderState() {
        binding.sheetRunning.visibility = if (paused) View.GONE else View.VISIBLE
        binding.sheetPaused.visibility = if (paused) View.VISIBLE else View.GONE
        binding.tvPaceBanner.visibility = if (paused) View.GONE else View.VISIBLE
        binding.compass.visibility = if (paused) View.GONE else View.VISIBLE
        if (paused) {
            binding.tvPausedReason.text = getString(if (autoPaused) R.string.live_paused_auto_reason else R.string.live_paused_manual_reason)
        }
        renderGpsChip()
        updateMapOffset()
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

    private fun saveDraft() {
        drafts.save(
            DraftStore.Draft(
                startTime = startTime,
                distanceM = distanceM,
                elapsedS = elapsedS,
                gpsPoints = track.size,
                calories = RunCalculator.calories(distanceM, elapsedS, prefs.weightKg),
                savedAt = System.currentTimeMillis(),
            ),
        )
        drafts.saveTrack(track)
    }

    private fun finishRun() {
        handler.removeCallbacks(ticker)
        tracker.stop()
        val run = Run(
            title = defaultTitle(startTime),
            startTime = startTime,
            endTime = System.currentTimeMillis(),
            distanceM = distanceM,
            movingTimeS = elapsedS,
            calories = RunCalculator.calories(distanceM, elapsedS, prefs.weightKg),
            elevationGainM = TrackMath.elevationGain(track),
            cadenceSpm = if (cadenceCount > 0) (cadenceSum / cadenceCount).toInt() else 0,
            tempC = tempC?.roundToInt() ?: 29,
            photoUri = photoPath,
            track = track.toList(),
        )
        val id = RunRepository.insert(run)
        drafts.clear()
        startActivity(
            Intent(this, RunDetailActivity::class.java)
                .putExtra(RunDetailActivity.EXTRA_RUN_ID, id)
                .putExtra(RunDetailActivity.EXTRA_JUST_FINISHED, true),
        )
        finish()
    }

    private fun defaultTitle(start: Long): String {
        val hour = java.util.Calendar.getInstance().apply { timeInMillis = start }.get(java.util.Calendar.HOUR_OF_DAY)
        return getString(
            when (hour) {
                in 4..10 -> R.string.title_morning_run
                in 11..15 -> R.string.title_afternoon_run
                in 16..19 -> R.string.title_evening_run
                else -> R.string.title_night_run
            },
        )
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        compass.start()
        if (tracker.hasPermission && !tracker.running) tracker.start()
        renderGpsChip()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    override fun onPause() {
        super.onPause()
        binding.map.onPause()
        compass.stop()
        if (!isFinishing) saveDraft()
        // GPS และตัวจับเวลายังทำงานต่อขณะแอปอยู่เบื้องหลังระยะสั้น ๆ (หน้าจอเปิดค้างไว้ระหว่างวิ่ง)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        if (::tracker.isInitialized) tracker.stop()
        if (::cadenceTracker.isInitialized) cadenceTracker.stop()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(tempListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_START, startTime)
        outState.putLong(KEY_ELAPSED, elapsedS)
        outState.putDouble(KEY_DISTANCE, distanceM)
        outState.putBoolean(KEY_PAUSED, paused)
        outState.putBoolean(KEY_AUTO, autoPaused)
        outState.putBoolean(KEY_DARK, mapDark)
        outState.putString(KEY_PHOTO, photoPath)
        drafts.saveTrack(track)          // จุด GPS อาจมีจำนวนมาก จึงเก็บในไฟล์แทน Bundle
    }

    private fun restore(s: Bundle) {
        startTime = s.getLong(KEY_START)
        elapsedS = s.getLong(KEY_ELAPSED)
        distanceM = s.getDouble(KEY_DISTANCE)
        paused = s.getBoolean(KEY_PAUSED)
        autoPaused = s.getBoolean(KEY_AUTO)
        mapDark = s.getBoolean(KEY_DARK)
        photoPath = s.getString(KEY_PHOTO)
        track += drafts.loadTrack()
        filter.reset(track.lastOrNull())
    }

    companion object {
        const val EXTRA_RESUME = "resume_draft"
        private const val AUTO_PAUSE_MS = 8_000L
        private const val STILL_SPEED = 0.5f       // ม./วินาที
        private const val RESUME_SPEED = 1.0f
        private const val KEY_START = "start"
        private const val KEY_ELAPSED = "elapsed"
        private const val KEY_DISTANCE = "distance"
        private const val KEY_PAUSED = "paused"
        private const val KEY_AUTO = "auto"
        private const val KEY_DARK = "dark"
        private const val KEY_PHOTO = "photo"
    }
}
