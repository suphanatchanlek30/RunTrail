package com.example.runtrail.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.runtrail.R
import com.example.runtrail.data.DraftStore
import com.example.runtrail.data.Run
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.DialogDeleteBinding
import com.example.runtrail.databinding.DialogRecoverBinding
import com.example.runtrail.domain.Fmt

/**
 * Dialog แบบกำหนดหน้าตาเอง (ตาม Figma) — 18 กู้การวิ่งที่ค้าง · 19 ยืนยันการลบ
 */
object Dialogs {

    private fun create(context: Context, view: View, cancelable: Boolean = true): Dialog =
        Dialog(context, R.style.Theme_RunTrail_FloatingDialog).apply {
            setContentView(view)
            setCancelable(cancelable)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                context.resources.displayMetrics.widthPixels - context.dp(48f),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    /** 19 · ยืนยันการลบ — onDelete(ลบจากเซิร์ฟเวอร์ด้วยไหม) */
    fun confirmDelete(context: Context, run: Run, onDelete: (alsoServer: Boolean) -> Unit) {
        val b = DialogDeleteBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.tvBody.text = context.getString(
            R.string.delete_body,
            run.title,
            Fmt.distance(run.distanceM),
        )
        val synced = run.syncStatus == SyncStatus.SYNCED
        var alsoServer = synced
        b.rowServer.visibility = if (synced) View.VISIBLE else View.GONE
        fun render() {
            b.checkServer.isSelected = alsoServer
            b.ivCheck.visibility = if (alsoServer) View.VISIBLE else View.INVISIBLE
        }
        render()
        b.rowServer.setOnClickListener { alsoServer = !alsoServer; render() }
        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnDelete.setOnClickListener {
            dialog.dismiss()
            onDelete(alsoServer && synced)
        }
        dialog.show()
    }

    /** 18 · พบการวิ่งที่ยังไม่จบ */
    fun recoverRun(
        context: Context,
        draft: DraftStore.Draft,
        miles: Boolean,
        onResume: () -> Unit,
        onSave: () -> Unit,
        onDiscard: () -> Unit,
    ) {
        val b = DialogRecoverBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root, cancelable = false)
        b.tvBody.text = context.getString(R.string.recover_body, Fmt.time(draft.savedAt))
        b.tvDistance.text = Fmt.distance(draft.distanceM, miles)
        b.tvUnit.text = Fmt.unit(miles)
        b.tvTime.text = Fmt.duration(draft.elapsedS)
        b.tvPoints.text = draft.gpsPoints.toString()
        b.btnResume.setOnClickListener { dialog.dismiss(); onResume() }
        b.btnSave.setOnClickListener { dialog.dismiss(); onSave() }
        b.btnDiscard.setOnClickListener { dialog.dismiss(); onDiscard() }
        dialog.show()
    }
}
