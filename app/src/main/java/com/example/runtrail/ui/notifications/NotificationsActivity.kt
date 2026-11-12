package com.example.runtrail.ui.notifications

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.runtrail.App
import com.example.runtrail.R
import com.example.runtrail.data.Accounts
import com.example.runtrail.data.RunRepository
import com.example.runtrail.databinding.ActivityNotificationsBinding
import com.example.runtrail.databinding.ItemNotificationBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.net.RunJson
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.common.applySystemBarsPadding
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.setupEdgeToEdge
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.common.userMessage
import com.example.runtrail.ui.detail.RunDetailActivity
import com.example.runtrail.ui.leaderboard.LeaderboardActivity
import org.json.JSONObject

/**
 * 24 · การแจ้งเตือน — เหตุการณ์จริงจาก server: นับในอันดับแล้ว / ไม่นับ (เพราะอะไร) · สถิติใหม่ ·
 * ถึงเป้ารายสัปดาห์ · มีคนแซงในอันดับ · มีคนเปิดดูลิงก์แชร์
 * เปิดหน้านี้ = อ่านทั้งหมด · ออฟไลน์แสดงรายการล่าสุดที่เก็บไว้ · แตะรายการเพื่อเปิดการวิ่งนั้น
 */
class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private val adapter = Adapter()
    private var loading = false
    private var pendingOpenId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupEdgeToEdge()
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarsPadding(top = true, bottom = true)
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnRefresh.setOnClickListener { load() }
        pendingOpenId = if (savedInstanceState == null) intent.getStringExtra(EXTRA_OPEN_ID) else null
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (!Accounts.isLoggedIn) {
            adapter.submit(emptyList())
            showEmpty(getString(R.string.notif_login), getString(R.string.auth_login)) {
                startActivity(Intent(this, LoginActivity::class.java))
            }
            return
        }
        App.services.prefs.notificationsCache?.let { render(JSONObject(it), fromCache = true) }
        if (loading) return
        loading = true
        if (adapter.itemCount == 0) binding.progress.visibility = View.VISIBLE
        runAsync({
            val page = App.services.api.notifications()
            // อ่านแล้วทั้งหมดเมื่อเปิดหน้านี้ (ตัวที่ยังไม่อ่านยังมีจุดสีเขียวให้เห็นในรอบนี้)
            if (page.optInt("unread_count") > 0) App.services.api.markAllRead()
            page
        }) { result ->
            loading = false
            binding.progress.visibility = View.GONE
            result.onSuccess { page ->
                val cached = JSONObject(page.toString()).apply {
                    val items = getJSONArray("items")
                    for (i in 0 until items.length()) items.getJSONObject(i).put("read", true)
                    put("unread_count", 0)
                }
                App.services.prefs.notificationsCache = cached.toString()
                App.services.prefs.unreadNotifications = 0
                render(page, fromCache = false)
            }.onFailure { e ->
                if (adapter.itemCount == 0) showEmpty(e.userMessage(), getString(R.string.lb_retry)) { load() }
                else showStatus(getString(R.string.notif_offline))
            }
        }
    }

    private fun render(page: JSONObject, fromCache: Boolean) {
        val arr = page.getJSONArray("items")
        val items = List(arr.length()) { arr.getJSONObject(it) }
        adapter.submit(items)
        binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.btnEmptyAction.visibility = View.GONE
        binding.tvEmpty.text = getString(R.string.notif_empty)
        binding.tvStatus.visibility = View.GONE
        if (!fromCache) pendingOpenId?.let { id ->
            pendingOpenId = null
            items.firstOrNull { it.getString("id") == id }?.let { open(it) }
        }
    }

    private fun showStatus(text: String) {
        binding.tvStatus.text = text
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun showEmpty(message: String, action: String?, onAction: () -> Unit) {
        binding.emptyView.visibility = View.VISIBLE
        binding.tvEmpty.text = message
        binding.btnEmptyAction.visibility = if (action != null) View.VISIBLE else View.GONE
        binding.btnEmptyAction.text = action
        binding.btnEmptyAction.setOnClickListener { onAction() }
    }

    /** แตะรายการ: เปิดการวิ่ง (ถ้ายังไม่อยู่ในเครื่องจะซิงก์ลงมาก่อน) หรือเปิดหน้าอันดับ */
    private fun open(n: JSONObject) {
        if (n.optString("type") == "rank_passed") {
            startActivity(Intent(this, LeaderboardActivity::class.java))
            return
        }
        val uuid = if (n.isNull("run_id")) return else n.optString("run_id")
        RunRepository.getByUuid(uuid)?.takeIf { !it.deleted && it.finished }?.let { openRun(it.id); return }
        toast(getString(R.string.notif_downloading))
        runAsync({ App.services.sync.sync(); RunRepository.getByUuid(uuid) }) { r ->
            val run = r.getOrNull()
            if (run != null && !run.deleted) openRun(run.id) else toast(getString(R.string.notif_run_gone))
        }
    }

    private fun openRun(id: Long) {
        startActivity(Intent(this, RunDetailActivity::class.java).putExtra(RunDetailActivity.EXTRA_RUN_ID, id))
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<JSONObject> = emptyList()

        fun submit(list: List<JSONObject>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemNotificationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        inner class Holder(private val b: ItemNotificationBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(n: JSONObject) {
                val type = n.optString("type")
                b.ivIcon.setImageResource(Notifier.icon(type))
                val warn = type == "run_not_counted"
                b.ivIcon.setBackgroundResource(if (warn) R.drawable.bg_icon_chip_warning else R.drawable.bg_icon_chip_green)
                b.ivIcon.imageTintList = ContextCompat.getColorStateList(this@NotificationsActivity,
                    if (warn) R.color.warning else R.color.run_green_dark)
                b.tvTitle.text = n.optString("title")
                b.tvBody.text = n.optString("body")
                b.tvTime.text = try { Fmt.cardDate(RunJson.millis(n.getString("created_at"))) } catch (e: Exception) { "" }
                b.dotUnread.visibility = if (n.optBoolean("read")) View.INVISIBLE else View.VISIBLE
                val tappable = type == "rank_passed" || !n.isNull("run_id")
                b.row.setOnClickListener(if (tappable) View.OnClickListener { open(n) } else null)
                // ต้องตั้งหลัง setOnClickListener: การส่ง null ทำให้ view กลายเป็น clickable เอง
                b.row.isClickable = tappable
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_ID = "open_notification"
    }
}
