package com.covelo.calendar.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings

object NotificationChannels {
    const val ALARM_CHANNEL_ID = "alarms"
    const val REMINDER_CHANNEL_ID = "reminders"

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val alarmSound: Uri = Settings.System.DEFAULT_ALARM_ALERT_URI
        val alarmAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        val alarms = NotificationChannel(ALARM_CHANNEL_ID, "Alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Important items marked as an alarm"
            setSound(alarmSound, alarmAttributes)
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val reminders = NotificationChannel(REMINDER_CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Regular event and task reminders"
        }

        manager.createNotificationChannel(alarms)
        manager.createNotificationChannel(reminders)
    }
}
