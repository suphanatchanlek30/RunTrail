package com.example.runtrail.ui.share

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.example.runtrail.App
import com.example.runtrail.R
import com.example.runtrail.data.Accounts
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.SheetShareBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.net.ApiConfig
import com.example.runtrail.net.ApiRequest
import com.example.runtrail.net.VolleyApi
import com.example.runtrail.ui.auth.LoginActivity
import com.example.runtrail.ui.common.Sheets
import com.example.runtrail.ui.common.dp
import com.example.runtrail.ui.common.runAsync
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.toast
import com.example.runtrail.ui.common.userMessage
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.util.Calendar

/**
 * Share sheet: uploads the run if needed, then creates a public link with the chosen privacy options.
 * Privacy (e.g. hiding the first and last 200 m) is applied by the server, not just the web page.
 * Requires a connection; normal sync still happens on its own.
 */
object ShareSheet {

    fun show(activity: Activity, run: Run, onChanged: () -> Unit) {
        val b = SheetShareBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        var current = run
        var busy = false
        var resendOptions = false   // options changed during a request: send them again afterwards
        b.btnRemoveServer.setStartIcon(R.drawable.ic_trash, 16f, R.color.danger)
        b.swHideEnds.isChecked = run.shareHideEnds
        b.swPhoto.isChecked = run.shareShowPhoto
        b.swKcal.isChecked = run.shareShowKcal

        fun render() {
            val ctx = activity
            val loggedIn = Accounts.isLoggedIn
            val linked = current.shareUrl != null
            when {
                !loggedIn -> {
                    b.rowSync.setBackgroundResource(R.drawable.bg_tile)
                    b.ivSync.setImageResource(R.drawable.ic_lock)
                    b.ivSync.imageTintList = ContextCompat.getColorStateList(ctx, R.color.text_secondary)
                    b.tvSyncTitle.setText(R.string.share_login_title)
                    b.tvSyncDesc.setText(R.string.share_login_desc)
                    b.btnSyncNow.setText(R.string.auth_login)
                }
                linked -> {
                    b.rowSync.setBackgroundResource(R.drawable.bg_tile_green)
                    b.ivSync.setImageResource(R.drawable.ic_cloud_check)
                    b.ivSync.imageTintList = ContextCompat.getColorStateList(ctx, R.color.run_green_dark)
                    b.tvSyncTitle.setText(R.string.share_synced_title)
                    b.tvSyncDesc.text = ctx.getString(R.string.share_synced_desc, syncedAt(current), current.shareCode)
                }
                current.syncStatus == SyncStatus.FAILED -> {
                    b.rowSync.setBackgroundResource(R.drawable.bg_tile)
                    b.ivSync.setImageResource(R.drawable.ic_refresh)
                    b.ivSync.imageTintList = ContextCompat.getColorStateList(ctx, R.color.danger)
                    b.tvSyncTitle.setText(R.string.share_failed_title)
                    b.tvSyncDesc.text = current.syncError ?: ctx.getString(R.string.share_pending_desc)
                    b.btnSyncNow.setText(R.string.share_create_link)
                }
                else -> {
                    b.rowSync.setBackgroundResource(R.drawable.bg_tile)
                    b.ivSync.setImageResource(R.drawable.ic_cloud_up)
                    b.ivSync.imageTintList = ContextCompat.getColorStateList(ctx, R.color.warning)
                    b.tvSyncTitle.setText(if (current.syncStatus == SyncStatus.SYNCED) R.string.share_no_link_title else R.string.share_pending_title)
                    b.tvSyncDesc.setText(R.string.share_pending_desc)
                    b.btnSyncNow.setText(R.string.share_create_link)
                }
            }
            b.tvLink.text = current.shareUrl?.removePrefix("https://")?.removePrefix("http://") ?: ctx.getString(R.string.share_no_link)
            b.tvLink.setTextColor(ctx.getColor(if (linked) R.color.text_primary else R.color.text_disabled))
            b.btnSyncNow.visibility = if (linked || busy) View.GONE else View.VISIBLE
            b.progressSync.visibility = if (busy) View.VISIBLE else View.GONE
            b.btnCopy.alpha = if (linked) 1f else 0.4f
            b.btnRemoveServer.visibility = if (linked && !busy) View.VISIBLE else View.GONE
        }

        fun save(updated: Run) {
            RunRepository.update(updated, touch = false)
            current = RunRepository.getById(updated.id, withTrack = false) ?: updated
            onChanged()
        }

        /** Uploads the run if needed, then creates or updates its link with the current options. */
        fun createLink() {
            if (busy) { resendOptions = true; return }
            resendOptions = false
            busy = true; render()
            val hide = b.swHideEnds.isChecked
            val photo = b.swPhoto.isChecked
            val kcal = b.swKcal.isChecked
            fun done(linked: Run?, error: Throwable?) {
                if (activity.isFinishing || activity.isDestroyed) return
                busy = false
                if (linked != null) save(linked) else {
                    RunRepository.getById(current.id, withTrack = false)?.let { r -> current = r }
                    error?.let { activity.toast(it.userMessage()) }
                }
                render()
                val optionsChanged = hide != b.swHideEnds.isChecked || photo != b.swPhoto.isChecked || kcal != b.swKcal.isChecked
                if (resendOptions && optionsChanged && current.shareUrl != null && dialog.isShowing) createLink()
                resendOptions = false
            }
            // Upload first (blocking sync call on a background thread).
            activity.runAsync({
                val synced = App.services.sync.pushNow(current.id) ?: error(activity.getString(R.string.share_run_missing))
                if (!synced.onServer) error(synced.syncError ?: activity.getString(R.string.share_failed_title))
                synced
            }) { pushed ->
                val synced = pushed.getOrElse { done(null, it); return@runAsync }
                val q = ApiRequest(
                    Request.Method.POST, ApiConfig.share(synced.uuid), shareBody(hide, photo, kcal),
                    { o ->
                        done(synced.copy(shareCode = o.getString("code"), shareUrl = o.getString("url"),
                            shareHideEnds = hide, shareShowPhoto = photo, shareShowKcal = kcal), null)
                    },
                    { e -> done(null, e) },
                )
                VolleyApi.getQueue(activity).add(q)
            }
        }

        fun requireLink(action: (String) -> Unit) {
            val url = current.shareUrl
            if (url == null) activity.toast(activity.getString(R.string.share_need_sync)) else action(url)
        }

        b.btnClose.setOnClickListener { dialog.dismiss() }
        b.btnSyncNow.setOnClickListener {
            if (!Accounts.isLoggedIn) {
                dialog.dismiss()
                activity.startActivity(Intent(activity, LoginActivity::class.java))
            } else createLink()
        }
        // Save option changes locally and update an existing link right away.
        listOf(b.swHideEnds, b.swPhoto, b.swKcal).forEach { sw ->
            sw.setOnCheckedChangeListener { _, _ ->
                current = current.copy(shareHideEnds = b.swHideEnds.isChecked, shareShowPhoto = b.swPhoto.isChecked,
                    shareShowKcal = b.swKcal.isChecked)
                RunRepository.update(current, touch = false)
                if (current.shareUrl != null) createLink()
            }
        }
        val copy = View.OnClickListener {
            requireLink { url ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("RunTrail", url))
                activity.toast(activity.getString(R.string.toast_link_copied))
            }
        }
        b.btnCopy.setOnClickListener(copy)
        b.rowLink.setOnClickListener(copy)
        b.actCopy.setOnClickListener(copy)
        b.actOther.setOnClickListener {
            requireLink { url ->
                val text = activity.getString(R.string.share_message, current.title, Fmt.distance(current.distanceM), url)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_title)))
            }
        }
        b.actQr.setOnClickListener { requireLink { url -> showQr(activity, url) } }
        b.actWeb.setOnClickListener {
            requireLink { url ->
                activity.startActivity(
                    Intent(activity, WebPreviewActivity::class.java)
                        .putExtra(WebPreviewActivity.EXTRA_URL, url)
                        .putExtra(WebPreviewActivity.EXTRA_RUN_ID, current.id),
                )
            }
        }
        b.btnRemoveServer.setOnClickListener {
            Sheets.confirm(
                activity, R.drawable.ic_trash, activity.getString(R.string.share_remove_title),
                activity.getString(R.string.share_remove_body), confirmText = activity.getString(R.string.share_remove_confirm),
                cancelText = activity.getString(R.string.action_cancel), tone = Sheets.Tone.DANGER,
            ) {
                busy = true; render()
                val q = ApiRequest(
                    Request.Method.DELETE, ApiConfig.share(current.uuid), null,
                    {
                        if (activity.isDestroyed) return@ApiRequest
                        busy = false
                        save(current.copy(shareCode = null, shareUrl = null))
                        activity.toast(activity.getString(R.string.toast_removed_server))
                        render()
                    },
                    { e ->
                        if (activity.isDestroyed) return@ApiRequest
                        busy = false
                        activity.toast(e.userMessage())
                        render()
                    },
                )
                VolleyApi.getQueue(activity).add(q)
            }
        }

        render()
        dialog.show()

        // Refresh an existing link (the server address may have changed or it was revoked elsewhere); keep the cached one offline.
        if (Accounts.isLoggedIn && current.shareUrl != null && current.onServer) {
            val r = current
            val q = ApiRequest(
                Request.Method.POST, ApiConfig.share(r.uuid), shareBody(r.shareHideEnds, r.shareShowPhoto, r.shareShowKcal),
                { o ->
                    if (!busy && dialog.isShowing && !activity.isDestroyed) {
                        save(current.copy(shareCode = o.getString("code"), shareUrl = o.getString("url")))
                        render()
                    }
                },
                { /* offline: keep the cached link */ },
            )
            VolleyApi.getQueue(activity).add(q)
        }
    }

    /** Body of `POST /runs/{uuid}/share`. */
    private fun shareBody(hideEnds: Boolean, showPhoto: Boolean, showKcal: Boolean) = JSONObject().apply {
        put("hide_ends", hideEnds); put("show_photo", showPhoto); put("show_kcal", showKcal)
    }

    private fun syncedAt(run: Run): String {
        val t = run.endTime ?: (run.startTime + run.movingTimeS * 1000)
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "${c.get(Calendar.DAY_OF_MONTH)} ${Fmt.monthShort(c.get(Calendar.MONTH))} ${Fmt.time(t)}"
    }

    /** QR code for the link, generated on the device. */
    private fun showQr(context: Context, url: String) {
        val size = context.dp(240f)
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dark = ContextCompat.getColor(context, R.color.run_ink)
        // Fill all pixels at once; per-pixel setPixel is far slower.
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            val row = y * size
            for (x in 0 until size) pixels[row + x] = if (matrix[x, y]) dark else Color.WHITE
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.bg_dialog)
            val p = context.dp(24f)
            setPadding(p, p, p, p)
        }
        TextView(context).apply {
            setTextAppearance(R.style.Txt_H2)
            setText(R.string.share_qr)
            box.addView(this, LinearLayout.LayoutParams(-2, -2))
        }
        ImageView(context).apply {
            setImageBitmap(bmp)
            box.addView(this, LinearLayout.LayoutParams(size, size).apply { topMargin = context.dp(16f) })
        }
        TextView(context).apply {
            setTextAppearance(R.style.Txt_Caption)
            text = url.removePrefix("https://").removePrefix("http://")
            box.addView(this, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12f) })
        }
        Dialog(context, R.style.Theme_RunTrail_FloatingDialog).apply {
            setContentView(box)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            show()
        }
    }
}
