package com.example.runtrail.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityRunStatsBinding
import com.example.runtrail.databinding.ItemSplitBinding
import com.example.runtrail.databinding.ViewConditionBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.Units
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.replay.ReplayActivity
import com.example.runtrail.ui.share.ShareSheet
import kotlin.math.roundToInt

/**
 * 10 · Splits & Charts — กราฟเพซ/ความสูง (Canvas) + ตาราง split รายกิโลเมตร + สภาพแวดล้อม
 */
class RunStatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRunStatsBinding
    private lateinit var run: Run
    private var tab = TAB_OVERVIEW
    private var showElevation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityRunStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.content.applySystemBarsPadding(top = true, bottom = true)

        val id = intent.getLongExtra(RunDetailActivity.EXTRA_RUN_ID, 0)
        run = RunRepository.getById(id) ?: run { finish(); return }
        tab = savedInstanceState?.getInt(KEY_TAB) ?: TAB_OVERVIEW
        showElevation = savedInstanceState?.getBoolean(KEY_ELEV) ?: false

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { v ->
            PopupMenu(this, v).apply {
                if (RunRepository.route(run).size >= 2) menu.add(0, 1, 0, R.string.action_replay)
                menu.add(0, 2, 1, R.string.action_share)
                setOnMenuItemClickListener {
                    if (it.itemId == 1) {
                        startActivity(Intent(this@RunStatsActivity, ReplayActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, run.id))
                    } else {
                        ShareSheet.show(this@RunStatsActivity, run) {}
                    }
                    true
                }
            }.show()
        }
        binding.segOverview.setOnClickListener { tab = TAB_OVERVIEW; renderTab() }
        binding.segSplits.setOnClickListener { tab = TAB_SPLITS; renderTab() }
        binding.segCharts.setOnClickListener { tab = TAB_CHARTS; renderTab() }
        binding.chipPace.setOnClickListener { showElevation = false; renderChart() }
        binding.chipElevation.setOnClickListener { showElevation = true; renderChart() }

        renderChart()
        renderSplits()
        renderConditions()
        renderTab()
    }

    private fun renderTab() {
        binding.segOverview.isSelected = tab == TAB_OVERVIEW
        binding.segSplits.isSelected = tab == TAB_SPLITS
        binding.segCharts.isSelected = tab == TAB_CHARTS
        binding.cardChart.visibility = if (tab != TAB_SPLITS) View.VISIBLE else View.GONE
        binding.cardSplits.visibility = if (tab != TAB_CHARTS) View.VISIBLE else View.GONE
        binding.cardConditions.visibility = if (tab != TAB_SPLITS) View.VISIBLE else View.GONE
    }

    private fun renderChart() {
        val miles = PrefsHelper(this).useMiles
        binding.chipPace.isSelected = !showElevation
        binding.chipElevation.isSelected = showElevation
        val route = RunRepository.route(run)
        // เฉลี่ยเคลื่อนที่ 9 จุดเพื่อให้กราฟนุ่มขึ้น
        val raw = route.map { if (showElevation) it.altitudeM else it.paceSecPerKm.toFloat() }
        val smooth = raw.indices.map { i ->
            val from = (i - 4).coerceAtLeast(0)
            val to = (i + 4).coerceAtMost(raw.size - 1)
            raw.subList(from, to + 1).average().toFloat()
        }
        val color = ContextCompat.getColor(this, if (showElevation) R.color.pace_moderate else R.color.run_green)
        binding.chart.setData(smooth, invert = !showElevation, color = color)

        if (raw.isEmpty()) {
            // การวิ่งที่บันทึกเองไม่มีจุด GPS (เดิม raw.max() ของรายการว่างทำให้แอปเด้ง, AUDIT A2)
            binding.tvChartTitle.setText(if (showElevation) R.string.stats_elev_chart else R.string.stats_pace_chart)
            binding.tvChartDesc.setText(R.string.stats_no_route)
        } else if (showElevation) {
            binding.tvChartTitle.setText(R.string.stats_elev_chart)
            binding.tvChartDesc.text = getString(R.string.stats_elev_desc, run.elevationGainM, raw.max().roundToInt())
        } else {
            val best = RunRepository.splits(run).minOfOrNull { it.paceSecPerKm } ?: 0
            binding.tvChartTitle.setText(R.string.stats_pace_chart)
            binding.tvChartDesc.text = getString(
                R.string.stats_pace_desc,
                Fmt.pace(RunCalculator.pace(run), miles), Fmt.paceUnit(miles), Fmt.pace(best),
            )
        }

        // แกน X: 0 … N (กม. หรือ ไมล์)
        binding.axis.removeAllViews()
        val marks = Units.axisMarks(run.distanceM, miles)   // ตามหน่วยที่เลือก (AUDIT A8)
        marks.forEachIndexed { i, k ->
            val tv = TextView(this).apply {
                setTextAppearance(R.style.Txt_Caption)
                text = if (i == marks.lastIndex) "$k ${Fmt.unit(miles)}" else k.toString()
                gravity = when (i) {
                    0 -> Gravity.START
                    marks.lastIndex -> Gravity.END
                    else -> Gravity.CENTER_HORIZONTAL
                }
            }
            binding.axis.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, if (i == 0 || i == marks.lastIndex) 0.5f else 1f))
        }
    }

    private fun renderSplits() {
        val splits = RunRepository.splits(run)
        if (splits.isEmpty()) return
        val min = splits.minOf { it.paceSecPerKm }
        val max = splits.maxOf { it.paceSecPerKm }
        val range = (max - min).coerceAtLeast(1)
        binding.splitRows.removeAllViews()
        val fastestKm = splits.first { it.paceSecPerKm == min }.km
        val slowestKm = splits.first { it.paceSecPerKm == max }.km
        splits.forEach { s ->
            val row = ItemSplitBinding.inflate(layoutInflater, binding.splitRows, false)
            row.tvKm.text = s.km.toString()
            row.tvPace.text = Fmt.pace(s.paceSecPerKm)
            row.tvElev.text = "+${s.elevationDeltaM}"
            val frac = 0.6f - (s.paceSecPerKm - min).toFloat() / range * 0.255f
            row.barBox.weightSum = 1f
            row.bar.layoutParams = LinearLayout.LayoutParams(0, row.bar.layoutParams.height, frac)
            when {
                splits.size > 1 && s.km == fastestKm -> {
                    row.tvBadge.visibility = View.VISIBLE
                    row.tvBadge.setText(R.string.stats_fastest)
                    row.tvBadge.setBackgroundResource(R.drawable.bg_pill_green_soft)
                    row.tvBadge.setTextColor(getColor(R.color.run_green_dark))
                }
                splits.size > 1 && s.km == slowestKm -> {
                    row.tvBadge.visibility = View.VISIBLE
                    row.tvBadge.setText(R.string.stats_slowest)
                    row.tvBadge.setBackgroundResource(R.drawable.bg_pill_warning_soft)
                    row.tvBadge.setTextColor(getColor(R.color.warning))
                    row.bar.setBackgroundResource(R.drawable.bg_bar_slow)
                }
            }
            binding.splitRows.addView(row.root)
        }
    }

    private fun renderConditions() {
        fun cond(c: ViewConditionBinding, icon: Int, value: String, label: Int) {
            c.ivIcon.setImageResource(icon); c.tvValue.text = value; c.tvLabel.setText(label)
        }
        // อุณหภูมิมาจากเซนเซอร์ในเครื่อง (ส่วนใหญ่ไม่มี) จึงแสดง "--" แทนการเดาค่า · ไม่มีเซนเซอร์ความชื้น
        cond(binding.condTemp, R.drawable.ic_thermo, run.tempC?.let { "$it°C" } ?: "--", R.string.stats_temp)
        cond(binding.condHumidity, R.drawable.ic_activity, run.track.size.toString(), R.string.stats_gps_points)
        cond(binding.condElevation, R.drawable.ic_mountain, "+${run.elevationGainM} ${getString(R.string.unit_m)}", R.string.stats_chip_elevation)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
        outState.putBoolean(KEY_ELEV, showElevation)
    }

    companion object {
        private const val TAB_OVERVIEW = 0
        private const val TAB_SPLITS = 1
        private const val TAB_CHARTS = 2
        private const val KEY_TAB = "tab"
        private const val KEY_ELEV = "elevation"
    }
}
