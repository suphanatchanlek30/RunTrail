package com.example.runtrail.ui.common

import android.app.Activity
import android.content.Intent
import com.example.runtrail.R
import com.example.runtrail.ui.history.HistoryActivity
import com.example.runtrail.ui.home.MainActivity
import com.example.runtrail.ui.records.RecordsActivity
import com.example.runtrail.ui.settings.SettingsActivity
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Bottom navigation ที่ใช้ร่วมกัน 4 หน้า (หน้าหลัก · ประวัติ · สถิติ · ตั้งค่า)
 * แต่ละแท็บเป็น Activity แยกตามแบบในคาบเรียน และสลับแท็บโดยไม่มีแอนิเมชัน
 */
object BottomNav {

    fun setup(activity: Activity, nav: BottomNavigationView, selectedId: Int) {
        nav.selectedItemId = selectedId
        nav.setOnItemSelectedListener { item ->
            if (item.itemId == selectedId) return@setOnItemSelectedListener true
            val target = when (item.itemId) {
                R.id.nav_home -> MainActivity::class.java
                R.id.nav_history -> HistoryActivity::class.java
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
    }
}
