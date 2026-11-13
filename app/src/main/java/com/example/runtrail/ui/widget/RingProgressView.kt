package com.example.runtrail.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.runtrail.R

/** Circular progress ring, e.g. sensors available out of total. */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    var progress = 0.75f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var strokeDp = 7f
    var trackColor = ContextCompat.getColor(context, R.color.bg_muted)
    var progressColor = ContextCompat.getColor(context, R.color.run_green)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private val arcRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = strokeDp * dp
        val r = arcRect.apply { set(s / 2, s / 2, width - s / 2, height - s / 2) }
        paint.strokeWidth = s
        paint.color = trackColor
        canvas.drawArc(r, 0f, 360f, false, paint)
        paint.color = progressColor
        canvas.drawArc(r, -90f, 360f * progress, false, paint)
    }
}
