package com.example.runtrail.ui.common

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.example.runtrail.App
import com.example.runtrail.domain.Lang
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

/** ข้อความข้อผิดพลาดตามภาษาที่เลือก สำหรับแสดงผู้ใช้ */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> if (isNetwork) Lang.t(
        "เชื่อมต่อเซิร์ฟเวอร์ไม่ได้ ตรวจสอบอินเทอร์เน็ตหรือที่อยู่เซิร์ฟเวอร์",
        "Can't reach the server. Check your internet or server address.",
    ) else if (Lang.english()) SERVER_ERRORS_EN[code] ?: message else message
    else -> message ?: Lang.t("เกิดข้อผิดพลาด", "Something went wrong")
}

/**
 * ข้อความจาก backend เป็นภาษาไทย → ถ้าผู้ใช้เลือกภาษาอังกฤษ แปลงจาก error code ที่พบบ่อย
 * (code ที่ไม่อยู่ในรายการจะแสดงข้อความเดิมจากเซิร์ฟเวอร์)
 */
private val SERVER_ERRORS_EN = mapOf(
    "invalid_credentials" to "Incorrect email or password",
    "account_gone" to "Account not found",
    "missing_token" to "Please sign in",
    "invalid_token" to "Invalid or expired token",
    "invalid_refresh_token" to "Please sign in again",
    "refresh_token_expired" to "Session expired. Please sign in again",
    "refresh_token_reused" to "Token reuse detected. Please sign in again",
    "refresh_token_revoked" to "Please sign in again",
    "rate_limited" to "Too many requests. Please try again shortly",
    "concurrent_upload" to "This run is being saved. Please try again",
    "run_deleted" to "This run has been deleted",
    "run_id_taken" to "This run ID is already used",
    "upload_invalid" to "File is empty or larger than 5 MB",
    "upload_missing" to "Uploaded file not found",
    "validation_failed" to "Some fields are invalid",
)
