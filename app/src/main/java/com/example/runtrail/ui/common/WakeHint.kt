package com.example.runtrail.ui.common

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.example.runtrail.R

/**
 * ถ้ายังรอเซิร์ฟเวอร์อยู่หลัง [delayMs] → บอกผู้ใช้ว่าเซิร์ฟเวอร์กำลังตื่น (โฮสต์ฟรีใช้ ~1 นาที) จะได้ไม่คิดว่าแอปค้าง
 * เรียกตอนเริ่มรอ แล้วส่ง [stillWaiting] ที่คืน true ระหว่างที่ยังรอ
 */
fun Activity.showWakeHintIfSlow(delayMs: Long = WAKE_HINT_DELAY_MS, stillWaiting: () -> Boolean) {
    Handler(Looper.getMainLooper()).postDelayed({
        if (!isFinishing && !isDestroyed && stillWaiting()) toast(getString(R.string.server_waking))
    }, delayMs)
}

const val WAKE_HINT_DELAY_MS = 6_000L
