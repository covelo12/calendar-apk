package com.covelo.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.covelo.calendar.MainActivity
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.sync.EventCache
import com.covelo.calendar.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single target for every tap inside the widget's event list (RemoteViews only allows one
 * PendingIntent template per collection view — see handoff on widget interactivity). Branches
 * on ACTION_EXTRA to either open the app or delete the tapped event.
 */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_WIDGET_ACTION)) {
            ACTION_OPEN -> {
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(openIntent)
            }
            ACTION_DELETE -> {
                val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        EventCache.removeLocal(context, eventId)
                        Prefs.addPendingDelete(context, eventId)
                        AlertScheduler.rescheduleAll(context)
                        DayWidgetProvider.updateAll(context)
                        TodoWidgetProvider.updateAll(context)
                        ProgressWidgetProvider.updateAll(context)
                        // The DELETE deliberately does NOT happen here. This process can be killed
                        // as soon as onReceive returns, and the call can need several round trips
                        // (the server's session store is in-memory, so a redeploy forces a full
                        // re-auth first) against a host that cold-starts — reliably longer than a
                        // receiver survives, which is why deleting from the widget kept failing.
                        // SyncWorker owns the retry and has a real execution window.
                        SyncWorker.enqueueOneOff(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_WIDGET_ACTION = "widget_action"
        const val EXTRA_EVENT_ID = "event_id"
        const val ACTION_OPEN = "open"
        const val ACTION_DELETE = "delete"
    }
}
