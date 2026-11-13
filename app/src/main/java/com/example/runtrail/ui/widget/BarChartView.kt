package com.example.runtrail.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.runtrail.R
import kotlin.math.max

/** Canvas bar chart for the 7-day activity and monthly distance charts. */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private var values: FloatArray = FloatArray(0)
    private var labels: List<String> = emptyList()
    private var highlight = -1

    /** Draws each bar's value above it. */
    var showValues = false
    var barWidthDp = 30f
    var minBarDp = 6f

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.plex_regular)
    }
    private val valueText = Paint(text).apply {
        typeface = ResourcesCompat.getFont(context, R.font.plex_semibold)
    }

    private val barRect = RectF()

    fun setData(values: FloatArray, labels: List<String>, highlightIndex: Int = -1) {
        this.values = values
        this.labels = labels
        this.highlight = highlightIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val n = values.size
        val labelH = 24 * dp
        val valueH = if (showValues) 22 * dp else 0f
        val chartH = height - labelH - valueH
        val bw = barWidthDp * dp
        val gap = if (n > 1) (width - bw * n) / (n - 1) else 0f
        val maxV = max(values.maxOrNull() ?: 0f, 0.0001f)

        for (i in 0 until n) {
            val left = i * (bw + gap)
            val v = values[i]
            val h = if (v <= 0f) minBarDp * dp else max(chartH * (v / maxV), minBarDp * dp)
            val top = valueH + chartH - h
            bar.color = when {
                v <= 0f -> ContextCompat.getColor(context, R.color.bg_muted)
                showValues && i != highlight -> ContextCompat.getColor(context, R.color.run_green_soft)
                showValues -> ContextCompat.getColor(context, R.color.run_green)
                i == highlight -> ContextCompat.getColor(context, R.color.run_lime)
                else -> ContextCompat.getColor(context, R.color.run_green)
            }
            canvas.drawRoundRect(barRect.apply { set(left, top, left + bw, valueH + chartH) }, 8 * dp, 8 * dp, bar)
            if (showValues && v > 0f) {
                valueText.color = ContextCompat.getColor(context, R.color.text_primary)
                canvas.drawText(v.toInt().toString(), left + bw / 2, top - 6 * dp, valueText)
            }
            text.color = ContextCompat.getColor(
                context, if (i == highlight) R.color.text_primary else R.color.text_tertiary,
            )
            labels.getOrNull(i)?.let { canvas.drawText(it, left + bw / 2, height - 6 * dp, text) }
        }
    }
}
