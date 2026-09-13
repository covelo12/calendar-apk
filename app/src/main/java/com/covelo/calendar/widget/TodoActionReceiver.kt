package com.covelo.calendar.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.ApiClient
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.sync.EventCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
            var failed = false
            try {
                val current = EventCache.loadAll(context)[taskId]
                if (current != null) {
                    val updated = current.copy(completed = !current.completed)
                    EventCache.upsertLocal(context, updated)
                    TodoWidgetProvider.updateAll(context)
                    ProgressWidgetProvider.updateAll(context)
                    AlertScheduler.rescheduleAll(context)
                    DayWidgetProvider.updateAll(context)

                    val body = JSONObject().apply { put("events", JSONArray().put(updated.toServerJson())) }
                    val res = ApiClient.authedRequest(context, "/events", "PUT", body.toString())
                    failed = res.statusCode !in 200..299
                    if (failed) android.util.Log.e("TodoAction", "Toggle failed: HTTP ${res.statusCode} ${res.body}")
                }
            } catch (e: Exception) {
                failed = true
                android.util.Log.e("TodoAction", "Toggle threw", e)
            } finally {
                if (failed) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Update failed — will retry next sync", Toast.LENGTH_SHORT).show()
                    }
                }
                pending.finish()
            }
        }
    }

    private fun deleteTask(context: Context, taskId: String) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var failed = false
            try {
                EventCache.removeLocal(context, taskId)
                Prefs.addPendingDelete(context, taskId)
                TodoWidgetProvider.updateAll(context)
                ProgressWidgetProvider.updateAll(context)
                AlertScheduler.rescheduleAll(context)
                DayWidgetProvider.updateAll(context)
                val res = ApiClient.authedRequest(context, "/events/$taskId", "DELETE")
                failed = res.statusCode !in 200..299 && res.statusCode != 404
                if (!failed) Prefs.removePendingDelete(context, taskId)
                if (failed) android.util.Log.e("TodoAction", "Delete failed: HTTP ${res.statusCode} ${res.body}")
            } catch (e: Exception) {
                failed = true
                android.util.Log.e("TodoAction", "Delete threw", e)
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

    companion object {
        const val EXTRA_TODO_ACTION = "todo_action"
        const val EXTRA_TASK_ID = "task_id"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_DELETE = "delete"
    }
}
