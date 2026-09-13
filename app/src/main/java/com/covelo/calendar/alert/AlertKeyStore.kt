package com.covelo.calendar.alert

import android.content.Context

/** Tracks which alert keys have already fired (never refire, forever — mirrors the server's
 * `alerted_offsets_json` dedupe, see handoff §4) and which are currently scheduled with
 * AlarmManager (so a stale one can be cancelled when its event changes or disappears). */
object AlertKeyStore {
    private const val FILE = "calendar_alert_state"
    private const val KEY_FIRED = "fired_keys"
    private const val KEY_SCHEDULED = "scheduled_keys"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun firedKeys(context: Context): MutableSet<String> =
        HashSet(prefs(context).getStringSet(KEY_FIRED, emptySet()) ?: emptySet())

    fun saveFiredKeys(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(KEY_FIRED, keys).apply()
    }

    fun scheduledKeys(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SCHEDULED, emptySet()) ?: emptySet()

    fun saveScheduledKeys(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SCHEDULED, keys).apply()
    }

    fun addFired(context: Context, key: String) {
        val fired = firedKeys(context)
        fired.add(key)
        saveFiredKeys(context, fired)
    }
}
