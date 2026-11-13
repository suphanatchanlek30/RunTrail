package com.example.runtrail.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.runtrail.data.PrefsHelper
import com.example.runtrail.domain.ReminderTime

/**
 * ตั้ง/ยกเลิก Alarm "เตือนไปวิ่ง" ผ่าน AlarmManager (MA13: ReminderScheduler)
 *
 * วงจร: ผู้ใช้บันทึกในหน้าตั้งค่า → [schedule] → ถึงเวลา AlarmManager ปลุก → ส่ง Broadcast
 * → [ReminderReceiver] แสดงแจ้งเตือน แล้วเรียก [schedule] อีกครั้งเพื่อตั้งรอบถัดไป
 * (ไม่ใช้ setRepeating เพราะ Android ไม่รับประกันเวลา และต้องข้ามวันที่ไม่ได้เลือก)
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    const val ACTION_REMIND = "com.example.runtrail.action.REMIND"
    private const val REQUEST_CODE = 7001

    /** ตั้งเวลารอบถัดไปตามค่าที่บันทึกไว้ (ปิดอยู่ = ยกเลิก) · คืนเวลาที่ตั้ง หรือ null */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val prefs = PrefsHelper(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        am.cancel(pi)                                     // ยกเลิกของเดิมก่อนเสมอ (PendingIntent ต้องเหมือนตอนตั้ง)
        if (!prefs.reminderEnabled) return null
        val at = ReminderTime.next(now, prefs.reminderHour, prefs.reminderMinute, prefs.reminderDays) ?: return null
        try {
            if (canExact(context)) {
                // RTC_WAKEUP = เวลานาฬิกาจริง + ปลุก CPU · AllowWhileIdle = ทำงานได้แม้อยู่ในโหมด Doze
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                inexact(am, at, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot schedule exact alarm", e)
            inexact(am, at, pi)
        }
        return at
    }

    /**
     * ยังไม่ได้สิทธิ์ exact alarm (Android 12+) → ให้ระบบเตือนภายใน 10 นาทีหลังเวลาที่ตั้ง (ไม่ต้องขอสิทธิ์)
     * ไม่ใช้ setAndAllowWhileIdle เพราะระบบเลื่อนได้ถึง 1 ชั่วโมง
     */
    private fun inexact(am: AlarmManager, at: Long, pi: PendingIntent) {
        am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
    }

    private const val WINDOW_MS = 10 * 60 * 1000L

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    /** Android 12+ ต้องได้สิทธิ์ SCHEDULE_EXACT_ALARM ก่อน (ผู้ใช้เปิดเองในหน้าตั้งค่าระบบ) */
    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Explicit Intent ชี้ไปที่ ReminderReceiver · FLAG_IMMUTABLE = ผู้รับแก้ Intent ไม่ได้ */
    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
