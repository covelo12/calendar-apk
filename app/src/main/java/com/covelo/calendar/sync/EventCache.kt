package com.covelo.calendar.sync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One locally cached event — just the fields the alert scheduler needs. Mirrors the server's
 * event shape (server/src/routes/events.ts `rowToEvent`), not the fuller web CalendarEvent type. */
data class CachedEvent(
    val id: String,
    val kind: String, // "event" | "task"
    val title: String,
    val date: String?,
    val startTime: String?,
    val allDay: Boolean,
    val startDate: String?,
    val dueDate: String?,
    val completed: Boolean,
    val alertsRaw: Any?, // JSONObject (task) or JSONArray (event) or null — polymorphic, see handoff §4
    val alertStyle: String?, // "alarm" | "notification" | null
    val color: String, // hex, for widget/UI rendering — not used by alert math
    val category: String?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("kind", kind)
        put("title", title)
        put("date", date ?: JSONObject.NULL)
        put("startTime", startTime ?: JSONObject.NULL)
        put("allDay", allDay)
        put("startDate", startDate ?: JSONObject.NULL)
        put("dueDate", dueDate ?: JSONObject.NULL)
        put("completed", completed)
        put("alerts", alertsRaw ?: JSONObject.NULL)
        put("alertStyle", alertStyle ?: JSONObject.NULL)
        put("color", color)
        put("category", category ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(o: JSONObject): CachedEvent = CachedEvent(
            id = o.getString("id"),
            kind = o.getString("kind"),
            title = o.optString("title", ""),
            date = o.optNullableString("date"),
            startTime = o.optNullableString("startTime"),
            allDay = o.optBoolean("allDay", false),
            startDate = o.optNullableString("startDate"),
            dueDate = o.optNullableString("dueDate"),
            completed = o.optBoolean("completed", false),
            alertsRaw = if (o.isNull("alerts")) null else o.get("alerts"),
            alertStyle = o.optNullableString("alertStyle"),
            color = o.optString("color", "#7FBBB3"),
            category = o.optNullableString("category")
        )

        /** Builds from a raw server event (as returned by GET /events). */
        fun fromServerJson(o: JSONObject): CachedEvent = CachedEvent(
            id = o.getString("id"),
            kind = o.getString("kind"),
            title = o.optString("title", ""),
            date = o.optNullableString("date"),
            startTime = o.optNullableString("startTime"),
            allDay = o.optBoolean("allDay", false),
            startDate = o.optNullableString("startDate"),
            dueDate = o.optNullableString("dueDate"),
            completed = o.optBoolean("completed", false),
            alertsRaw = if (o.isNull("alerts")) null else o.opt("alerts"),
            alertStyle = o.optNullableString("alertStyle"),
            color = o.optString("color", "#7FBBB3"),
            category = o.optNullableString("category")
        )
    }
}

private fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key)

/** Tiny JSON-file cache — the whole point being the dataset is small enough that a real
 * database (Room) would be overkill (handoff doc §2 explicitly calls this out). */
object EventCache {
    private const val FILE_NAME = "event_cache.json"

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun loadAll(context: Context): Map<String, CachedEvent> {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        val root = JSONObject(f.readText())
        val eventsObj = root.optJSONObject("events") ?: return emptyMap()
        val result = mutableMapOf<String, CachedEvent>()
        for (key in eventsObj.keys()) {
            result[key] = CachedEvent.fromJson(eventsObj.getJSONObject(key))
        }
        return result
    }

    @Synchronized
    private fun saveAll(context: Context, events: Map<String, CachedEvent>) {
        val eventsObj = JSONObject()
        for ((id, event) in events) eventsObj.put(id, event.toJson())
        val root = JSONObject().apply { put("events", eventsObj) }
        file(context).writeText(root.toString())
    }

    /**
     * Applies an incremental (or full, on first sync) pull from GET /events: upserts live rows,
     * drops rows the server reports as soft-deleted. Returns the merged cache.
     */
    @Synchronized
    fun applyServerEvents(context: Context, serverEventsJson: JSONArray, isIncremental: Boolean): Map<String, CachedEvent> {
        val current = if (isIncremental) loadAll(context).toMutableMap() else mutableMapOf()
        for (i in 0 until serverEventsJson.length()) {
            val row = serverEventsJson.getJSONObject(i)
            val id = row.getString("id")
            if (row.optBoolean("deleted", false)) {
                current.remove(id)
            } else {
                current[id] = CachedEvent.fromServerJson(row)
            }
        }
        saveAll(context, current)
        return current
    }

    /** Adds or replaces one event in the local cache immediately — used by the widget's quick-add
     * so the new event shows up right away, without waiting for the next periodic sync. */
    @Synchronized
    fun upsertLocal(context: Context, event: CachedEvent) {
        val current = loadAll(context).toMutableMap()
        current[event.id] = event
        saveAll(context, current)
    }

    /** Removes one event from the local cache immediately — used by the widget's delete action. */
    @Synchronized
    fun removeLocal(context: Context, id: String) {
        val current = loadAll(context).toMutableMap()
        current.remove(id)
        saveAll(context, current)
    }
}
