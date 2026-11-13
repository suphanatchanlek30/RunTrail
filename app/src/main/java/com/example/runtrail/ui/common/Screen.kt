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

/** Draws behind the system bars; screens add the insets with [applySystemBarsPadding]. */
fun AppCompatActivity.setupEdgeToEdge(lightStatusBar: Boolean = true) {
    enableEdgeToEdge()
    // Dark theme needs light system bar icons.
    val lightBars = resources.getBoolean(com.example.runtrail.R.bool.light_system_bars)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightStatusBar && lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}

/** Adds system bar insets to the padding defined in XML. */
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

/** Adds the status bar height to the top margin, for buttons floating over a map. */
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

/** Switches tabs without the default transition. */
@Suppress("DEPRECATION")
fun Activity.noTransition() {
    overridePendingTransition(0, 0)
}

/** Sets a start drawable of [sizeDp], optionally tinted. */
fun TextView.setStartIcon(resId: Int, sizeDp: Float = 14f, tintRes: Int? = null) {
    val d = ContextCompat.getDrawable(context, resId)?.mutate() ?: return
    val px = context.dp(sizeDp)
    d.setBounds(0, 0, px, px)
    tintRes?.let { d.setTint(ContextCompat.getColor(context, it)) }
    setCompoundDrawablesRelative(d, null, null, null)
}
