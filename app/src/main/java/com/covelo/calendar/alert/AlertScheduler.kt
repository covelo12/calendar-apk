package com.covelo.calendar.alert

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.covelo.calendar.BuildConfig
import com.covelo.calendar.MainActivity
import com.covelo.calendar.sync.EventCache
import java.time.ZoneId

/** Recomputes every alert instant from the local cache and reconciles it against what's
 * currently scheduled with AlarmManager: cancels alarms that no longer apply, schedules new
 * or changed ones, and never re-delivers an alert that's already fired (handoff §4-§6). Called
 * after every sync and once on every boot (alarms don't survive a reboot). */
object AlertScheduler {
    private val zone: ZoneId = ZoneId.of(BuildConfig.SERVER_TIME_ZONE)

    fun rescheduleAll(context: Context) {
        val events = EventCache.loadAll(context).values
        val fired = AlertKeyStore.firedKeys(context)
        val now = System.currentTimeMillis()

        val currentInstants = events
            .flatMap { AlertMath.computeInstants(it, zone) }
            .associateBy { it.key }

        val previouslyScheduled = AlertKeyStore.scheduledKeys(context)
        for (staleKey in previouslyScheduled - currentInstants.keys) {
            AlarmReceiver.cancelPendingIntentFor(context, staleKey)
        }

        val nowScheduled = mutableSetOf<String>()
        for ((key, instant) in currentInstants) {
            if (key in fired) continue
            if (now - instant.epochMillis > AlertMath.MAX_OVERDUE_MS) {
                // Too stale to be useful — stop tracking it, but don't fire it (matches server).
                fired.add(key)
                continue
            }
            scheduleAlarm(context, instant)
            nowScheduled.add(key)
        }

        AlertKeyStore.saveScheduledKeys(context, nowScheduled)
        AlertKeyStore.saveFiredKeys(context, fired.intersect(currentInstants.keys))
    }

    private fun scheduleAlarm(context: Context, instant: AlertInstant) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = AlarmReceiver.pendingIntentFor(context, instant)

        if (instant.alertStyle == "alarm") {
            val showIntent = PendingIntent.getActivity(
                context, instant.key.hashCode(),
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(instant.epochMillis, showIntent), pendingIntent)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                // No exact-alarm permission — fall back to an inexact alarm rather than crash;
                // MainActivity prompts the user to grant it (handoff §6).
                alarmManager.set(AlarmManager.RTC_WAKEUP, instant.epochMillis, pendingIntent)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, instant.epochMillis, pendingIntent)
            }
        }
    }
}
