package com.example.runtrail.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.CompoundButton
import android.widget.Switch
import androidx.core.content.ContextCompat
import com.example.runtrail.R

/** 46×28 dp toggle with a rounded track and a white knob. A [CompoundButton], so it works like a Switch. */
class ToggleSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : CompoundButton(context, attrs) {

    private var animator: ValueAnimator? = null
    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
        setShadowLayer(1.5f * dp, 0f, 0.5f * dp, 0x33000000)
    }
    private val onColor = ContextCompat.getColor(context, R.color.run_green)
    private val offColor = ContextCompat.getColor(context, R.color.grabber)

    init {
        buttonDrawable = null
        background = null
        text = null
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // software layer so the knob shadow renders
    }

    override fun setChecked(checked: Boolean) {
        val changed = checked != isChecked
        super.setChecked(checked)
        // Fields are not initialised yet during construction; skip the animation.
        if (changed && isAttachedToWindow && isLaidOut) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(if (checked) 0f else 1f, if (checked) 1f else 0f).apply {
                duration = 180
                addUpdateListener { invalidate() }
                start()
            }
        } else {
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((46 * dp).toInt(), widthMeasureSpec)
        val h = resolveSize((28 * dp).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private val trackRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val pos = (animator?.takeIf { it.isRunning }?.animatedValue as? Float) ?: if (isChecked) 1f else 0f
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2f

        track.color = blend(offColor, onColor, pos)
        canvas.drawRoundRect(trackRect.apply { set(0f, 0f, w, h) }, r, r, track)

        val inset = 3 * dp
        val knobR = r - inset
        val cx = inset + knobR + (w - 2 * inset - 2 * knobR) * pos
        canvas.drawCircle(cx, r, knobR, knob)
    }

    override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
