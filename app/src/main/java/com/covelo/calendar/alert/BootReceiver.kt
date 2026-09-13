package com.covelo.calendar.alert

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.covelo.calendar.sync.SyncWorker

/** All AlarmManager alarms are wiped on reboot (handoff §6) — reschedule immediately from the
 * local cache, then kick off a fresh sync in case events changed while the phone was off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AlertScheduler.rescheduleAll(context)
        SyncWorker.enqueueOneOff(context)
    }
}
