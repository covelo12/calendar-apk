package com.covelo.calendar.alert

import com.covelo.calendar.sync.CachedEvent
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
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
        "at-time" to 0, "5" to 5, "15" to 15, "30" to 30, "60" to 60, "120" to 120, "180" to 180,
        "300" to 300, "360" to 360, "720" to 720, "1440" to 1440, "2880" to 2880, "4320" to 4320,
        "10080" to 10080, "20160" to 20160
    )
    private val OFFSET_LABELS: Map<Int, String> = mapOf(
        0 to "now", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes",
        60 to "1 hour", 120 to "2 hours", 180 to "3 hours", 300 to "5 hours", 360 to "6 hours",
        720 to "12 hours", 1440 to "1 day", 2880 to "2 days", 4320 to "3 days",
        10080 to "1 week", 20160 to "2 weeks"
    )

    /** `at:HH:MM` (on the day itself) or `at:HH:MM:D` (D days earlier). Kept identical to the
     * pattern in src/types/index.ts and server/src/alert-scanner.ts — all three have to agree. */
    private val ABSOLUTE_ALERT = Regex("""^at:([01]\d|2[0-3]):([0-5]\d)(?::(\d{1,2}))?$""")
    private val DEFAULT_OFFSET_MINUTES: Map<String, Int> = mapOf("do" to 60, "due" to 1440)
    const val MAX_OVERDUE_MS: Long = 24L * 60 * 60 * 1000

    private fun parseOffsetKeys(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> (0 until value.length()).mapNotNull { i -> value.opt(i) as? String }.filter { isKnownOffset(it) }
        is String -> if (isKnownOffset(value)) listOf(value) else emptyList()
        else -> emptyList()
    }

    private fun isKnownOffset(key: String): Boolean = key in OFFSET_MINUTES || ABSOLUTE_ALERT.matches(key)

    /**
     * Converts a wall-clock alert into minutes-before *for this particular event*, which is the
     * only way to express it as an offset: "08:00 the day before" lands a different distance
     * ahead depending on what time the event itself is.
     */
    private fun absoluteOffsetMinutes(
        key: String,
        dateStr: String,
        timeStr: String?,
        allDay: Boolean,
        zone: ZoneId
    ): Int? {
        val match = ABSOLUTE_ALERT.matchEntire(key) ?: return null
        val eventMs = runCatching { targetTimeMillis(dateStr, timeStr, allDay, 0, zone) }.getOrNull() ?: return null
        val daysBefore = match.groupValues[3].toIntOrNull() ?: 0
        val alertMs = runCatching {
            LocalDate.parse(dateStr).minusDays(daysBefore.toLong())
                .atTime(match.groupValues[1].toInt(), match.groupValues[2].toInt())
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return ((eventMs - alertMs) / 60_000L).toInt()
    }

    private fun offsetMinutesFor(
        role: String,
        offsetKeys: List<String>,
        dateStr: String,
        timeStr: String?,
        allDay: Boolean,
        zone: ZoneId
    ): List<Int> {
        val explicit = offsetKeys.mapNotNull {
            OFFSET_MINUTES[it] ?: absoluteOffsetMinutes(it, dateStr, timeStr, allDay, zone)
        }.distinct()
        if (explicit.isNotEmpty()) return explicit
        val fallback = DEFAULT_OFFSET_MINUTES[role] ?: return emptyList()
        return listOf(fallback)
    }

    private fun targetTimeMillis(dateStr: String, timeStr: String?, allDay: Boolean, offsetMinutes: Int, zone: ZoneId): Long {
        val time = if (allDay || timeStr.isNullOrBlank()) "09:00" else timeStr
        val dateTime = LocalDateTime.parse("${dateStr}T$time:00", DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return dateTime.atZone(zone).toInstant().toEpochMilli() - offsetMinutes * 60_000L
    }

    /** Any number of minutes, not just the handful that have a fixed label. */
    private fun formatLead(minutes: Int): String {
        OFFSET_LABELS[minutes]?.let { return it }
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        val parts = mutableListOf<String>()
        if (days > 0) parts += "$days day" + if (days == 1) "" else "s"
        if (hours > 0) parts += "$hours hour" + if (hours == 1) "" else "s"
        if (mins > 0 && days == 0) parts += "$mins minute" + if (mins == 1) "" else "s"
        return if (parts.isEmpty()) "now" else parts.joinToString(" ")
    }

    private fun alertBody(kind: String, role: String, minutes: Int): String {
        // A wall-clock alert set after the event's own time resolves to a negative offset; there
        // is nothing sensible to count down to, so treat it the same as "now".
        val lead = formatLead(maxOf(0, minutes))
        return when {
            kind == "event" -> if (minutes <= 0) "Starting now" else "Starts in $lead"
            role == "start" -> "Task starts today"
            role == "due" -> if (minutes <= 0) "Task is due now" else "Task due in $lead"
            else -> if (minutes <= 0) "Time to do this task" else "Time to do this task in $lead"
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
            for (minutes in offsetMinutesFor(role, offsetKeys, dateStr, event.startTime, event.allDay, zone)) {
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
