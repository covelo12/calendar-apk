package com.covelo.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.covelo.calendar.MainActivity
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.ApiClient
import com.covelo.calendar.sync.EventCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    var failed = false
                    try {
                        EventCache.removeLocal(context, eventId)
                        AlertScheduler.rescheduleAll(context)
                        DayWidgetProvider.updateAll(context)
                        val res = ApiClient.authedRequest(context, "/events/$eventId", "DELETE")
                        failed = res.statusCode !in 200..299
                    } catch (e: Exception) {
                        failed = true
                    } finally {
                        if (failed) {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Delete failed — will retry next sync", Toast.LENGTH_SHORT).show()
                            }
                        }
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
