package com.example.runtrail.ui.common

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import com.example.runtrail.R
import com.example.runtrail.databinding.DialogConfirmBinding
import com.example.runtrail.databinding.ItemOptionRadioBinding
import com.example.runtrail.databinding.ItemOptionCellBinding
import com.example.runtrail.databinding.SheetInputBinding
import com.example.runtrail.databinding.SheetOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/** App-styled bottom sheets and dialogs: single choice, text input and confirmation. */
object Sheets {

    data class Option(val label: String, val hint: String? = null)

    /**
     * Single-choice bottom sheet.
     * @param columns 1 = list with descriptions; more = grid of compact cells
     */
    fun choose(
        activity: Activity,
        icon: Int,
        title: String,
        subtitle: String?,
        options: List<Option>,
        selected: Int,
        columns: Int = 1,
        alwaysPick: Boolean = false,   // call onPick even for the current value (it may differ from the server)
        onPick: (Int) -> Unit,
    ) {
        val b = SheetOptionsBinding.inflate(LayoutInflater.from(activity))
        val dialog = sheet(activity, b.root)
        b.ivIcon.setImageResource(icon)
        b.tvTitle.text = title
        b.tvSubtitle.text = subtitle
        b.tvSubtitle.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.btnClose.setOnClickListener { dialog.dismiss() }

        val gap = activity.dp(10f)
        var selectedView: View? = null
        fun pick(index: Int) {
            dialog.dismiss()
            if (alwaysPick || index != selected) onPick(index)
        }

        if (columns <= 1) {
            val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
            val ids = IntArray(options.size) { View.generateViewId() }
            val hintSize = activity.resources.getDimensionPixelSize(R.dimen.text_size_s)
            options.forEachIndexed { i, opt ->
                val rb = ItemOptionRadioBinding.inflate(LayoutInflater.from(activity), group, false).root
                rb.id = ids[i]
                rb.text = optionText(activity, opt, checked = i == selected, hintSize)
                group.addView(rb, RadioGroup.LayoutParams(-1, -2).apply { if (i > 0) topMargin = gap })
                // OnClick rather than OnCheckedChange so tapping the current option also closes the sheet.
                rb.setOnClickListener { pick(ids.indexOf(group.checkedRadioButtonId)) }
                if (i == selected) selectedView = rb
            }
            if (selected in options.indices) group.check(ids[selected])
            b.options.addView(group, LinearLayout.LayoutParams(-1, -2))
        } else {
            options.chunked(columns).forEachIndexed { r, chunk ->
                val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until columns) {
                    val i = r * columns + c
                    val opt = chunk.getOrNull(c)
                    val cell = ItemOptionCellBinding.inflate(LayoutInflater.from(activity), line, false)
                    val lp = LinearLayout.LayoutParams(0, activity.dp(68f), 1f).apply { if (c > 0) marginStart = gap }
                    if (opt == null) {
                        cell.root.visibility = View.INVISIBLE
                    } else {
                        cell.tvLabel.text = opt.label
                        cell.tvHint.text = opt.hint
                        cell.tvHint.visibility = if (opt.hint.isNullOrEmpty()) View.GONE else View.VISIBLE
                        cell.root.isSelected = i == selected
                        cell.root.setOnClickListener { pick(i) }
                        if (i == selected) selectedView = line
                    }
                    line.addView(cell.root, lp)
                }
                b.options.addView(line, LinearLayout.LayoutParams(-1, -2).apply { if (r > 0) topMargin = gap })
            }
        }

        // Cap the height at 60% of the screen and scroll to the selected option.
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.6f).toInt()
        b.scroll.post {
            if (b.scroll.height > maxH) b.scroll.layoutParams = b.scroll.layoutParams.apply { height = maxH }
            selectedView?.let { v -> b.scroll.post { b.scroll.scrollTo(0, (v.top - maxH / 3).coerceAtLeast(0)) } }
        }
        dialog.show()
    }

    /** Option label with its hint as a smaller second line. */
    private fun optionText(activity: Activity, opt: Option, checked: Boolean, hintSize: Int): CharSequence {
        if (opt.hint.isNullOrEmpty()) return opt.label
        val hintColor = ContextCompat.getColor(activity, if (checked) R.color.run_green_dark else R.color.text_tertiary)
        return SpannableStringBuilder(opt.label).append("\n").apply {
            val start = length
            append(opt.hint)
            setSpan(AbsoluteSizeSpan(hintSize), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(hintColor), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Single text-field bottom sheet. [validate] returns an error message, or null when valid. */
    fun input(
        activity: Activity,
        icon: Int,
        title: String,
        description: String,
        value: String,
        hint: String,
        validate: (String) -> String?,
        onSave: (String) -> Unit,
    ) {
        val b = SheetInputBinding.inflate(LayoutInflater.from(activity))
        val dialog = sheet(activity, b.root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        b.ivIcon.setImageResource(icon)
        b.tvTitle.text = title
        b.tvDesc.text = description
        b.etValue.setText(value)
        b.etValue.hint = hint
        b.etValue.setSelection(value.length)
        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnSave.setOnClickListener {
            val text = b.etValue.text.toString().trim()
            val error = validate(text)
            if (error != null) {
                b.tvError.text = error
                b.tvError.visibility = View.VISIBLE
            } else {
                dialog.dismiss()
                onSave(text)
            }
        }
        dialog.setOnShowListener {
            b.etValue.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    enum class Tone { NEUTRAL, DANGER, INFO }

    /** Confirmation dialog; a null [cancelText] shows a single button. */
    fun confirm(
        activity: Activity,
        icon: Int,
        title: String,
        body: String,
        confirmText: String,
        cancelText: String?,
        tone: Tone = Tone.NEUTRAL,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit = {},
    ) {
        val b = DialogConfirmBinding.inflate(LayoutInflater.from(activity))
        val dialog = Dialog(activity, R.style.Theme_RunTrail_FloatingDialog).apply {
            setContentView(b.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(activity.resources.displayMetrics.widthPixels - activity.dp(48f), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        b.ivIcon.setImageResource(icon)
        b.tvTitle.text = title
        b.tvBody.text = body
        b.btnConfirm.text = confirmText
        when (tone) {
            Tone.DANGER -> {
                b.iconBox.setBackgroundResource(R.drawable.bg_circle_danger_soft)
                b.ivIcon.imageTintList = ContextCompat.getColorStateList(activity, R.color.danger)
                b.btnConfirm.setBackgroundResource(R.drawable.bg_btn_danger)
                b.btnConfirm.setTextColor(ContextCompat.getColor(activity, R.color.white))
            }
            Tone.INFO, Tone.NEUTRAL -> Unit
        }
        if (cancelText == null) {
            b.btnCancel.visibility = View.GONE
            (b.btnConfirm.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        } else {
            b.btnCancel.text = cancelText
        }
        var confirmed = false
        b.btnCancel.setOnClickListener { dialog.dismiss() }
        b.btnConfirm.setOnClickListener { confirmed = true; dialog.dismiss(); onConfirm() }
        // Cancel button, Back and tapping outside all count as cancel.
        dialog.setOnDismissListener { if (!confirmed) onCancel() }
        dialog.show()
    }

    private fun sheet(activity: Activity, view: View): BottomSheetDialog =
        BottomSheetDialog(activity).apply {
            setContentView(view)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }
}
