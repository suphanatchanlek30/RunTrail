package com.example.runtrail.ui.records

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.PersonalRecord
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityRecordsBinding
import com.example.runtrail.databinding.ItemPrBinding
import com.example.runtrail.databinding.ViewStatDarkBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.ui.common.BottomNav
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.setupEdgeToEdge

/**
 * 16 · Personal Records — ผลรวมตลอดการใช้งาน (SUM) · PR 1/5/10 กม. (MIN) · วิ่งไกลสุด (MAX) · กราฟรายเดือน (GROUP BY)
 */
class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.content.applySystemBarsPadding(top = true)
        BottomNav.setup(this, binding.navContainer.bottomNav, R.id.nav_records)
        binding.btnShare.setOnClickListener { shareSummary() }
    }

    override fun onResume() {
        super.onResume()
        binding.navContainer.bottomNav.selectedItemId = R.id.nav_records
        render()
    }

    private var loadGen = 0

    /** โหลดทุกการวิ่งบน background thread แล้วค่อยแสดงผล (AUDIT P2) */
    private fun render() {
        val gen = ++loadGen
        runAsync({ RunRepository.getAll() }) { r -> if (gen == loadGen) r.getOrNull()?.let { show(it) } }
    }

    private fun show(runs: List<com.example.runtrail.data.Run>) {
        val miles = PrefsHelper(this).useMiles
        binding.tvTotal.text = Fmt.distance(runs.sumOf { it.distanceM }, miles, 1)
        binding.tvTotalUnit.text = Fmt.unit(miles)
        stat(binding.statRuns, runs.size.toString(), getString(R.string.unit_times), getString(R.string.label_runs))
        stat(binding.statTime, Fmt.hours(runs.sumOf { it.movingTimeS }), getString(R.string.unit_hours), getString(R.string.label_total_time))
        stat(binding.statKcal, Fmt.compact(runs.sumOf { it.calories }), getString(R.string.unit_kcal), getString(R.string.label_calories))

        val records = RunCalculator.records(runs, miles = miles)
        listOf(binding.pr1, binding.pr2, binding.pr3, binding.pr4).forEachIndexed { i, card ->
            records.getOrNull(i)?.let { pr(card, it) }
        }

        val monthly = RunRepository.monthlyKm(6, all = runs, miles = miles)
        binding.chartMonthly.showValues = true
        binding.chartMonthly.barWidthDp = 34f
        binding.chartMonthly.setData(
            monthly.map { it.second }.toFloatArray(),
            monthly.map { Fmt.monthShort(it.first) },
            highlightIndex = monthly.lastIndex,
        )
    }

    private fun stat(s: ViewStatDarkBinding, value: String, unit: String, label: String) {
        s.tvValue.text = value; s.tvUnit.text = unit; s.tvLabel.text = label
    }

    private fun pr(card: ItemPrBinding, r: PersonalRecord) {
        card.tvValue.text = r.value
        card.tvTitle.text = r.title
        card.tvDate.text = if (r.dateMillis > 0) Fmt.shortDate(r.dateMillis) else getString(R.string.records_none)
        card.tvNew.visibility = if (r.isNew) View.VISIBLE else View.GONE
    }

    private fun shareSummary() {
        val miles = PrefsHelper(this).useMiles
        val runs = RunRepository.getAll()
        val records = RunCalculator.records(runs, miles = miles)
        val text = buildString {
            append(getString(R.string.records_share_head,
                "${Fmt.distance(runs.sumOf { it.distanceM }, miles, decimals = 1)} ${Fmt.unit(miles)}", runs.size))
            records.forEach { append("\n• ${it.title}: ${it.value}") }
            append("\n#RunTrail")
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }
}
