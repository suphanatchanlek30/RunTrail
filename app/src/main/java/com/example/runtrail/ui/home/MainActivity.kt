package com.example.runtrail.ui.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.data.Accounts
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityMainBinding
import com.example.runtrail.databinding.ItemRunBinding
import com.example.runtrail.databinding.ViewStatDarkBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.ui.common.BottomNav
import com.example.runtrail.ui.common.Dialogs
import com.example.runtrail.ui.common.RunCardBinder
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.dp
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.history.HistoryActivity
import com.example.runtrail.ui.live.LiveRunActivity
import com.example.runtrail.ui.onboarding.WelcomeActivity
import com.example.runtrail.ui.records.RecordsActivity
import com.example.runtrail.ui.sensor.SensorCheckActivity
import com.example.runtrail.service.RunTrackingService
import com.example.runtrail.sync.SyncManager
import kotlin.math.roundToInt

/**
 * 05 / 06 · Home — สรุปสัปดาห์เทียบเป้า · กราฟ 7 วัน · การวิ่งล่าสุด · ปุ่มเริ่มวิ่ง
 * - ไม่มีข้อมูล → สถานะว่าง (05)
 * - มีการวิ่งค้างใน run_draft.properties → Dialog กู้คืน (18)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsHelper
    private var recoverShown = false

    /** server ในวง LAN (backend บน Mac) ต้องได้สิทธิ์เครือข่ายภายในก่อนซิงก์ได้ (Android 17+) */
    private val askLocalNetwork = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) SyncManager.requestSync(this) else toast(getString(R.string.local_network_denied)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PrefsHelper(this)
        if (!prefs.onboarded) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }
        setupEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.content.applySystemBarsPadding(top = true)

        BottomNav.setup(this, binding.navContainer.bottomNav, R.id.nav_home)

        if (savedInstanceState == null && Accounts.isLoggedIn &&
            com.example.runtrail.ui.common.LocalNetwork.needsPermission(this)
        ) askLocalNetwork.launch(com.example.runtrail.ui.common.LocalNetwork.PERMISSION)
        binding.fabStart.setOnClickListener { startRun() }
        binding.btnFirstRun.setOnClickListener { startRun() }
        binding.cardSensorCheck.setOnClickListener {
            startActivity(Intent(this, SensorCheckActivity::class.java))
        }
        binding.btnSeeAll.setOnClickListener { BottomNavGo.history(this) }
        binding.cardWeekly.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        binding.btnBell.setOnClickListener {
            val pending = RunRepository.pendingCount()
            when {
                !Accounts.isLoggedIn && pending > 0 -> toast(getString(R.string.home_bell_guest, pending))
                pending > 0 -> {
                    toast(getString(R.string.home_bell_pending, pending))
                    SyncManager.requestSync(this)
                }
                else -> toast(getString(R.string.home_bell_none))
            }
        }
    }

    private val onDataChanged: () -> Unit = { if (::binding.isInitialized) render() }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        binding.navContainer.bottomNav.selectedItemId = R.id.nav_home
        RunRepository.addListener(onDataChanged)
        render()
        checkUnfinishedRun()
    }

    override fun onPause() {
        super.onPause()
        RunRepository.removeListener(onDataChanged)
    }

    private fun render() {
        val miles = prefs.useMiles
        binding.tvAvatar.text = prefs.initial
        binding.tvGreeting.text = Fmt.greeting()
        binding.tvName.text = prefs.firstName

        val all = RunRepository.getAll()
        val empty = all.isEmpty()
        binding.groupEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.groupData.visibility = if (empty) View.GONE else View.VISIBLE
        binding.fabStart.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmptyGoal.text = getString(R.string.home_goal_short, prefs.weeklyGoalKm, Fmt.unit(miles))
        binding.tvEmptyUnit.text = Fmt.unit(miles)
        if (empty) return

        // ---- การ์ดสรุปสัปดาห์ ----
        val week = RunRepository.thisWeek()
        val weekM = week.sumOf { it.distanceM }
        val goalM = prefs.weeklyGoalKm * if (miles) 1609.344 else 1000.0
        val percent = if (goalM > 0) (weekM / goalM * 100).roundToInt() else 0
        binding.tvGoalPercent.text = getString(R.string.home_goal_percent, percent)
        binding.tvWeekKm.text = Fmt.distance(weekM, miles, decimals = 1)
        binding.tvWeekGoal.text = getString(R.string.home_goal_of, prefs.weeklyGoalKm, Fmt.unit(miles))
        binding.progressWeek.progress = (weekM / goalM * 1000).roundToInt().coerceIn(0, 1000)

        bindStat(binding.statRuns, week.size.toString(), getString(R.string.unit_times), getString(R.string.label_runs))
        bindStat(binding.statTime, Fmt.hours(week.sumOf { it.movingTimeS }), getString(R.string.unit_hours), getString(R.string.label_total_time))
        bindStat(binding.statKcal, Fmt.number(week.sumOf { it.calories }), getString(R.string.unit_kcal), getString(R.string.label_calories))

        // ---- กราฟ 7 วัน ----
        val now = System.currentTimeMillis()
        val weekStart = RunRepository.startOfWeek(now)
        binding.tvWeekRange.text = Fmt.weekRange(weekStart)
        binding.chartWeek.setData(RunRepository.weekDailyKm(now), RunRepository.weekDayLetters(now), highlightIndex = 6)

        // ---- การวิ่งล่าสุด 2 รายการ ----
        binding.recentList.removeAllViews()
        all.take(2).forEachIndexed { index, run ->
            val item = ItemRunBinding.inflate(layoutInflater, binding.recentList, false)
            RunCardBinder.bind(item, run, miles, decimals = 1)
            item.root.setOnClickListener { openRun(run) }
            if (index > 0) {
                (item.root.layoutParams as android.widget.LinearLayout.LayoutParams).topMargin = dp(16f)
            }
            binding.recentList.addView(item.root)
        }
    }

    private fun bindStat(stat: ViewStatDarkBinding, value: String, unit: String, label: String) {
        stat.tvValue.text = value
        stat.tvUnit.text = unit
        stat.tvLabel.text = label
    }

    private fun openRun(run: Run) {
        startActivity(Intent(this, RunDetailActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, run.id))
    }

    /** มีการวิ่งที่กำลังบันทึกอยู่ → กลับไปหน้าวิ่ง ไม่เริ่มใหม่ */
    private fun startRun() {
        startActivity(Intent(this, LiveRunActivity::class.java))
    }

    /** 18 · กู้การวิ่งที่ค้าง (แอปถูกระบบปิดระหว่างวิ่ง) — ไม่ถามถ้ายังมีการวิ่งที่กำลังบันทึกอยู่ */
    private fun checkUnfinishedRun() {
        if (recoverShown || RunTrackingService.isRunning) return
        val draft = RunRepository.activeDraft() ?: return
        recoverShown = true
        Dialogs.recoverRun(
            this, draft, prefs.useMiles,
            onResume = {
                recoverShown = false
                startActivity(Intent(this, LiveRunActivity::class.java).putExtra(LiveRunActivity.EXTRA_RESUME, true))
            },
            onSave = {
                recoverShown = false
                // บันทึกเท่าที่มีพร้อมจุด GPS ทั้งหมดที่เก็บไว้ (เดิมทิ้งจุดและใส่เส้นทางปลอม)
                RunRepository.finishDraft(
                    draft.copy(title = getString(R.string.recover_saved_title)),
                    draft.track.lastOrNull()?.time ?: draft.updatedAt, draft.track,
                )
                SyncManager.requestSync(this)
                startActivity(Intent(this, RunDetailActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, draft.id))
            },
            onDiscard = {
                recoverShown = false
                RunRepository.discardDraft(draft.id)
                toast(getString(R.string.recover_discarded))
            },
        )
    }

    private object BottomNavGo {
        fun history(activity: AppCompatActivity) {
            activity.startActivity(
                Intent(activity, HistoryActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(0, 0)
        }
    }
}
