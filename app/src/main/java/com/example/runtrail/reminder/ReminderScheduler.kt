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
 * Schedules the run reminder with [AlarmManager].
 *
 * One alarm is set at a time; [ReminderReceiver] schedules the next occurrence after each one,
 * which also skips unselected days.
 */
object ReminderScheduler {

    private const val TAG = "ReminderScheduler"
    const val ACTION_REMIND = "com.example.runtrail.action.REMIND"
    private const val REQUEST_CODE = 7001

    /** Schedules the next reminder from saved settings, or cancels it when disabled. Returns the trigger time or null. */
    fun schedule(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val prefs = PrefsHelper(context)
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pendingIntent(context)
        am.cancel(pi)
        if (!prefs.reminderEnabled) return null
        val at = ReminderTime.next(now, prefs.reminderHour, prefs.reminderMinute, prefs.reminderDays) ?: return null
        try {
            if (canExact(context)) {
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
     * Fallback without the exact-alarm permission: fires within 10 minutes of [at].
     * Preferred over setAndAllowWhileIdle, which may be deferred by up to an hour.
     */
    private fun inexact(am: AlarmManager, at: Long, pi: PendingIntent) {
        am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
    }

    private const val WINDOW_MS = 10 * 60 * 1000L

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
    }

    /** Exact alarms need the user-granted SCHEDULE_EXACT_ALARM permission on Android 12+. */
    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
