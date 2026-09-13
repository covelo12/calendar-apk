package com.covelo.calendar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.covelo.calendar.MainActivity
import com.covelo.calendar.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Home-screen "Today" widget — a day-view-styled list built from the same local cache the
 * alert engine uses, so it stays current whether or not the WebView is ever opened. */
class DayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, DayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) updateWidget(context, manager, id)
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_day)

            val zone = ZoneId.of(com.covelo.calendar.BuildConfig.SERVER_TIME_ZONE)
            val today = LocalDate.now(zone)
            val label = "${today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, " +
                today.format(DateTimeFormatter.ofPattern("d MMM"))
            views.setTextViewText(R.id.widgetDateLabel, label)

            val serviceIntent = Intent(context, DayWidgetRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = android.net.Uri.parse("calendarapp://widget/$appWidgetId")
            }
            views.setRemoteAdapter(R.id.widgetList, serviceIntent)
            views.setEmptyView(R.id.widgetList, R.id.widgetEmptyText)

            val openAppIntent = Intent(context, MainActivity::class.java)
            val openAppPendingIntent = PendingIntent.getActivity(
                context, appWidgetId, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setPendingIntentTemplate(R.id.widgetList, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.widgetDateLabel, openAppPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
