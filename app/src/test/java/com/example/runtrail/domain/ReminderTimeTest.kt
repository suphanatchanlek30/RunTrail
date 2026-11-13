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
        // พฤหัสบดี 12 พ.ย. 2026 10:00 → เตือน 18:00 วันเดียวกัน
        assertEquals(at(2026, 11, 12, 18, 0), ReminderTime.next(at(2026, 11, 12, 10, 0), 18, 0, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun alreadyPassedGoesToTomorrow() {
        assertEquals(at(2026, 11, 13, 6, 30), ReminderTime.next(at(2026, 11, 12, 7, 0), 6, 30, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun exactlyNowIsNotRepeated() {
        // Receiver ทำงานตรงเวลาพอดี → ต้องได้รอบของวันถัดไป ไม่ใช่เวลาเดิม (กันเตือนวนซ้ำ)
        assertEquals(at(2026, 11, 13, 18, 0), ReminderTime.next(at(2026, 11, 12, 18, 0), 18, 0, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun appStartJustBeforeReminderKeepsToday() {
        // เปิดแอป 17:59:30 (App.onCreate ตั้งเวลาใหม่) → ต้องยังเตือน 18:00 วันนี้ ไม่ข้ามไปพรุ่งนี้
        val now = at(2026, 11, 12, 17, 59) + 30_000
        assertEquals(at(2026, 11, 12, 18, 0), ReminderTime.next(now, 18, 0, ReminderTime.ALL_DAYS, tz))
    }

    @Test fun skipsUnselectedDays() {
        // เลือกแค่วันเสาร์ · ตอนนี้พฤหัสบดี → เสาร์ 14 พ.ย.
        val sat = ReminderTime.bit(Calendar.SATURDAY)
        assertEquals(at(2026, 11, 14, 7, 0), ReminderTime.next(at(2026, 11, 12, 10, 0), 7, 0, sat, tz))
    }

    @Test fun sameWeekdayNextWeek() {
        // เลือกแค่วันพฤหัสบดี แต่เวลาวันนี้ผ่านไปแล้ว → พฤหัสบดีหน้า
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
