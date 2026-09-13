package com.covelo.calendar.widget

import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.covelo.calendar.R

class DayWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = DayWidgetFactory(applicationContext)
}

private class DayWidgetFactory(private val context: android.content.Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<WidgetItem> = emptyList()

    override fun onCreate() {}
    override fun onDataSetChanged() {
        items = WidgetItems.today(context)
    }
    override fun onDestroy() {}

    override fun getCount(): Int = items.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val item = items[position]
        val views = RemoteViews(context.packageName, R.layout.widget_day_item)

        views.setTextViewText(R.id.itemTime, item.timeLabel)
        views.setTextViewText(R.id.itemTitle, item.title)
        views.setInt(R.id.itemColorDot, "setColorFilter", parseColor(item.color))
        // Important (alarm-style) items get a highlighted accent bar so they stand out from
        // routine day-to-day entries — matches the same red used across the web app and app icon.
        views.setInt(R.id.itemAccent, "setBackgroundColor", if (item.important) Color.parseColor("#E67E80") else Color.TRANSPARENT)
        views.setViewVisibility(R.id.itemImportantMark, if (item.important) android.view.View.VISIBLE else android.view.View.GONE)
        views.setFloat(R.id.itemTitle, "setAlpha", if (item.completed) 0.5f else 1f)

        val fillInIntent = Intent()
        views.setOnClickFillInIntent(R.id.itemTitle, fillInIntent)

        return views
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.parseColor("#7FBBB3")
    }
}
