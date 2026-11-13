package com.example.runtrail.domain

import java.util.Calendar
import java.util.TimeZone

/**
 * Next run-reminder time.
 *
 * Days are a bitmask: bit 0 = Sunday (Calendar.SUNDAY) … bit 6 = Saturday.
 */
object ReminderTime {

    const val ALL_DAYS = 0b1111111

    fun bit(dayOfWeek: Int) = 1 shl (dayOfWeek - Calendar.SUNDAY)

    fun has(mask: Int, dayOfWeek: Int) = mask and bit(dayOfWeek) != 0

    /** Next reminder strictly after [now] on a selected day, in epoch ms, or null if no day is selected. */
    fun next(now: Long, hour: Int, minute: Int, daysMask: Int, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (daysMask and ALL_DAYS == 0) return null
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // Alarms never fire early, so any time up to now belongs to the next day.
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        repeat(7) {
            if (has(daysMask, c.get(Calendar.DAY_OF_WEEK))) {
                // Re-apply the time of day in case the day change crossed a DST shift.
                c.set(Calendar.HOUR_OF_DAY, hour)
                c.set(Calendar.MINUTE, minute)
                return c.timeInMillis
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }
}
