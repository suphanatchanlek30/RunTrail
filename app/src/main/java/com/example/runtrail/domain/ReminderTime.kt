package com.example.runtrail.domain

import java.util.Calendar
import java.util.TimeZone

/**
 * คำนวณเวลาเตือนไปวิ่งรอบถัดไป (MA13: เทียบกับ nextDailyTime() ในสไลด์)
 *
 * วันที่เลือกเก็บเป็น bitmask: bit 0 = อาทิตย์ (Calendar.SUNDAY = 1) … bit 6 = เสาร์ (Calendar.SATURDAY = 7)
 * ไม่ได้ใช้ repeating alarm — ทุกครั้งที่เตือนแล้ว Receiver จะตั้งรอบถัดไปใหม่จากฟังก์ชันนี้
 */
object ReminderTime {

    const val ALL_DAYS = 0b1111111

    fun bit(dayOfWeek: Int) = 1 shl (dayOfWeek - Calendar.SUNDAY)

    fun has(mask: Int, dayOfWeek: Int) = mask and bit(dayOfWeek) != 0

    /**
     * เวลา (epoch millis) ของรอบถัดไปที่อยู่หลัง [now] ในวันที่เลือกไว้
     * คืน null เมื่อไม่ได้เลือกวันไหนเลย
     */
    fun next(now: Long, hour: Int, minute: Int, daysMask: Int, tz: TimeZone = TimeZone.getDefault()): Long? {
        if (daysMask and ALL_DAYS == 0) return null
        val c = Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // ถึงเวลาแล้ว/ผ่านไปแล้ว → เริ่มนับจากพรุ่งนี้ (alarm ไม่มีวันมาก่อนเวลา จึงไม่ต้องเผื่อ:
        // ถ้าเผื่อไว้ การเปิดแอปในนาทีก่อนเวลาเตือนจะทำให้วันนี้ถูกข้าม)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        repeat(7) {
            if (has(daysMask, c.get(Calendar.DAY_OF_WEEK))) {
                // วันที่เปลี่ยนเวลาออมแสงอาจเลื่อนชั่วโมง → ตั้งชั่วโมง/นาทีซ้ำหลังเลื่อนวัน
                c.set(Calendar.HOUR_OF_DAY, hour)
                c.set(Calendar.MINUTE, minute)
                return c.timeInMillis
            }
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }
}
