package com.example.runtrail.ui.common

import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R

/**
 * Requires a second Back press within [EXIT_WINDOW_MS] to leave the app.
 *
 * @param onlyWhenTaskRoot only guard when this is the last activity in the task; otherwise Back navigates normally.
 */
fun AppCompatActivity.installExitGuard(onlyWhenTaskRoot: Boolean = false) {
    var lastBackAt = 0L
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (onlyWhenTaskRoot && !isTaskRoot) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastBackAt < EXIT_WINDOW_MS) {
                finishAffinity()
            } else {
                lastBackAt = now
                toast(getString(R.string.back_again_to_exit))
            }
        }
    })
}

const val EXIT_WINDOW_MS = 2_000L
