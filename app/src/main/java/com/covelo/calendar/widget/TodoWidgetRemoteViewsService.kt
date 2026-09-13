package com.covelo.calendar.widget

import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.covelo.calendar.R

class TodoWidgetRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = TodoWidgetFactory(applicationContext)
}

private class TodoWidgetFactory(private val context: android.content.Context) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<TodoItem> = emptyList()

    override fun onCreate() {}
    override fun onDataSetChanged() {
        items = try {
            TodoWidgetItems.all(context)
        } catch (e: Exception) {
            android.util.Log.e("TodoWidget", "Failed to load tasks", e)
            emptyList()
        }
    }
    override fun onDestroy() {}

    override fun getCount(): Int = items.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews = try {
        buildView(items[position])
    } catch (e: Exception) {
        android.util.Log.e("TodoWidget", "Failed to build row $position", e)
        RemoteViews(context.packageName, R.layout.widget_todo_item)
    }

    private fun buildView(item: TodoItem): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_todo_item)

        views.setTextViewText(R.id.todoTitle, item.title)
        views.setFloat(R.id.todoTitle, "setAlpha", if (item.completed) 0.6f else 1f)

        // The checkbox ring/fill is tinted to the task's own category color instead of a
        // flat neutral grey — one shape now carries both "is this done" and "what category
        // is this", matching the web app's TaskRow. The check glyph is a separate, fixed-white
        // overlay so it stays legible against whatever color fills the circle underneath.
        val categoryColor = parseColor(item.color)
        views.setImageViewResource(
            R.id.todoCheckboxCircle,
            if (item.completed) R.drawable.widget_checkbox_fill else R.drawable.widget_checkbox_ring
        )
        views.setInt(R.id.todoCheckboxCircle, "setColorFilter", categoryColor)
        views.setViewVisibility(R.id.todoCheckMark, if (item.completed) android.view.View.VISIBLE else android.view.View.GONE)

        val dueLabel = item.dueLabel
        views.setViewVisibility(R.id.todoDueLabel, if (dueLabel != null) android.view.View.VISIBLE else android.view.View.GONE)
        if (dueLabel != null) {
            views.setTextViewText(R.id.todoDueLabel, dueLabel)
            views.setTextColor(R.id.todoDueLabel, if (dueLabel == "Overdue") Color.parseColor("#E5A6A7") else Color.parseColor("#9BA6A0"))
        }
        if (item.important && !item.completed) {
            views.setTextColor(R.id.todoTitle, Color.parseColor("#E5A6A7"))
        } else {
            views.setTextColor(R.id.todoTitle, Color.parseColor("#E9E5D3"))
        }

        val toggleIntent = Intent().apply {
            putExtra(TodoActionReceiver.EXTRA_TODO_ACTION, TodoActionReceiver.ACTION_TOGGLE)
            putExtra(TodoActionReceiver.EXTRA_TASK_ID, item.id)
        }
        views.setOnClickFillInIntent(R.id.todoCheckbox, toggleIntent)
        views.setOnClickFillInIntent(R.id.todoTitle, toggleIntent)

        val deleteIntent = Intent().apply {
            putExtra(TodoActionReceiver.EXTRA_TODO_ACTION, TodoActionReceiver.ACTION_DELETE)
            putExtra(TodoActionReceiver.EXTRA_TASK_ID, item.id)
        }
        views.setOnClickFillInIntent(R.id.todoDelete, deleteIntent)

        return views
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.parseColor("#7FBBB3")
    }
}
