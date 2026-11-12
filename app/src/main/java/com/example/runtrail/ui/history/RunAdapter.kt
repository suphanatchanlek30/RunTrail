package com.example.runtrail.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.runtrail.R
import com.example.runtrail.data.Run
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.ItemMonthHeaderBinding
import com.example.runtrail.databinding.ItemRunBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.ui.common.RunCardBinder
import com.example.runtrail.ui.common.dp
import com.example.runtrail.ui.common.setStartIcon
import java.util.Calendar

/**
 * RecyclerView.Adapter ของหน้าประวัติ (MA10) — มี 2 แบบ: หัวรายเดือน และการ์ดการวิ่ง
 */
class RunAdapter(
    private val onClick: (Run) -> Unit,
    private val onLongClick: (Run) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val monthMillis: Long, val count: Int, val meters: Double, val seconds: Long, val pending: Int) : Row()
        data class Item(val run: Run) : Row()
    }

    private var rows: List<Row> = emptyList()
    var miles = false
        set(value) { if (field != value) unitChanged = true; field = value }

    fun submit(runs: List<Run>, groupByMonth: Boolean) {
        val out = mutableListOf<Row>()
        if (groupByMonth) {
            runs.groupBy { monthKey(it.startTime) }.forEach { (_, list) ->
                out += Row.Header(
                    list.first().startTime, list.size, list.sumOf { it.distanceM },
                    list.sumOf { it.movingTimeS }, list.count { it.syncStatus != SyncStatus.SYNCED },
                )
                list.forEach { out += Row.Item(it) }
            }
        } else {
            runs.forEach { out += Row.Item(it) }
        }
        // อัปเดตเฉพาะแถวที่เปลี่ยน (เดิม notifyDataSetChanged ผูกการ์ดใหม่ทั้งหมดทุกตัวอักษรที่พิมพ์, AUDIT P3)
        val old = rows
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = out.size
            override fun areItemsTheSame(o: Int, n: Int): Boolean = when (val a = old[o]) {
                is Row.Header -> (out[n] as? Row.Header)?.let { monthKey(it.monthMillis) == monthKey(a.monthMillis) } == true
                is Row.Item -> (out[n] as? Row.Item)?.run?.id == a.run.id
            }
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == out[n] && !unitChanged
        })
        rows = out
        unitChanged = false
        diff.dispatchUpdatesTo(this)
    }

    private var unitChanged = false

    private fun monthKey(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH)
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_RUN

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemMonthHeaderBinding.inflate(inflater, parent, false))
        } else {
            RunHolder(ItemRunBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Item -> (holder as RunHolder).bind(row.run)
        }
    }

    inner class RunHolder(private val b: ItemRunBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(run: Run) {
            RunCardBinder.bind(b, run, miles, decimals = 2)
            val lp = b.root.layoutParams as RecyclerView.LayoutParams
            lp.bottomMargin = b.root.context.dp(14f)
            b.root.layoutParams = lp
            b.root.setOnClickListener { onClick(run) }
            b.root.setOnLongClickListener { onLongClick(run); true }
        }
    }

    inner class HeaderHolder(private val b: ItemMonthHeaderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(h: Row.Header) {
            val ctx = b.root.context
            b.tvMonth.text = Fmt.monthYear(h.monthMillis)
            b.tvSummary.text = ctx.getString(
                R.string.history_month_summary, h.count,
                Fmt.distance(h.meters, miles, 1), Fmt.unit(miles), Fmt.hours(h.seconds),
            )
            b.tvPending.visibility = if (h.pending > 0) View.VISIBLE else View.GONE
            b.tvPending.text = ctx.getString(R.string.history_pending, h.pending)
            b.tvPending.setStartIcon(R.drawable.ic_cloud_up, 14f, R.color.warning)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_RUN = 1
    }
}
