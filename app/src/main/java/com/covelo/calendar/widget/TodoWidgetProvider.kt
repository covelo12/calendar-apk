package com.covelo.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.covelo.calendar.R

/** Home-screen "To-Do" widget — a flat checkbox list of tasks, separate from the Today
 * schedule widget so a mixed day/task view doesn't get cluttered on a small home screen tile. */
class TodoWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) updateWidget(context, manager, id)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.todoList)
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_todo)

            val serviceIntent = Intent(context, TodoWidgetRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse("calendarapp://todowidget/$appWidgetId")
            }
            views.setRemoteAdapter(R.id.todoList, serviceIntent)
            views.setEmptyView(R.id.todoList, R.id.todoEmptyText)

            val actionPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId + 2_000_000, Intent(context, TodoActionReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.todoList, actionPendingIntent)

            val quickAddPendingIntent = PendingIntent.getActivity(
                context, appWidgetId + 3_000_000,
                Intent(context, QuickAddActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(QuickAddActivity.EXTRA_KIND, "task")
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.todoAddButton, quickAddPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
