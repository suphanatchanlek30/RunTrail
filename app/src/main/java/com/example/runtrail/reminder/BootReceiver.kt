package com.example.runtrail.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Alarm ทั้งหมดหายเมื่อเครื่อง reboot → รับ BOOT_COMPLETED แล้วตั้งเวลาเตือนกลับ (MA13: BootReceiver)
 * exported="true" เพราะเหตุการณ์มาจากระบบปฏิบัติการ
 *
 * รับเพิ่ม: อัปเดตแอป (alarm ก็หายเช่นกัน) · ผู้ใช้เปลี่ยนเวลา/โซนเวลา · ได้/ถูกถอนสิทธิ์ exact alarm
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> ReminderScheduler.schedule(context)
        }
    }
}
