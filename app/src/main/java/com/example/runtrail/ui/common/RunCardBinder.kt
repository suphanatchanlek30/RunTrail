package com.example.runtrail.ui.common

import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.ItemRunBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.domain.RunCalculator

/** Binds a run to the run card used on Home and History. */
object RunCardBinder {

    fun bind(b: ItemRunBinding, run: Run, miles: Boolean, decimals: Int) {
        val ctx = b.root.context
        b.tvTitle.text = run.title
        b.tvDate.text = Fmt.cardDate(run.startTime)
        b.tvDistance.text = "${Fmt.distance(run.distanceM, miles, decimals)} ${Fmt.unit(miles)}"
        b.tvTime.text = Fmt.durationShort(run.movingTimeS)
        b.tvPace.text = Fmt.pace(RunCalculator.pace(run), miles)

        b.thumb.thumbnail = true
        b.thumb.routeTop = 0f
        b.thumb.routeBottom = 1f
        b.thumb.clipToOutline = true
        b.thumb.setRoute(RunRepository.route(run))

        val (icon, color, desc) = when (run.syncStatus) {
            SyncStatus.SYNCED -> Triple(R.drawable.ic_cloud_check, R.color.run_green_dark, R.string.sync_synced)
            SyncStatus.PENDING -> Triple(R.drawable.ic_cloud_up, R.color.warning, R.string.sync_pending)
            SyncStatus.FAILED -> Triple(R.drawable.ic_refresh, R.color.danger, R.string.sync_failed)
        }
        b.ivSync.setImageResource(icon)
        b.ivSync.imageTintList = ContextCompat.getColorStateList(ctx, color)
        b.ivSync.contentDescription = ctx.getString(desc)
    }
}
