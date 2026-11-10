package com.example.runtrail.ui.common

import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.runtrail.R

/**
 * กันการออกจากแอปโดยไม่ตั้งใจ (CS364 เกณฑ์ 4.2)
 * กด Back ครั้งแรกจะยังไม่ออก แต่ขึ้นข้อความ "กด Back อีกครั้งเพื่อออก"
 * ถ้ากดซ้ำภายใน 2 วินาทีจึงออกจากแอป ถ้าเกิน 2 วินาทีจะเริ่มนับใหม่
 *
 * @param onlyWhenTaskRoot true = ทำงานเฉพาะเมื่อหน้านี้อยู่ล่างสุดของ task (กด Back แล้วจะออกจากแอป)
 *                         ถ้ายังมีหน้าก่อนหน้าอยู่ Back จะย้อนกลับตามปกติ
 */
fun AppCompatActivity.installExitGuard(onlyWhenTaskRoot: Boolean = false) {
    var lastBackAt = 0L
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (onlyWhenTaskRoot && !isTaskRoot) {
                // ไม่ใช่หน้าสุดท้าย → ปล่อยให้ระบบย้อนกลับตามปกติ
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

/** ระยะเวลาที่ต้องกด Back ครั้งที่สองเพื่อยืนยันการออก */
const val EXIT_WINDOW_MS = 2_000L
