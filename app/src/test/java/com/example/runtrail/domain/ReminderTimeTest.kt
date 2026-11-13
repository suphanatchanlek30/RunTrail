package com.example.runtrail.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderTimeTest {

    private val tz = TimeZone.getTimeZone("Asia/Bangkok")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test fun laterToday() {
        // Thursday 10:00 → 18:00 the same day.
        assertEquals(at(2026, 11, 12, 18, 0), ReminderTime.next(at(2026, 11, 12, 10, 0), 18, 0, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun alreadyPassedGoesToTomorrow() {
        assertEquals(at(2026, 11, 13, 6, 30), ReminderTime.next(at(2026, 11, 12, 7, 0), 6, 30, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun exactlyNowIsNotRepeated() {
        // Called exactly at the reminder time (from the receiver): next day, never the same time again.
        assertEquals(at(2026, 11, 13, 18, 0), ReminderTime.next(at(2026, 11, 12, 18, 0), 18, 0, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun appStartJustBeforeReminderKeepsToday() {
        // App start at 17:59:30 reschedules; today's 18:00 must not be skipped.
        val now = at(2026, 11, 12, 17, 59) + 30_000
        assertEquals(at(2026, 11, 12, 18, 0), ReminderTime.next(now, 18, 0, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun skipsUnselectedDays() {
        // Saturday only, now Thursday → Saturday.
        val sat = ReminderTime.bit(Calendar.SATURDAY)
        assertEquals(at(2026, 11, 14, 7, 0), ReminderTime.next(at(2026, 11, 12, 10, 0), 7, 0, sat, tz))
    }

    @Test fun sameWeekdayNextWeek() {
        // Thursday only, but today's time has passed → next Thursday.
        val thu = ReminderTime.bit(Calendar.THURSDAY)
        assertEquals(at(2026, 11, 19, 6, 0), ReminderTime.next(at(2026, 11, 12, 10, 0), 6, 0, thu, tz))
    }

    @Test fun noDaysMeansNoAlarm() {
        assertNull(ReminderTime.next(at(2026, 11, 12, 10, 0), 18, 0, 0, tz))
    }

    @Test fun bitsMatchCalendarDays() {
        assertEquals(1, ReminderTime.bit(Calendar.SUNDAY))
        assertEquals(64, ReminderTime.bit(Calendar.SATURDAY))
    }
}
