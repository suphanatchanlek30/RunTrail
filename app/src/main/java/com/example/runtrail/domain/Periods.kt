package com.example.runtrail.domain

import java.util.Calendar

/** ช่วงเวลาที่ใช้ร่วมกันหลายหน้าจอ (Kotlin ล้วน) */
object Periods {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WEEK_MS = 7 * DAY_MS

    /** เที่ยงคืนของ 6 วันก่อน (ช่วง 7 วันล่าสุด รวมวันนี้) */
    fun startOfWeek(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -6)
    }.timeInMillis

    fun startOfMonth(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
