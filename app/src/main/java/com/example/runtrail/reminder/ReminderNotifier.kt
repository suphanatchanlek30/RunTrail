package com.example.runtrail.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.runtrail.R
import com.example.runtrail.ui.home.MainActivity

/** สร้าง Channel และแสดงการแจ้งเตือน "ได้เวลาวิ่ง" (MA13: NotificationHelper) */
object ReminderNotifier {

    const val CHANNEL_REMINDER = "run_reminder"
    private const val NOTIFY_ID = 44

    /** Android 8.0+ ต้องมี Channel ก่อนแจ้งเตือน · เรียกซ้ำได้ปลอดภัย */
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, context.getString(R.string.channel_reminder), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun show(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        // Android 13+ ต้องได้ POST_NOTIFICATIONS ก่อน (ประกาศใน Manifest อย่างเดียวไม่พอ)
        if (!nm.areNotificationsEnabled()) return
        createChannel(context)
        // แตะการแจ้งเตือน → เปิดหน้าหลัก (PendingIntent.getActivity)
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, NOTIFY_ID, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(context.getString(R.string.reminder_notif_title))
            .setContentText(context.getString(R.string.reminder_notif_body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFY_ID, n)
        } catch (e: SecurityException) {
            Log.w("ReminderNotifier", "notification permission revoked", e)
        }
    }
}
