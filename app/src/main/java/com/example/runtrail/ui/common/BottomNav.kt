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
 * Bottom navigation ที่ใช้ร่วมกัน 5 หน้า (หน้าหลัก · ประวัติ · อันดับ · สถิติ · ตั้งค่า)
 * แต่ละแท็บเป็น Activity แยกตามแบบในคาบเรียน และสลับแท็บโดยไม่มีแอนิเมชัน
 *
 * Back navigation (CS364 เกณฑ์ 4.1 / 4.2):
 *  - แท็บอื่นที่ไม่ใช่หน้าหลัก: กด Back → กลับหน้าหลักเสมอ (ไม่กระโดดไปแท็บที่เคยเปิดค้างไว้)
 *  - หน้าหลัก: กด Back → ต้องกดซ้ำภายใน 2 วินาทีจึงออกจากแอป
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

    /** กลับหน้าหลัก และปิดแท็บอื่นที่ค้างอยู่ด้านบน (stack จึงเหลือหน้าหลักเป็นหน้าเดียว) */
    fun goHome(activity: AppCompatActivity) {
        activity.startActivity(
            Intent(activity, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
        activity.finish()
        activity.noTransition()
    }
}
