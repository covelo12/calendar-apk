package com.covelo.calendar.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.covelo.calendar.R
import com.covelo.calendar.sync.EventCache

/** Home-screen "Progress" widget — "how many tasks did I do / how many are left", the thing
 * that was asked for from the start of this redesign but only ever shipped as a ring inside the
 * Tasks view on the web side. Counts every task the same way that ring does (all tasks, not just
 * today's), so the two stay consistent with each other. */
class ProgressWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ProgressWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_progress)

            val tasks = try {
                EventCache.loadAll(context).values.filter { it.kind == "task" }
            } catch (e: Exception) {
                android.util.Log.e("ProgressWidget", "Failed to load tasks", e)
                emptyList()
            }
            val total = tasks.size
            val done = tasks.count { it.completed }
            val remaining = total - done
            val pct = if (total > 0) (done * 100) / total else 0

            views.setTextViewText(R.id.progressCount, "$done done · $remaining left")
            views.setProgressBar(R.id.progressBar, 100, pct, false)
            views.setTextViewText(
                R.id.progressSubtitle,
                if (total > 0) "$pct% of $total task${if (total == 1) "" else "s"} complete" else "No tasks yet"
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
