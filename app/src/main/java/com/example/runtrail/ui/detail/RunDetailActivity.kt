package com.example.runtrail.ui.detail

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.example.runtrail.R
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.ActivityRunDetailBinding
import com.example.runtrail.databinding.ViewStatBinding
import com.example.runtrail.databinding.ViewStatLargeBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.RunCalculator
import com.example.runtrail.ui.common.Dialogs
import com.example.runtrail.ui.common.ImageStore
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.dp
import com.example.runtrail.ui.widget.OsmRoute
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.edit.AddEditRunActivity
import com.example.runtrail.ui.replay.ReplayActivity
import com.example.runtrail.ui.share.ShareSheet

/**
 * 09 · Run Summary — เปิดทันทีหลังจบการวิ่ง / แตะการ์ดจากหน้าหลักหรือประวัติ
 */
class RunDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRunDetailBinding
    private lateinit var prefs: PrefsHelper
    private var runId = 0L
    private val routeOverlay by lazy { OsmRoute.RouteOverlay(resources.displayMetrics.density) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OsmRoute.init(this)
        setupEdgeToEdge()
        binding = ActivityRunDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.topActions.applySystemBarsPadding(top = true)
        binding.sheet.applySystemBarsPadding(top = false, bottom = true)

        prefs = PrefsHelper(this)
        runId = intent.getLongExtra(EXTRA_RUN_ID, 0L)

        binding.map.routeTop = 0.16f
        binding.map.routeBottom = 0.84f
        OsmRoute.setup(binding.osmMap, interactive = false)
        binding.osmMap.overlays.add(routeOverlay)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { share() }
        binding.actionShare.setOnClickListener { share() }
        binding.actionReplay.setOnClickListener { openReplay() }
        binding.actionImage.setOnClickListener {
            startActivity(Intent(this, com.example.runtrail.ui.share.ShareImageActivity::class.java).putExtra(EXTRA_RUN_ID, runId))
        }
        binding.mapSpace.setOnClickListener { openReplay() }
        binding.actionDelete.setOnClickListener { confirmDelete() }
        binding.btnEdit.setOnClickListener { openEdit() }
        binding.cardPhoto.setOnClickListener { openEdit() }
        binding.rowMain.setOnClickListener { openStats() }
        binding.rowSecondary.setOnClickListener { openStats() }
        binding.btnMore.setOnClickListener { showMenu(it) }
        binding.badgeSync.setOnClickListener { explainSync() }

        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_JUST_FINISHED, false)) {
            toast(getString(R.string.detail_saved))
        }
    }

    override fun onPause() {
        super.onPause()
        binding.osmMap.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.osmMap.onResume()
        val run = RunRepository.getById(runId)
        if (run == null) { finish(); return }
        render(run)
    }

    private fun render(run: Run) {
        val miles = prefs.useMiles
        binding.tvTitle.text = run.title
        binding.tvDate.text = Fmt.detailDate(run.startTime, run.endTime ?: (run.startTime + run.movingTimeS * 1000))
        binding.map.setRoute(RunRepository.route(run))
        val real = run.track.size >= 2
        binding.osmMap.visibility = if (real) View.VISIBLE else View.GONE
        binding.map.visibility = if (real) View.GONE else View.VISIBLE
        if (real) {
            routeOverlay.track = run.track
            OsmRoute.fit(binding.osmMap, run.track, padTopPx = dp(80f), padBottomPx = dp(70f))
            binding.osmMap.invalidate()
        }

        val record = RunCalculator.newRecordLabel(run, RunRepository.getAll())
        binding.badgeRecord.visibility = if (record != null) View.VISIBLE else View.GONE
        binding.badgeRecord.text = record
        binding.badgeRecord.setStartIcon(R.drawable.ic_trophy, 14f, R.color.run_green_dark)

        val (bg, color, icon, text) = when (run.syncStatus) {
            SyncStatus.SYNCED -> Quad(R.drawable.bg_pill_green_soft, R.color.run_green_dark, R.drawable.ic_cloud_check, R.string.sync_synced)
            SyncStatus.PENDING -> Quad(R.drawable.bg_pill_warning_soft, R.color.warning, R.drawable.ic_cloud_up, R.string.sync_pending)
            SyncStatus.FAILED -> Quad(R.drawable.bg_pill_danger_soft, R.color.danger, R.drawable.ic_refresh, R.string.sync_failed)
        }
        binding.badgeSync.setBackgroundResource(bg)
        binding.badgeSync.setText(text)
        binding.badgeSync.setTextColor(getColor(color))
        binding.badgeSync.setStartIcon(icon, 14f, color)

        large(binding.statDistance, Fmt.distance(run.distanceM, miles), Fmt.unit(miles), R.string.label_distance)
        large(binding.statTime, Fmt.duration(run.movingTimeS), "", R.string.label_moving_time)
        large(binding.statPace, Fmt.pace(RunCalculator.pace(run), miles), Fmt.paceUnit(miles), R.string.label_avg_pace)
        small(binding.statKcal, Fmt.number(run.calories), getString(R.string.unit_kcal), R.string.label_calories)
        small(binding.statElevation, "+${run.elevationGainM}", getString(R.string.unit_m), R.string.label_elevation_gain)
        small(binding.statCadence, if (run.cadenceSpm > 0) run.cadenceSpm.toString() else "--", getString(R.string.unit_spm), R.string.label_cadence)

        val photo = ImageStore.load(run.photoUri)
        binding.photoFrame.clipToOutline = true
        binding.ivPhoto.visibility = if (photo != null) View.VISIBLE else View.GONE
        binding.ivPhotoIcon.visibility = if (photo != null) View.GONE else View.VISIBLE
        binding.ivPhoto.setImageBitmap(photo)
        binding.tvPhotoDesc.setText(if (photo != null) R.string.detail_photo_has else R.string.detail_photo_none)
    }

    /** แตะป้ายสถานะ: บอกเหตุผลที่ซิงก์ไม่ผ่าน หรือสถานะในอันดับโลก */
    private fun explainSync() {
        val run = RunRepository.getById(runId, withTrack = false) ?: return
        val msg = when {
            run.syncStatus == SyncStatus.FAILED -> run.syncError ?: getString(R.string.sync_failed)
            !com.example.runtrail.data.Accounts.isLoggedIn && !run.onServer -> getString(R.string.share_login_desc)
            run.syncStatus == SyncStatus.PENDING -> getString(R.string.setting_sync_started)
            run.verified == true -> getString(R.string.detail_lb_counted)
            else -> getString(R.string.detail_lb_not_counted,
                com.example.runtrail.net.RunJson.verifyReasonText(run.verifyReason) ?: getString(R.string.sync_pending))
        }
        toast(msg)
        if (run.syncStatus != SyncStatus.SYNCED) com.example.runtrail.sync.SyncManager.requestSync(this)
    }

    private fun large(s: ViewStatLargeBinding, value: String, unit: String, label: Int) {
        s.tvValue.text = value
        s.tvUnit.text = unit
        s.tvUnit.visibility = if (unit.isEmpty()) View.GONE else View.VISIBLE
        s.tvLabel.setText(label)
    }

    private fun small(s: ViewStatBinding, value: String, unit: String, label: Int) {
        s.tvValue.text = value
        s.tvUnit.text = unit
        s.tvLabel.setText(label)
    }

    private fun share() {
        val run = RunRepository.getById(runId) ?: return
        ShareSheet.show(this, run) { RunRepository.getById(runId)?.let { render(it) } }
    }

    private fun openReplay() {
        startActivity(Intent(this, ReplayActivity::class.java).putExtra(EXTRA_RUN_ID, runId))
    }

    private fun openEdit() {
        startActivity(Intent(this, AddEditRunActivity::class.java).putExtra(EXTRA_RUN_ID, runId))
    }

    private fun openStats() {
        startActivity(Intent(this, RunStatsActivity::class.java).putExtra(EXTRA_RUN_ID, runId))
    }

    private fun confirmDelete() {
        val run = RunRepository.getById(runId) ?: return
        Dialogs.confirmDelete(this, run) { alsoServer ->
            RunRepository.delete(run.id, alsoServer)
            com.example.runtrail.sync.SyncManager.requestSync(this)
            toast(getString(if (alsoServer) R.string.toast_deleted_both else R.string.toast_deleted))
            finish()
        }
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.detail_menu_stats)
            menu.add(0, 2, 1, R.string.action_edit)
            menu.add(0, 3, 2, R.string.action_replay)
            menu.add(0, 4, 3, R.string.action_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> openStats()
                    2 -> openEdit()
                    3 -> openReplay()
                    4 -> confirmDelete()
                }
                true
            }
        }.show()
    }

    private data class Quad(val bg: Int, val color: Int, val icon: Int, val text: Int)

    companion object {
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_JUST_FINISHED = "just_finished"
    }
}
