package com.example.runtrail.ui.common

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * ตัวช่วยร่วมของทุกหน้าจอ: edge-to-edge + เว้นระยะ status bar / navigation bar
 * (ใน Figma แถบ 9:41 คือ status bar ของระบบ จึงไม่วาดซ้ำ แต่เว้นที่ให้ตามจริง)
 */
fun AppCompatActivity.setupEdgeToEdge(lightStatusBar: Boolean = true) {
    enableEdgeToEdge()
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightStatusBar
        isAppearanceLightNavigationBars = true
    }
}

/** เพิ่ม padding เท่ากับความสูงแถบระบบ โดยคง padding เดิมที่กำหนดใน XML */
fun View.applySystemBarsPadding(top: Boolean = true, bottom: Boolean = false) {
    val startTop = paddingTop
    val startBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.updatePadding(
            top = if (top) startTop + bars.top else startTop,
            bottom = if (bottom) startBottom + bars.bottom else startBottom,
        )
        insets
    }
}

/** เพิ่ม margin ด้านบนเท่ากับ status bar (ใช้กับปุ่มที่ลอยบนแผนที่) */
fun View.applyStatusBarMargin() {
    val lp = layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
    val start = lp.topMargin
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        (v.layoutParams as android.view.ViewGroup.MarginLayoutParams).topMargin = start + bars.top
        v.requestLayout()
        insets
    }
}

fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

/** เปลี่ยนแท็บโดยไม่มีแอนิเมชันเลื่อนหน้า */
@Suppress("DEPRECATION")
fun Activity.noTransition() {
    overridePendingTransition(0, 0)
}

/** ใส่ไอคอนหน้าข้อความด้วยขนาดตาม Figma (ค่าเริ่มต้น 14dp) */
fun TextView.setStartIcon(resId: Int, sizeDp: Float = 14f, tintRes: Int? = null) {
    val d = ContextCompat.getDrawable(context, resId)?.mutate() ?: return
    val px = context.dp(sizeDp)
    d.setBounds(0, 0, px, px)
    tintRes?.let { d.setTint(ContextCompat.getColor(context, it)) }
    setCompoundDrawablesRelative(d, null, null, null)
}
