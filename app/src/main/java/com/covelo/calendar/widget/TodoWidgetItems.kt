package com.covelo.calendar.widget

import android.content.Context
import com.covelo.calendar.sync.CachedEvent
import com.covelo.calendar.sync.EventCache
import java.time.LocalDate

data class TodoItem(
    val id: String,
    val title: String,
    val color: String,
    val important: Boolean,
    val completed: Boolean,
    val dueLabel: String? // "Overdue", "Today", or a short date — null if undated
)

/** Builds a flat, checkbox-style task list for the To-Do widget — incomplete tasks first
 * (nearest-due first, undated last), then completed ones at the bottom so ticking one off
 * doesn't yank it out from under your thumb mid-tap. */
object TodoWidgetItems {
    fun all(context: Context): List<TodoItem> {
        val today = LocalDate.now()
        val tasks = EventCache.loadAll(context).values.filter { it.kind == "task" }

        fun earliestDate(t: CachedEvent): LocalDate? =
            listOfNotNull(t.date, t.dueDate, t.startDate)
                .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                .minOrNull()

        fun dueLabel(t: CachedEvent): String? {
            val d = earliestDate(t) ?: return null
            return when {
                d.isBefore(today) -> "Overdue"
                d.isEqual(today) -> "Today"
                else -> d.toString().substring(5) // "MM-dd"
            }
        }

        val (incomplete, completed) = tasks.partition { !it.completed }
        val sortedIncomplete = incomplete.sortedWith(
            compareBy({ earliestDate(it) ?: LocalDate.MAX }, { it.title })
        )
        val sortedCompleted = completed.sortedByDescending { it.title }

        return (sortedIncomplete + sortedCompleted).map { t ->
            TodoItem(
                id = t.id,
                title = t.title,
                color = t.color,
                important = t.alertStyle == "alarm",
                completed = t.completed,
                dueLabel = if (t.completed) null else dueLabel(t)
            )
        }
    }
}
