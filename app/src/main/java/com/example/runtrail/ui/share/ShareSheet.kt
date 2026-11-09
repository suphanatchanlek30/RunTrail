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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.data.Run
import com.example.runtrail.data.RunRepository
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.SheetShareBinding
import com.example.runtrail.domain.Fmt
import com.example.runtrail.net.ApiConfig
import com.example.runtrail.ui.common.dp
import com.example.runtrail.ui.common.setStartIcon
import com.example.runtrail.ui.common.toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.runtrail.ui.common.Sheets
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Calendar
import kotlin.random.Random

/**
 * 13 · Share & Sync — แชร์ลิงก์เว็บของการวิ่ง
 *
 * หมายเหตุช่วง UI: ปุ่ม "ซิงก์ตอนนี้" ยังเป็นตัวจำลอง (ยังไม่ส่ง Volley ไป create.php)
 * เมื่อทำ MA11 จะเปลี่ยนเฉพาะฟังก์ชัน syncNow() / removeFromServer()
 */
object ShareSheet {

    fun show(activity: Activity, run: Run, onChanged: () -> Unit) {
        val b = SheetShareBinding.inflate(LayoutInflater.from(activity))
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(b.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        var current = run
        b.btnRemoveServer.setStartIcon(R.drawable.ic_trash, 16f, R.color.danger)

        fun render() {
            val synced = current.syncStatus == SyncStatus.SYNCED && current.shareCode != null
            val ctx = activity
            if (synced) {
                b.rowSync.setBackgroundResource(R.drawable.bg_tile_green)
                b.ivSync.setImageResource(R.drawable.ic_cloud_check)
                b.ivSync.imageTintList = ContextCompat.getColorStateList(ctx, R.color.run_green_dark)
                b.tvSyncTitle.setText(R.string.share_synced_title)
                b.tvSyncDesc.text = ctx.getString(R.string.share_synced_desc, syncedAt(current), current.shareCode)
                b.tvLink.text = ApiConfig.shareUrl(current.shareCode!!)
                b.tvLink.setTextColor(ctx.getColor(R.color.text_primary))
            } else {
                b.rowSync.setBackgroundResource(R.drawable.bg_tile)
                b.ivSync.setImageResource(if (current.syncStatus == SyncStatus.FAILED) R.drawable.ic_refresh else R.drawable.ic_cloud_up)
                b.ivSync.imageTintList = ContextCompat.getColorStateList(
                    ctx, if (current.syncStatus == SyncStatus.FAILED) R.color.danger else R.color.warning,
                )
                b.tvSyncTitle.setText(if (current.syncStatus == SyncStatus.FAILED) R.string.share_failed_title else R.string.share_pending_title)
                b.tvSyncDesc.setText(R.string.share_pending_desc)
                b.tvLink.setText(R.string.share_no_link)
                b.tvLink.setTextColor(ctx.getColor(R.color.text_disabled))
            }
            b.btnSyncNow.visibility = if (synced) View.GONE else View.VISIBLE
            b.btnCopy.alpha = if (synced) 1f else 0.4f
            b.btnRemoveServer.visibility = if (synced) View.VISIBLE else View.GONE
        }

        fun requireLink(action: (String) -> Unit) {
            val code = current.shareCode
            if (current.syncStatus != SyncStatus.SYNCED || code == null) {
                activity.toast(activity.getString(R.string.share_need_sync))
            } else {
                action("https://" + ApiConfig.shareUrl(code))
            }
        }

        b.btnClose.setOnClickListener { dialog.dismiss() }
        b.btnSyncNow.setOnClickListener {
            b.btnSyncNow.visibility = View.GONE
            b.progressSync.visibility = View.VISIBLE
            Handler(Looper.getMainLooper()).postDelayed({
                current = current.copy(
                    syncStatus = SyncStatus.SYNCED,
                    shareCode = current.shareCode ?: randomCode(),
                    serverId = current.serverId ?: (100L + current.id),
                )
                RunRepository.update(current)
                b.progressSync.visibility = View.GONE
                render()
                onChanged()
            }, 1200)
        }
        val copy = View.OnClickListener {
            requireLink { url ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("RunTrail", url))
                activity.toast(activity.getString(R.string.toast_link_copied))
            }
        }
        b.btnCopy.setOnClickListener(copy)
        b.actCopy.setOnClickListener(copy)
        b.actOther.setOnClickListener {
            requireLink { url ->
                val text = activity.getString(
                    R.string.share_message, current.title, Fmt.distance(current.distanceM), url,
                )
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_title)))
            }
        }
        b.actQr.setOnClickListener { requireLink { url -> showQr(activity, url) } }
        b.actWeb.setOnClickListener {
            requireLink {
                activity.startActivity(
                    Intent(activity, WebPreviewActivity::class.java)
                        .putExtra(WebPreviewActivity.EXTRA_RUN_ID, current.id)
                        .putExtra(WebPreviewActivity.EXTRA_HIDE_ENDS, b.swHideEnds.isChecked)
                        .putExtra(WebPreviewActivity.EXTRA_SHOW_KCAL, b.swKcal.isChecked)
                        .putExtra(WebPreviewActivity.EXTRA_SHOW_PHOTO, b.swPhoto.isChecked),
                )
            }
        }
        b.btnRemoveServer.setOnClickListener {
            Sheets.confirm(
                activity, R.drawable.ic_trash, activity.getString(R.string.share_remove_title),
                activity.getString(R.string.share_remove_body), confirmText = activity.getString(R.string.action_delete),
                cancelText = activity.getString(R.string.action_cancel), tone = Sheets.Tone.DANGER,
            ) {
                current = current.copy(syncStatus = SyncStatus.PENDING, shareCode = null, serverId = null)
                RunRepository.update(current)
                render()
                onChanged()
                activity.toast(activity.getString(R.string.toast_removed_server))
            }
        }

        render()
        dialog.show()
    }

    private fun syncedAt(run: Run): String {
        val t = run.endTime ?: (run.startTime + run.movingTimeS * 1000)
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val months = arrayOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.")
        return "${c.get(Calendar.DAY_OF_MONTH)} ${months[c.get(Calendar.MONTH)]} ${Fmt.time(t)}"
    }

    private fun randomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    /** QR Code ของลิงก์ (สร้างในเครื่องด้วย ZXing) */
    private fun showQr(context: Context, url: String) {
        val size = context.dp(240f)
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dark = ContextCompat.getColor(context, R.color.run_ink)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) dark else Color.WHITE)

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
            text = url.removePrefix("https://")
            box.addView(this, LinearLayout.LayoutParams(-2, -2).apply { topMargin = context.dp(12f) })
        }
        Dialog(context, R.style.Theme_RunTrail_FloatingDialog).apply {
            setContentView(box)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            show()
        }
    }
}
