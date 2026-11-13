package com.example.runtrail.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.runtrail.service.RunTrackingService

/**
 * BroadcastReceiver ที่ AlarmManager เรียกเมื่อถึงเวลาเตือน (MA13: ReminderReceiver)
 * exported="false" ใน Manifest — รับเฉพาะ PendingIntent ของแอปเอง
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        // กำลังวิ่งอยู่แล้ว ไม่ต้องเตือน
        if (!RunTrackingService.isRunning) ReminderNotifier.show(context)
        // ตั้งรอบถัดไปทุกครั้ง (วันถัดไปที่เลือกไว้)
        ReminderScheduler.schedule(context)
    }
}
