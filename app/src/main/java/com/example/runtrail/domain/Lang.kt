package com.example.runtrail.domain

/**
 * ภาษาของข้อความที่สร้างในโค้ดส่วนที่ไม่มี Context (domain / net) — CS364 เกณฑ์ 3.3
 *
 * หน้าจอใช้ string resource (values = ไทย, values-en = อังกฤษ) ตามปกติ
 * ส่วน Fmt / PaceColor / RunCalculator / ข้อความ error เป็น Kotlin ล้วนที่ทดสอบบน JVM ได้
 * จึงถามภาษาปัจจุบันผ่าน [english] ซึ่ง App ตั้งให้ตอนเริ่ม (ค่าเริ่มต้น = ไทย ใช้ใน unit test)
 */
object Lang {

    /** true = แสดงภาษาอังกฤษ (App ผูกกับภาษาที่ผู้ใช้เลือกใน AppCompatDelegate) */
    @Volatile
    var english: () -> Boolean = { false }

    /** เลือกข้อความตามภาษาปัจจุบัน */
    fun t(th: String, en: String): String = if (english()) en else th
}
