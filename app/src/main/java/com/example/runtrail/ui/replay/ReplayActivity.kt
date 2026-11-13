package com.example.runtrail.ui.replay

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.RoutePoint
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityReplayBinding
import com.example.runtrail.databinding.ViewStatLargeBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.ui.common.toast
import com.example.runtrail.domain.Units
import com.example.runtrail.domain.PaceColor
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.widget.OsmRoute
import kotlin.math.roundToInt

/** Replays a run on the map at 1×, 2×, 4× or 8× speed. */
class ReplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReplayBinding
    private lateinit var run: Run
    private lateinit var route: List<RoutePoint>
    private val handler = Handler(Looper.getMainLooper())
    private var progress = 0f
    private var playing = true
    private var speed = 4
    private var miles = false
    private val overlay by lazy { OsmRoute.RouteOverlay(resources.displayMetrics.density) }

    private val frame = object : Runnable {
        override fun run() {
            if (playing) {
                progress += FRAME_MS / BASE_DURATION_MS * speed
                if (progress >= 1f) { progress = 1f; setPlaying(false) }
                render()
            }
            handler.postDelayed(this, FRAME_MS.toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmRoute.init(this)
        setupEdgeToEdge()
        binding = ActivityReplayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.top.applySystemBarsPadding(top = true)
        binding.sheet.applySystemBarsPadding(top = false, bottom = true)

        run = RunRepository.getById(intent.getLongExtra(RunDetailActivity.EXTRA_RUN_ID, 0)) ?: run { finish(); return }
        route = RunRepository.route(run)
        // Manual runs have no route to replay.
        if (route.size < 2) {
            toast(getString(R.string.replay_no_route))
            finish()
            return
        }
        miles = PrefsHelper(this).useMiles

        progress = savedInstanceState?.getFloat(KEY_PROGRESS) ?: 0.0f
        speed = savedInstanceState?.getInt(KEY_SPEED) ?: 4
        playing = savedInstanceState?.getBoolean(KEY_PLAYING) ?: true

        binding.tvTitle.text = getString(R.string.replay_title, run.title)
        binding.tvTitle.setStartIcon(R.drawable.ic_rotate, 16f, R.color.run_green_dark)
        binding.map.setRoute(route)
        binding.map.routeTop = 0.2f
        binding.map.routeBottom = 0.62f
        binding.map.showCurrent = true

        // GPS runs replay on the OpenStreetMap view.
        if (run.track.size >= 2) {
            OsmRoute.setup(binding.osmMap, interactive = true)
            overlay.track = run.track
            overlay.showCurrent = true
            binding.osmMap.overlays.add(overlay)
            binding.osmMap.visibility = android.view.View.VISIBLE
            binding.map.visibility = android.view.View.GONE
            binding.sheet.post {
                OsmRoute.fit(binding.osmMap, run.track, padTopPx = binding.top.height, padBottomPx = binding.sheet.height)
            }
        }

        binding.statDistance.tvUnit.text = Fmt.unit(miles)
        binding.statDistance.tvLabel.setText(R.string.replay_distance_here)
        binding.statTime.tvUnit.visibility = android.view.View.GONE
        binding.statTime.tvLabel.setText(R.string.label_time)
        binding.statPace.tvUnit.text = Fmt.paceUnit(miles)
        binding.statPace.tvLabel.setText(R.string.label_pace)

        buildAxis()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPlay.setOnClickListener {
            if (!playing && progress >= 1f) progress = 0f
            setPlaying(!playing)
        }
        binding.btnPrev.setOnClickListener { progress = (progress - 0.1f).coerceAtLeast(0f); render() }
        binding.btnNext.setOnClickListener { progress = (progress + 0.1f).coerceAtMost(1f); render() }
        binding.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) { progress = value / 1000f; render() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        mapOf(binding.speed1 to 1, binding.speed2 to 2, binding.speed4 to 4, binding.speed8 to 8)
            .forEach { (chip, s) -> chip.setOnClickListener { speed = s; renderSpeed() } }

        setPlaying(playing)
        renderSpeed()
        render()
    }

    private fun buildAxis() {
        val marks = Units.axisMarks(run.distanceM, miles)
        marks.forEachIndexed { i, k ->
            val tv = TextView(this).apply {
                setTextAppearance(R.style.Txt_Caption)
                text = if (i == marks.lastIndex) "$k ${Fmt.unit(miles)}" else k.toString()
                gravity = when (i) { 0 -> Gravity.START; marks.lastIndex -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
            }
            binding.axis.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i == 0 || i == marks.lastIndex) 0.5f else 1f))
        }
    }

    private fun setPlaying(value: Boolean) {
        playing = value
        binding.btnPlay.setImageResource(if (value) R.drawable.ic_pause else R.drawable.ic_play)
        binding.btnPlay.contentDescription = getString(if (value) R.string.replay_pause else R.string.replay_play)
    }

    private fun renderSpeed() {
        binding.speed1.isSelected = speed == 1
        binding.speed2.isSelected = speed == 2
        binding.speed4.isSelected = speed == 4
        binding.speed8.isSelected = speed == 8
    }

    private fun render() {
        binding.map.progress = progress
        if (binding.osmMap.visibility == android.view.View.VISIBLE) {
            overlay.progress = progress
            binding.osmMap.invalidate()
        }
        binding.seek.progress = (progress * 1000).roundToInt()

        val point = route[((route.size - 1) * progress).toInt()]
        val distance = run.distanceM * progress
        val elapsed = (run.movingTimeS * progress).toLong()
        binding.statDistance.tvValue.text = Fmt.distance(distance, miles)
        binding.statTime.tvValue.text = Fmt.duration(elapsed)
        binding.statPace.tvValue.text = Fmt.pace(point.paceSecPerKm.toDouble(), miles)

        val unitIndex = Units.inUnit(distance, miles).toInt() + 1
        val color = PaceColor.forPace(point.paceSecPerKm)
        binding.tvSegment.text = getString(R.string.replay_segment, PaceColor.label(point.paceSecPerKm), unitIndex, Fmt.unit(miles))
        binding.tvSegmentPace.text = getString(R.string.replay_segment_pace, Fmt.pace(point.paceSecPerKm.toDouble(), miles), Fmt.paceUnit(miles))
        (binding.segmentDot.background.mutate() as? GradientDrawable)?.setColor(color)
    }

    override fun onResume() {
        super.onResume()
        binding.osmMap.onResume()
        handler.removeCallbacks(frame)
        handler.postDelayed(frame, FRAME_MS.toLong())
    }

    override fun onPause() {
        super.onPause()
        binding.osmMap.onPause()
        handler.removeCallbacks(frame)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(KEY_PROGRESS, progress)
        outState.putInt(KEY_SPEED, speed)
        outState.putBoolean(KEY_PLAYING, playing)
    }

    companion object {
        private const val FRAME_MS = 40f
        private const val BASE_DURATION_MS = 60_000f   // 1× plays the whole run in 60 s
        private const val KEY_PROGRESS = "progress"
        private const val KEY_SPEED = "speed"
        private const val KEY_PLAYING = "playing"
    }
}
