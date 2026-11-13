package com.example.runtrail.net

import android.net.Uri

/**
 * รวม URL ของ API ที่หน้าจอเรียกผ่าน Volley ไว้ที่เดียว (MA11: ApiConfig)
 *
 * ต่างจากสไลด์ตรงที่ BASE_URL ไม่ใช่ค่าคงที่ — ผู้ใช้เปลี่ยนที่อยู่เซิร์ฟเวอร์ได้ในหน้าเข้าสู่ระบบ
 * จึงอ่านค่าล่าสุดทุกครั้งที่สร้าง URL ([baseUrl] ถูกตั้งตอนเปิดแอป)
 */
object ApiConfig {

    /** ที่อยู่เซิร์ฟเวอร์ปัจจุบัน เช่น http://10.0.2.2:8080 (ตั้งใน Services) */
    @Volatile var baseUrl: () -> String = { "" }

    val BASE_URL: String get() = baseUrl().trimEnd('/') + "/api/v1"

    val ME get() = "$BASE_URL/me"
    val CHANGE_PASSWORD get() = "$BASE_URL/auth/change-password"
    val NOTIFICATIONS get() = "$BASE_URL/notifications?limit=50"
    val NOTIFICATIONS_READ get() = "$BASE_URL/notifications/read"

    fun leaderboard(metric: String, period: String, scope: String) =
        "$BASE_URL/leaderboard?metric=${Uri.encode(metric)}&period=${Uri.encode(period)}&scope=${Uri.encode(scope)}&limit=100"

    fun userSummary(userId: String) = "$BASE_URL/users/${Uri.encode(userId)}/summary"

    fun share(runUuid: String) = "$BASE_URL/runs/${Uri.encode(runUuid)}/share"
}
