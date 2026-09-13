package com.covelo.calendar.widget

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Minimal recurrence matcher for the home-screen widgets — mirrors the web app's own stepping
 * logic (src/utils/recurrence.ts) closely enough to answer one question: does this event/task's
 * recurrence rule land on `target`, given it anchors on `anchorDate`? The widgets previously did
 * no recurrence expansion at all ("no recurrence expansion" was called out as a deliberate initial
 * scope limit, but it reads to a user as "some events just don't show up on the widget" — a daily
 * "Routine" category, for instance, would only ever appear on its literal creation date).
 */
object RecurrenceUtil {
    private const val MAX_ITERATIONS = 5000

    fun occursOn(recurringRaw: Any?, anchorDate: LocalDate, target: LocalDate): Boolean {
        if (target.isBefore(anchorDate)) return false
        val rule = recurringRaw as? JSONObject ?: return false
        val frequency = rule.optString("frequency", "")
        val until = if (rule.isNull("until") || !rule.has("until")) null else
            runCatching { LocalDate.parse(rule.getString("until")) }.getOrNull()
        if (until != null && target.isAfter(until)) return false

        return when (frequency) {
            "daily" -> true
            "weekly" -> ChronoUnit.DAYS.between(anchorDate, target) % 7 == 0L
            "monthly", "yearly" -> {
                // Variable month/year lengths make simple modulo arithmetic wrong (e.g. a
                // Jan 31 monthly anchor skips February) — step forward exactly like the web
                // app's expandOccurrences does, bounded the same way it is there.
                var cursor = anchorDate
                var iterations = 0
                while (!cursor.isAfter(target) && iterations < MAX_ITERATIONS) {
                    if (cursor.isEqual(target)) return true
                    cursor = if (frequency == "monthly") cursor.plusMonths(1) else cursor.plusYears(1)
                    iterations++
                }
                false
            }
            else -> false
        }
    }
}
