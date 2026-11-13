package com.example.runtrail.ui.common

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.example.runtrail.R

/**
 * Shows a "server is waking up" hint if [stillWaiting] is still true after [delayMs],
 * so a cold free-tier server doesn't look like a frozen app.
 */
fun Activity.showWakeHintIfSlow(delayMs: Long = WAKE_HINT_DELAY_MS, stillWaiting: () -> Boolean) {
    Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing && !isDestroyed && stillWaiting()) toast(getString(R.string.server_waking))
    }, delayMs)
}

const val WAKE_HINT_DELAY_MS = 6_000L
