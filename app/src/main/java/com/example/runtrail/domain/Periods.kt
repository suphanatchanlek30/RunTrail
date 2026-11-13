package com.example.runtrail.domain

import java.util.Calendar

/** Time ranges shared by several screens. */
object Periods {

    const val DAY_MS = 24L * 60 * 60 * 1000
    const val WEEK_MS = 7 * DAY_MS

    /** Midnight six days ago: the start of the rolling 7-day window that includes today. */
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
