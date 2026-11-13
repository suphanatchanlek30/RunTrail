package com.example.runtrail.ui.common

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.runtrail.R
import com.example.runtrail.data.Run
import com.example.runtrail.data.SyncStatus
import com.example.runtrail.databinding.DialogDeleteBinding
import com.example.runtrail.databinding.DialogRecoverBinding
import com.example.runtrail.domain.Fmt

/** Custom-styled dialogs: recover an unfinished run and confirm deletion. */
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

    /** Confirms deleting [run]; [onDelete] receives whether to delete it from the server too. */
    fun confirmDelete(context: Context, run: Run, onDelete: (alsoServer: Boolean) -> Unit) {
        val b = DialogDeleteBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root)
        b.tvBody.text = context.getString(
            R.string.delete_body,
            run.title,
            Fmt.distance(run.distanceM),
        )
        val synced = run.onServer
        b.rowServer.visibility = if (synced) View.VISIBLE else View.GONE
        b.checkServer.buttonTintList = null          // keep the drawable's own colours
        b.checkServer.isChecked = synced
        b.rowServer.setOnClickListener { b.checkServer.toggle() }
        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnDelete.setOnClickListener {
            dialog.dismiss()
            onDelete(b.checkServer.isChecked && synced)
        }
        dialog.show()
    }

    /** Offers to resume, save or discard an unfinished run found at startup. */
    fun recoverRun(
        context: Context,
        draft: Run,
        miles: Boolean,
        onResume: () -> Unit,
        onSave: () -> Unit,
        onDiscard: () -> Unit,
    ) {
        val b = DialogRecoverBinding.inflate(LayoutInflater.from(context))
        val dialog = create(context, b.root, cancelable = false)
        b.tvBody.text = context.getString(R.string.recover_body, Fmt.time(draft.updatedAt))
        b.tvDistance.text = Fmt.distance(draft.distanceM, miles)
        b.tvUnit.text = Fmt.unit(miles)
        b.tvTime.text = Fmt.duration(draft.movingTimeS)
        b.tvPoints.text = draft.track.size.toString()
        b.btnResume.setOnClickListener { dialog.dismiss(); onResume() }
        b.btnSave.setOnClickListener { dialog.dismiss(); onSave() }
        b.btnDiscard.setOnClickListener { dialog.dismiss(); onDiscard() }
        dialog.show()
    }
}
