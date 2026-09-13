package com.covelo.calendar.widget

import android.content.Context
import com.covelo.calendar.BuildConfig
import com.covelo.calendar.sync.CachedEvent
import com.covelo.calendar.sync.EventCache
import java.time.LocalDate
import java.time.ZoneId

data class WidgetItem(
    val id: String,
    val sortKey: String, // "00:00" style, or "0" for all-day/undated so it sorts first
    val timeLabel: String,
    val title: String,
    val color: String,
    val important: Boolean,
    val completed: Boolean
)

/** Builds "what's on today" for the home screen widget from the same local cache the alert
 * scheduler reads — a day-view-style list, not a full calendar render (handoff's WebView owns
 * that). Kept deliberately simple: one row per event/task role due today, no recurrence
 * expansion (matches the alert engine's own scope, see handoff §5). */
object WidgetItems {
    fun today(context: Context): List<WidgetItem> {
        val zone = ZoneId.of(BuildConfig.SERVER_TIME_ZONE)
        val today = LocalDate.now(zone).toString()
        val events = EventCache.loadAll(context).values

        val items = mutableListOf<WidgetItem>()
        for (event in events) {
            val important = event.alertStyle == "alarm"
            if (event.kind == "task") {
                val role = when (today) {
                    event.date -> "Do"
                    event.dueDate -> "Due"
                    event.startDate -> "Start"
                    else -> null
                } ?: continue
                items += WidgetItem(
                    id = "${event.id}-$role",
                    sortKey = "0",
                    timeLabel = role,
                    title = event.title,
                    color = event.color,
                    important = important,
                    completed = event.completed
                )
            } else {
                if (event.date != today) continue
                val timeLabel = if (event.allDay || event.startTime.isNullOrBlank()) "All day" else event.startTime
                items += WidgetItem(
                    id = event.id,
                    sortKey = if (event.allDay || event.startTime.isNullOrBlank()) "0" else event.startTime,
                    timeLabel = timeLabel,
                    title = event.title,
                    color = event.color,
                    important = important,
                    completed = false
                )
            }
        }
        return items.sortedWith(compareBy({ it.completed }, { it.sortKey }))
    }
}
