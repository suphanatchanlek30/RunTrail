package com.example.runtrail.ui.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.runtrail.R
import com.example.runtrail.service.RunTrackingService
import org.json.JSONObject

/** Posts server notifications to the system tray. */
object Notifier {

    fun icon(type: String): Int = when (type) {
        "personal_record" -> R.drawable.ic_trophy
        "weekly_goal" -> R.drawable.ic_target
        "rank_passed" -> R.drawable.ic_globe
        "share_viewed" -> R.drawable.ic_link
        "run_not_counted" -> R.drawable.ic_info
        else -> R.drawable.ic_cloud_check
    }

    fun show(context: Context, n: JSONObject) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        val id = n.getString("id")
        val open = Intent(context, NotificationsActivity::class.java)
            .putExtra(NotificationsActivity.EXTRA_OPEN_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, id.hashCode(), open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, RunTrackingService.CHANNEL_UPDATES)
            .setSmallIcon(icon(n.optString("type")))
            .setContentTitle(n.optString("title"))
            .setContentText(n.optString("body"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.optString("body")))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id.hashCode(), notification)
        } catch (e: SecurityException) {
            Log.w("Notifier", "notification permission revoked", e)
        }
    }
}
