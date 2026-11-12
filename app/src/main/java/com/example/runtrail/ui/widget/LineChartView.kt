package com.example.runtrail.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.runtrail.R

/**
 * กราฟเส้นเพซ/ความสูงตลอดเส้นทาง (Canvas) — โหมดเพซจะกลับแกน ให้ "เร็ว" อยู่ด้านบน
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private var values: List<Float> = emptyList()
    private var invert = true
    private var color = ContextCompat.getColor(context, R.color.run_green)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * dp
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val area = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1 * dp
        color = ContextCompat.getColor(context, R.color.divider)
        pathEffect = DashPathEffect(floatArrayOf(4 * dp, 4 * dp), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    // path/gradient สร้างใหม่เฉพาะตอนข้อมูลหรือขนาดเปลี่ยน ไม่ใช่ทุกครั้งที่วาด (AUDIT P5)
    private val path = Path()
    private val fillPath = Path()
    private var builtFor = -1

    fun setData(values: List<Float>, invert: Boolean, color: Int) {
        this.values = values
        this.invert = invert
        this.color = color
        builtFor = -1
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        builtFor = -1
    }

    override fun onDraw(canvas: Canvas) {
        if (values.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 6 * dp
        val min = values.min()
        val max = values.max()
        val range = (max - min).takeIf { it > 0f } ?: 1f
        fun x(i: Int) = i * w / (values.size - 1)
        fun y(v: Float): Float {
            val t = (v - min) / range
            return if (invert) pad + t * (h - 2 * pad) else h - pad - t * (h - 2 * pad)
        }

        for (k in 0..2) canvas.drawLine(0f, pad + k * (h - 2 * pad) / 2f, w, pad + k * (h - 2 * pad) / 2f, grid)

        if (builtFor != values.hashCode() xor width xor (height shl 16)) {
            builtFor = values.hashCode() xor width xor (height shl 16)
            path.reset()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            fillPath.set(path)
            fillPath.lineTo(w, h); fillPath.lineTo(0f, h); fillPath.close()
            area.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x44000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }
        canvas.drawPath(fillPath, area)
        line.color = color
        canvas.drawPath(path, line)

        // จุดที่ดีที่สุด (เพซเร็วสุด / จุดสูงสุด)
        val bestIndex = if (invert) values.indexOf(min) else values.indexOf(max)
        dot.color = ContextCompat.getColor(context, R.color.white)
        canvas.drawCircle(x(bestIndex), y(values[bestIndex]), 6 * dp, dot)
        dot.color = color
        canvas.drawCircle(x(bestIndex), y(values[bestIndex]), 4 * dp, dot)
    }
}
