package com.covelo.calendar.alert

import com.covelo.calendar.sync.CachedEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One alert instant computed for a specific event/role/offset. `key` is stable and unique
 * per (event, role, offset) so it can be deduped and used as an AlarmManager request identity. */
data class AlertInstant(
    val key: String,
    val eventId: String,
    val role: String,
    val epochMillis: Long,
    val title: String,
    val body: String,
    val alertStyle: String // "alarm" | "notification"
)

/**
 * Reimplementation of server/src/alert-scanner.ts's alert-time math, kept in lockstep with it
 * on purpose (see handoff doc §4-§5) — this is NOT the place to get creative, any divergence
 * means the phone and the web app disagree about when something should fire.
 */
object AlertMath {
    private val OFFSET_MINUTES: Map<String, Int> = mapOf(
        "at-time" to 0, "5" to 5, "15" to 15, "30" to 30, "60" to 60, "300" to 300, "1440" to 1440, "10080" to 10080
    )
    private val OFFSET_LABELS: Map<Int, String> = mapOf(
        0 to "now", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes",
        60 to "1 hour", 300 to "5 hours", 1440 to "1 day", 10080 to "1 week"
    )
    private val DEFAULT_OFFSET_MINUTES: Map<String, Int> = mapOf("do" to 60, "due" to 1440)
    const val MAX_OVERDUE_MS: Long = 24L * 60 * 60 * 1000

    private fun parseOffsetKeys(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { i -> value.opt(i) as? String }.filter { it in OFFSET_MINUTES }
        is String -> if (value in OFFSET_MINUTES) listOf(value) else emptyList()
        else -> emptyList()
    }

    private fun offsetMinutesFor(role: String, offsetKeys: List<String>): List<Int> {
        val explicit = offsetKeys.mapNotNull { OFFSET_MINUTES[it] }.distinct()
        if (explicit.isNotEmpty()) return explicit
        val fallback = DEFAULT_OFFSET_MINUTES[role] ?: return emptyList()
        return listOf(fallback)
    }

    private fun targetTimeMillis(dateStr: String, timeStr: String?, allDay: Boolean, offsetMinutes: Int, zone: ZoneId): Long {
        val time = if (allDay || timeStr.isNullOrBlank()) "09:00" else timeStr
        val dateTime = LocalDateTime.parse("${dateStr}T$time:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return dateTime.atZone(zone).toInstant().toEpochMilli() - offsetMinutes * 60_000L
    }

    private fun alertBody(kind: String, role: String, minutes: Int): String {
        val lead = OFFSET_LABELS[minutes] ?: "$minutes minutes"
        return when {
            kind == "event" -> if (minutes == 0) "Starting now" else "Starts in $lead"
            role == "start" -> "Task starts today"
            role == "due" -> if (minutes == 0) "Task is due now" else "Task due in $lead"
            else -> if (minutes == 0) "Time to do this task" else "Time to do this task in $lead"
        }
    }

    /** All (role, date, offsetKeys) triples relevant to this event's kind, mirroring the
     * server scanner's per-kind role list. Task alerts are polymorphic on kind — see handoff §4. */
    private fun rolesFor(event: CachedEvent): List<Triple<String, String?, List<String>>> {
        return if (event.kind == "task") {
            val alertsObj = event.alertsRaw as? JSONObject
            listOf(
                Triple("start", event.startDate, parseOffsetKeys(alertsObj?.opt("start"))),
                Triple("due", event.dueDate, parseOffsetKeys(alertsObj?.opt("due"))),
                Triple("do", event.date, parseOffsetKeys(alertsObj?.opt("do")))
            )
        } else {
            listOf(Triple("event", event.date, parseOffsetKeys(event.alertsRaw)))
        }
    }

    /** Computes every alert instant this event should ever fire (past or future) — callers
     * decide what to do with stale ones (drop) vs. future ones (schedule). */
    fun computeInstants(event: CachedEvent, zone: ZoneId): List<AlertInstant> {
        if (event.completed) return emptyList()
        val alertStyle = if (event.alertStyle == "alarm") "alarm" else "notification"
        val result = mutableListOf<AlertInstant>()

        for ((role, dateStr, offsetKeys) in rolesFor(event)) {
            if (dateStr.isNullOrBlank()) continue
            for (minutes in offsetMinutesFor(role, offsetKeys)) {
                val target = runCatching {
                    targetTimeMillis(dateStr, event.startTime, event.allDay, minutes, zone)
                }.getOrNull() ?: continue
                result += AlertInstant(
                    key = "${event.id}|$role|$minutes",
                    eventId = event.id,
                    role = role,
                    epochMillis = target,
                    title = event.title,
                    body = alertBody(event.kind, role, minutes),
                    alertStyle = alertStyle
                )
            }
        }
        return result
    }
}
