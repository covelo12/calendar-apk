package com.covelo.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.sync.EventCache
import com.covelo.calendar.sync.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Single tap target for the To-Do widget's rows — toggling complete or deleting a task. */
class TodoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return

        when (intent.getStringExtra(EXTRA_TODO_ACTION)) {
            ACTION_TOGGLE -> toggleComplete(context, taskId)
            ACTION_DELETE -> deleteTask(context, taskId)
        }
    }

    private fun toggleComplete(context: Context, taskId: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val current = EventCache.loadAll(context)[taskId]
                if (current != null) {
                    val updated = current.copy(completed = !current.completed)
                    EventCache.upsertLocal(context, updated)
                    Prefs.addPendingUpdate(context, taskId)
                    TodoWidgetProvider.updateAll(context)
                    ProgressWidgetProvider.updateAll(context)
                    AlertScheduler.rescheduleAll(context)
                    DayWidgetProvider.updateAll(context)
                    // The PUT deliberately does NOT happen here — same reasoning as the delete
                    // in WidgetActionReceiver: this process can die before a multi-round-trip
                    // network call finishes. SyncWorker retries it with a real execution window.
                    SyncWorker.enqueueOneOff(context)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun deleteTask(context: Context, taskId: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                EventCache.removeLocal(context, taskId)
                Prefs.addPendingDelete(context, taskId)
                TodoWidgetProvider.updateAll(context)
                ProgressWidgetProvider.updateAll(context)
                AlertScheduler.rescheduleAll(context)
                DayWidgetProvider.updateAll(context)
                // Handed to SyncWorker rather than called here — see WidgetActionReceiver.
                SyncWorker.enqueueOneOff(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TODO_ACTION = "todo_action"
        const val EXTRA_TASK_ID = "task_id"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_DELETE = "delete"
    }
}
