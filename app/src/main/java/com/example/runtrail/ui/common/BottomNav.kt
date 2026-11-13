package com.example.runtrail.ui.common

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R
import com.example.runtrail.ui.history.HistoryActivity
import com.example.runtrail.ui.home.MainActivity
import com.example.runtrail.ui.records.RecordsActivity
import com.example.runtrail.ui.settings.SettingsActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Bottom navigation shared by the top-level tabs, each a separate activity switched without animation.
 * Back from any tab returns to Home; Back on Home needs a second press to exit.
 */
object BottomNav {

    fun setup(activity: AppCompatActivity, nav: BottomNavigationView, selectedId: Int) {
        nav.selectedItemId = selectedId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedId) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_history -> HistoryActivity::class.java
                R.id.nav_leaderboard -> com.example.runtrail.ui.leaderboard.LeaderboardActivity::class.java
                R.id.nav_records -> RecordsActivity::class.java
                R.id.nav_settings -> SettingsActivity::class.java
                else -> return@setOnItemSelectedListener false
            }
            val intent = Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            activity.startActivity(intent)
            activity.noTransition()
            false
        }
        nav.setOnItemReselectedListener { }

        if (selectedId == R.id.nav_home) {
            activity.installExitGuard()
        } else {
            activity.onBackPressedDispatcher.addCallback(activity, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = goHome(activity)
            })
        }
    }

    /** Returns to Home and clears the tabs above it. */
    fun goHome(activity: AppCompatActivity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        activity.finish()
        activity.noTransition()
    }
}
