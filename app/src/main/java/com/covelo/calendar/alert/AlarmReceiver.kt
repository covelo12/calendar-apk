package com.covelo.calendar.alert

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.covelo.calendar.MainActivity
import com.covelo.calendar.ui.AlarmActivity

/** Fires when an AlarmManager-scheduled alert instant is due. Builds either a full-screen
 * "alarm" notification (rings, wakes the device, shows over the lock screen) or a plain
 * "reminder" notification, per the item's alertStyle — see handoff §6. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Calendar"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val alertStyle = intent.getStringExtra(EXTRA_ALERT_STYLE) ?: "notification"

        AlertKeyStore.addFired(context, key)
        val remaining = AlertKeyStore.scheduledKeys(context) - key
        AlertKeyStore.saveScheduledKeys(context, remaining)

        val notificationId = key.hashCode()
        val manager = NotificationManagerCompat.from(context)

        if (alertStyle == "alarm") {
            val fullScreenIntent = Intent(context, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmActivity.EXTRA_TITLE, title)
                putExtra(AlarmActivity.EXTRA_BODY, body)
                putExtra(AlarmActivity.EXTRA_NOTIFICATION_ID, notificationId)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context, notificationId, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, NotificationChannels.ALARM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(notificationId, notification)
        } else {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context, notificationId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, NotificationChannels.REMINDER_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .build()
            manager.notify(notificationId, notification)
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_ALERT_STYLE = "alert_style"

        fun pendingIntentFor(context: Context, instant: AlertInstant): PendingIntent {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                data = Uri.parse("calendarapp://alert/${instant.key}")
                putExtra(EXTRA_KEY, instant.key)
                putExtra(EXTRA_EVENT_ID, instant.eventId)
                putExtra(EXTRA_TITLE, instant.title)
                putExtra(EXTRA_BODY, instant.body)
                putExtra(EXTRA_ALERT_STYLE, instant.alertStyle)
            }
            return PendingIntent.getBroadcast(
                context, instant.key.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun cancelPendingIntentFor(context: Context, key: String) {
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                data = Uri.parse("calendarapp://alert/$key")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, key.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            context.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
