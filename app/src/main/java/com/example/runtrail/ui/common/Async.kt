package com.example.runtrail.ui.common

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.example.runtrail.App
import com.example.runtrail.net.ApiException

private val main = Handler(Looper.getMainLooper())

/**
 * รันงานเครือข่าย/ฐานข้อมูลบน background thread แล้วส่งผลกลับมาที่ main thread
 * (ถ้าหน้าจอถูกปิดไปแล้วจะไม่เรียก done)
 */
fun <T> Activity.runAsync(work: () -> T, done: (Result<T>) -> Unit) {
    App.services.io.execute {
        val result = runCatching(work)
        main.post { if (!isFinishing && !isDestroyed) done(result) }
    }
}

/** ข้อความภาษาไทยของข้อผิดพลาด สำหรับแสดงผู้ใช้ */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> if (isNetwork) "เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ ตรวจสอบอินเทอร์เน็ตหรือที่อยู่เซิร์ฟเวอร์" else message
    else -> message ?: "เกิดข้อผิดพลาด"
}
