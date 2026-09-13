package com.covelo.calendar.widget

import android.content.Context
import com.covelo.calendar.BuildConfig
import com.covelo.calendar.sync.CachedEvent
import com.covelo.calendar.sync.EventCache
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class WidgetItem(
    val id: String,
    val eventId: String, // the real server/cache id — always the deletable target, unlike `id`
    val sortKey: String, // "00:00" style, or "0" for all-day/undated so it sorts first
    val timeLabel: String,
    val title: String,
    val color: String,
    val important: Boolean,
    val completed: Boolean,
    /** A plain section-label row ("Tomorrow, Sep 14") rather than an actual event/task — lets
     * the widget read as a short two-day agenda instead of "just today", without needing a
     * second RemoteViewsFactory/provider. */
    val isHeader: Boolean = false
)

/** Builds a short agenda for the home screen widget from the same local cache the alert
 * scheduler reads — day-view-style rows, not a full calendar render (handoff's WebView owns
 * that). One row per event/task role due that day, including recurring events/tasks via
 * RecurrenceUtil (previously only the literal anchor date matched, so a daily/weekly item only
 * ever showed up on the day it was created). */
object WidgetItems {
    private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")

    /** Today's items, followed by a "Tomorrow" header + tomorrow's items when there are any —
     * this is the "more like a calendar, see the next day" agenda view. */
    fun agenda(context: Context): List<WidgetItem> {
        val zone = ZoneId.of(BuildConfig.SERVER_TIME_ZONE)
        val todayDate = LocalDate.now(zone)
        val tomorrowDate = todayDate.plusDays(1)
        val events = EventCache.loadAll(context).values

        val todayItems = forDate(events, todayDate)
        val tomorrowItems = forDate(events, tomorrowDate)
        if (tomorrowItems.isEmpty()) return todayItems

        val dayName = tomorrowDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        val header = WidgetItem(
            id = "header-tomorrow",
            eventId = "",
            sortKey = "",
            timeLabel = "",
            title = "$dayName, ${tomorrowDate.format(MONTH_DAY)}",
            color = "#7A8478",
            important = false,
            completed = false,
            isHeader = true
        )
        return todayItems + header + tomorrowItems
    }

    private fun forDate(events: Collection<CachedEvent>, date: LocalDate): List<WidgetItem> {
        val dateStr = date.toString()
        val items = mutableListOf<WidgetItem>()

        for (event in events) {
            val important = event.alertStyle == "alarm"
            if (event.kind == "task") {
                // A recurring task's "do" date is the one role that repeats — start/due stay
                // fixed, single points regardless of recurrence (matches the web app's own
                // expandTaskOccurrences split between recurring "do" and fixed markers).
                val doesRecur = event.date?.let { anchor ->
                    runCatching { RecurrenceUtil.occursOn(event.recurringRaw, LocalDate.parse(anchor), date) }.getOrDefault(false)
                } ?: false
                val role = when {
                    dateStr == event.date -> "Do"
                    doesRecur -> "Do"
                    dateStr == event.dueDate -> "Due"
                    dateStr == event.startDate -> "Start"
                    else -> null
                } ?: continue
                items += WidgetItem(
                    id = "${event.id}-$role-$dateStr",
                    eventId = event.id,
                    sortKey = "0",
                    timeLabel = role,
                    title = event.title,
                    color = event.color,
                    important = important,
                    completed = event.completed
                )
            } else {
                val occurs = dateStr == event.date || event.date?.let { anchor ->
                    runCatching { RecurrenceUtil.occursOn(event.recurringRaw, LocalDate.parse(anchor), date) }.getOrDefault(false)
                } == true
                if (!occurs) continue
                val timeLabel = if (event.allDay || event.startTime.isNullOrBlank()) "All day" else event.startTime
                items += WidgetItem(
                    id = "${event.id}-$dateStr",
                    eventId = event.id,
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
