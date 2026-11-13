package com.example.runtrail.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.runtrail.service.RunTrackingService

/** Fires when the reminder alarm goes off: notifies unless a run is in progress, then schedules the next one. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return
        if (!RunTrackingService.isRunning) ReminderNotifier.show(context)
        ReminderScheduler.schedule(context)
    }
}
