package com.example.runtrail.net

/**
 * ที่อยู่เซิร์ฟเวอร์ (MA11) — ช่วงนี้ยังไม่ต่อ backend
 * เมื่อทำฝั่งเซิร์ฟเวอร์จะเพิ่ม VolleyApi / RunMapper ไว้ในแพ็กเกจนี้
 */
object ApiConfig {
    const val DEFAULT_HOST = "10.0.2.2"
    const val SHARE_HOST = "runtrail.app"

    fun baseUrl(host: String): String = "http://$host/runtrail/"

    fun shareUrl(code: String): String = "$SHARE_HOST/r/$code"
}
