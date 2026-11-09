package com.example.runtrail.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.runtrail.R

/**
 * ปุ่ม "กดค้างเพื่อจบ" — วงแหวนสีเหลืองมะนาวเติมเต็มใน 1.5 วินาที ปล่อยก่อนจะย้อนกลับ
 */
class HoldToStopView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onCompleted: (() -> Unit)? = null
    var onHoldStarted: (() -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var completed = false

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * dp; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_stop)!!.mutate().apply {
        setTint(ContextCompat.getColor(context, R.color.white))
    }

    init {
        isClickable = true
        contentDescription = context.getString(R.string.hold_to_finish)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startHold(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { cancelHold(); return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startHold() {
        completed = false
        onHoldStarted?.invoke()
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = ((1f - progress) * HOLD_MS).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
                if (progress >= 1f && !completed) {
                    completed = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    performClick()
                    onCompleted?.invoke()
                }
            }
            start()
        }
    }

    private fun cancelHold() {
        if (completed) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, 0f).apply {
            duration = (progress * 300).toLong()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun reset() {
        animator?.cancel()
        progress = 0f
        completed = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val s = ring.strokeWidth
        val r = RectF(s / 2, s / 2, width - s / 2, height - s / 2)
        ring.color = ContextCompat.getColor(context, R.color.bg_muted)
        canvas.drawArc(r, 0f, 360f, false, ring)
        ring.color = ContextCompat.getColor(context, R.color.run_lime)
        // แสดงวงแหวนบางส่วนตอนยังไม่กด ตาม Figma (บอกว่ากดค้างได้)
        val shown = if (progress == 0f) 0.28f else progress
        ring.alpha = if (progress == 0f) 255 else 255
        canvas.drawArc(r, -90f, 360f * shown, false, ring)

        fill.color = ContextCompat.getColor(context, R.color.run_ink)
        canvas.drawCircle(cx, cy, 34 * dp * (width / (84 * dp)), fill)
        val half = (13 * dp).toInt()
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }

    companion object {
        const val HOLD_MS = 1500L
    }
}
