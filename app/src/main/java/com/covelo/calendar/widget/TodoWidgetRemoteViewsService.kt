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
        views.setFloat(R.id.todoTitle, "setAlpha", if (item.completed) 0.5f else 1f)
        views.setInt(
            R.id.todoCheckbox, "setBackgroundResource",
            if (item.completed) R.drawable.widget_checkbox_on else R.drawable.widget_checkbox_off
        )
        views.setTextViewText(R.id.todoCheckbox, if (item.completed) "✓" else "")

        val dueLabel = item.dueLabel
        views.setViewVisibility(R.id.todoDueLabel, if (dueLabel != null) android.view.View.VISIBLE else android.view.View.GONE)
        if (dueLabel != null) {
            views.setTextViewText(R.id.todoDueLabel, dueLabel)
            views.setTextColor(R.id.todoDueLabel, if (dueLabel == "Overdue") Color.parseColor("#E67E80") else Color.parseColor("#859289"))
        }
        if (item.important && !item.completed) {
            views.setTextColor(R.id.todoTitle, Color.parseColor("#E67E80"))
        } else {
            views.setTextColor(R.id.todoTitle, Color.parseColor("#D3C6AA"))
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
}
