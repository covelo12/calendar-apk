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
        items = try {
            WidgetItems.agenda(context)
        } catch (e: Exception) {
            // Any uncaught exception here leaves the widget stuck on its loading placeholder
            // forever, since the host never gets a valid view back — never let that happen.
            android.util.Log.e("DayWidget", "Failed to load today's items", e)
            emptyList()
        }
    }
    override fun onDestroy() {}

    override fun getCount(): Int = items.size
    // Two row types: a plain section header ("Tomorrow, Sep 14") and a normal event/task row.
    // RemoteViewsFactory has no per-position getItemViewType — getViewAt returning one of two
    // distinct layouts is what getViewTypeCount's "2" describes.
    override fun getViewTypeCount(): Int = 2
    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews = try {
        val item = items[position]
        if (item.isHeader) buildHeaderView(item) else buildView(item)
    } catch (e: Exception) {
        android.util.Log.e("DayWidget", "Failed to build row $position", e)
        RemoteViews(context.packageName, R.layout.widget_day_item)
    }

    private fun buildHeaderView(item: WidgetItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_day_header_item)
        views.setTextViewText(R.id.headerLabel, item.title)
        return views
    }

    private fun buildView(item: WidgetItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_day_item)

        views.setTextViewText(R.id.itemTime, item.timeLabel)
        views.setTextViewText(R.id.itemTitle, item.title)
        views.setInt(R.id.itemColorDot, "setColorFilter", parseColor(item.color))
        // Important (alarm-style) items get a highlighted accent bar so they stand out from
        // routine day-to-day entries — matches the same red used across the web app and app icon.
        views.setInt(R.id.itemAccent, "setBackgroundColor", if (item.important) Color.parseColor("#E5A6A7") else Color.TRANSPARENT)
        views.setViewVisibility(R.id.itemImportantMark, if (item.important) android.view.View.VISIBLE else android.view.View.GONE)
        views.setFloat(R.id.itemTitle, "setAlpha", if (item.completed) 0.5f else 1f)

        val openIntent = Intent().apply {
            putExtra(WidgetActionReceiver.EXTRA_WIDGET_ACTION, WidgetActionReceiver.ACTION_OPEN)
        }
        views.setOnClickFillInIntent(R.id.itemTitle, openIntent)
        views.setOnClickFillInIntent(R.id.itemTime, openIntent)

        val deleteIntent = Intent().apply {
            putExtra(WidgetActionReceiver.EXTRA_WIDGET_ACTION, WidgetActionReceiver.ACTION_DELETE)
            putExtra(WidgetActionReceiver.EXTRA_EVENT_ID, item.eventId)
        }
        views.setOnClickFillInIntent(R.id.itemDelete, deleteIntent)

        return views
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.parseColor("#7FBBB3")
    }
}
