package com.example.runtrail.ui.leaderboard

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.runtrail.App
import com.example.runtrail.R
import com.example.runtrail.data.Accounts
import com.example.runtrail.databinding.ActivityLeaderboardBinding
import com.example.runtrail.databinding.ItemLeaderboardBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.common.BottomNav
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.common.userMessage
import org.json.JSONObject

/**
 * 22 · อันดับโลก — ระยะรวม / 5K / 10K · สัปดาห์ / เดือน / ทั้งหมด · ทั่วโลก / ประเทศ
 * - ต้องใช้เน็ต แต่เก็บผลล่าสุดไว้แสดงตอนออฟไลน์
 * - นับเฉพาะการวิ่ง GPS ที่ server ตรวจแล้ว (การวิ่งที่เพิ่มเองไม่นับ)
 */
class LeaderboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaderboardBinding
    private val adapter = Adapter()
    private var metric = "distance"
    private var period = "week"
    private var scope = "global"
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.header.applySystemBarsPadding(top = true)
        savedInstanceState?.let {
            metric = it.getString(KEY_METRIC, metric)
            period = it.getString(KEY_PERIOD, period)
            scope = it.getString(KEY_SCOPE, scope)
        }
        BottomNav.setup(this, binding.navContainer.bottomNav, R.id.nav_leaderboard)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.segWeek.setOnClickListener { period = "week"; load() }
        binding.segMonth.setOnClickListener { period = "month"; load() }
        binding.segAll.setOnClickListener { period = "all"; load() }
        binding.chipDistance.setOnClickListener { metric = "distance"; load() }
        binding.chip5k.setOnClickListener { metric = "best_5k"; load() }
        binding.chip10k.setOnClickListener { metric = "best_10k"; load() }
        binding.chipGlobal.setOnClickListener { scope = "global"; load() }
        binding.chipCountry.setOnClickListener { scope = "country"; load() }
        binding.btnRefresh.setOnClickListener { load() }
    }

    override fun onResume() {
        super.onResume()
        binding.navContainer.bottomNav.selectedItemId = R.id.nav_leaderboard
        load()
    }

    private val cacheKey get() = "$metric|$period|$scope"

    private fun renderControls() {
        binding.segWeek.isSelected = period == "week"
        binding.segMonth.isSelected = period == "month"
        binding.segAll.isSelected = period == "all"
        binding.chipDistance.isSelected = metric == "distance"
        binding.chip5k.isSelected = metric == "best_5k"
        binding.chip10k.isSelected = metric == "best_10k"
        binding.chipGlobal.isSelected = scope == "global"
        binding.chipCountry.isSelected = scope == "country"
    }

    private fun load() {
        renderControls()
        if (!Accounts.isLoggedIn) {
            showEmpty(getString(R.string.lb_login_needed), getString(R.string.auth_login)) {
                startActivity(Intent(this, LoginActivity::class.java))
            }
            return
        }
        val prefs = App.services.prefs
        val cached = prefs.leaderboardCache(cacheKey)?.let { JSONObject(it) }
        cached?.let { render(it, offline = false) }
        if (loading) return
        loading = true
        if (cached == null) binding.progress.visibility = View.VISIBLE
        val key = cacheKey
        val m = metric; val p = period; val s = scope
        runAsync({ App.services.api.leaderboard(m, p, s) }) { result ->
            loading = false
            binding.progress.visibility = View.GONE
            result.onSuccess { o ->
                o.put("fetched_at", System.currentTimeMillis())
                prefs.saveLeaderboardCache(key, o.toString())
                if (key == cacheKey) render(o, offline = false)
                else load()   // ผู้ใช้เปลี่ยนตัวกรองระหว่างโหลด
            }.onFailure { e ->
                if (key != cacheKey) { load(); return@onFailure }
                if (cached != null) {
                    render(cached, offline = true)
                    toast(getString(R.string.lb_offline))
                } else {
                    showEmpty(e.userMessage(), getString(R.string.lb_retry)) { load() }
                }
            }
        }
    }

    private fun showEmpty(message: String, action: String?, onAction: (() -> Unit)?) {
        adapter.submit(emptyList(), metric)
        binding.myCard.visibility = View.GONE
        binding.emptyView.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.btnEmptyAction.visibility = if (action != null) View.VISIBLE else View.GONE
        binding.btnEmptyAction.text = action
        binding.btnEmptyAction.setOnClickListener { onAction?.invoke() }
        binding.tvPeriod.text = periodText(null, false)
    }

    private fun render(o: JSONObject, offline: Boolean) {
        val arr = o.optJSONArray("entries")
        val entries = (0 until (arr?.length() ?: 0)).map { Entry.from(arr!!.getJSONObject(it)) }
        adapter.submit(entries, metric)
        binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        binding.btnEmptyAction.visibility = View.GONE
        binding.tvEmpty.text = getString(R.string.lb_empty)
        binding.tvPeriod.text = periodText(o.optLong("fetched_at").takeIf { it > 0 }, offline)

        val me = o.optJSONObject("me")?.let { Entry.from(it) }
        binding.myCard.visibility = View.VISIBLE
        if (me == null) {
            binding.tvMyRank.text = "–"
            binding.tvMyValue.text = ""
            binding.tvMyHint.text = getString(
                if (!App.services.prefs.leaderboardOptIn) R.string.lb_me_hidden else R.string.lb_me_none,
            )
        } else {
            binding.tvMyRank.text = getString(R.string.lb_rank, me.rank)
            binding.tvMyValue.text = format(me.value, metric)
            val ahead = entries.lastOrNull { it.rank < me.rank }
            binding.tvMyHint.text = when {
                me.rank == 1 -> getString(R.string.lb_me_first)
                ahead == null -> getString(R.string.lb_me_total, o.optInt("total"))
                metric == "distance" -> getString(R.string.lb_me_behind_distance, format(ahead.value - me.value, metric))
                else -> getString(R.string.lb_me_behind_time, Fmt.duration((me.value - ahead.value).toLong()))
            }
        }
    }

    private fun periodText(fetchedAt: Long?, offline: Boolean): String {
        val base = getString(
            when (period) {
                "week" -> R.string.lb_reset_week
                "month" -> R.string.lb_reset_month
                else -> R.string.lb_reset_all
            },
        )
        val updated = fetchedAt?.let { " · " + getString(if (offline) R.string.lb_updated_offline else R.string.lb_updated, Fmt.time(it)) } ?: ""
        return base + updated
    }

    private fun format(value: Double, metric: String): String {
        val miles = App.services.prefs.useMiles
        return if (metric == "distance") "${Fmt.distance(value, miles, 1)} ${Fmt.unit(miles)}" else Fmt.duration(value.toLong())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_METRIC, metric)
        outState.putString(KEY_PERIOD, period)
        outState.putString(KEY_SCOPE, scope)
    }

    data class Entry(val rank: Int, val name: String, val country: String?, val value: Double, val isMe: Boolean) {
        companion object {
            fun from(o: JSONObject) = Entry(
                o.optInt("rank"), o.optString("display_name"),
                if (o.isNull("country_code")) null else o.optString("country_code"),
                o.optDouble("value"), o.optBoolean("is_me"),
            )
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<Entry> = emptyList()
        private var metric = "distance"

        fun submit(list: List<Entry>, m: String) {
            items = list; metric = m
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemLeaderboardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val b: ItemLeaderboardBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(e: Entry) {
                b.tvRank.text = e.rank.toString()
                b.tvRank.setTextColor(getColor(if (e.rank <= 3) R.color.run_green_dark else R.color.text_secondary))
                b.tvAvatar.text = e.name.take(1).uppercase()
                b.tvName.text = if (e.isMe) getString(R.string.lb_you, e.name) else e.name
                b.tvCountry.text = e.country ?: ""
                b.tvCountry.visibility = if (e.country == null) View.GONE else View.VISIBLE
                b.tvValue.text = format(e.value, metric)
                b.row.setBackgroundResource(if (e.isMe) R.drawable.bg_tile_green else R.drawable.bg_card)
            }
        }
    }

    companion object {
        private const val KEY_METRIC = "metric"
        private const val KEY_PERIOD = "period"
        private const val KEY_SCOPE = "scope"
    }
}
