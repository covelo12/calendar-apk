package com.covelo.calendar.auth

import android.content.Context
import android.content.SharedPreferences

/** Plain (not encrypted) prefs — nothing stored here is secret; the private key stays in the
 * Keystore and never touches this file. Mirrors the shape of the web app's localStorage use. */
object Prefs {
    private const val FILE = "calendar_device_prefs"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_SESSION_TOKEN = "session_token"
    private const val KEY_SESSION_EXPIRES = "session_expires"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val KEY_PENDING_DELETES = "pending_deletes"
    private const val KEY_PENDING_UPDATES = "pending_updates"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apiBase(context: Context): String? = prefs(context).getString(KEY_API_BASE, null)
    fun deviceId(context: Context): String? = prefs(context).getString(KEY_DEVICE_ID, null)

    fun isEnrolled(context: Context): Boolean =
        apiBase(context) != null && deviceId(context) != null && KeystoreSigner.hasKey()

    fun saveEnrollment(context: Context, apiBase: String, deviceId: String) {
        prefs(context).edit()
            .putString(KEY_API_BASE, apiBase.trimEnd('/'))
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    fun clearEnrollment(context: Context) {
        prefs(context).edit()
            .remove(KEY_API_BASE)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_SESSION_TOKEN)
            .remove(KEY_SESSION_EXPIRES)
            .remove(KEY_LAST_SYNC)
            .apply()
    }

    fun sessionToken(context: Context): String? = prefs(context).getString(KEY_SESSION_TOKEN, null)
    fun sessionExpiresAt(context: Context): Long = prefs(context).getLong(KEY_SESSION_EXPIRES, 0L)

    fun saveSession(context: Context, token: String, expiresAtMillis: Long) {
        prefs(context).edit()
            .putString(KEY_SESSION_TOKEN, token)
            .putLong(KEY_SESSION_EXPIRES, expiresAtMillis)
            .apply()
    }

    fun lastSync(context: Context): String? = prefs(context).getString(KEY_LAST_SYNC, null)

    fun saveLastSync(context: Context, isoTimestamp: String) {
        prefs(context).edit().putString(KEY_LAST_SYNC, isoTimestamp).apply()
    }

    /** Event ids removed from the local cache whose server-side DELETE hasn't been confirmed yet.
     * A full or incremental sync must not let one of these ids back into the cache — otherwise a
     * delete that failed to reach the server (no network, a dropped request) silently "undoes"
     * itself the next time the server's copy comes back down. SyncWorker retries these each tick
     * and only clears an id once the DELETE actually succeeds (or the server 404s, meaning it's
     * already gone). */
    fun pendingDeletes(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PENDING_DELETES, emptySet()).orEmpty().toSet()

    fun addPendingDelete(context: Context, eventId: String) {
        val current = pendingDeletes(context).toMutableSet()
        current.add(eventId)
        prefs(context).edit().putStringSet(KEY_PENDING_DELETES, current).apply()
    }

    fun removePendingDelete(context: Context, eventId: String) {
        val current = pendingDeletes(context).toMutableSet()
        if (current.remove(eventId)) {
            prefs(context).edit().putStringSet(KEY_PENDING_DELETES, current).apply()
        }
    }

    /** Same idea as [pendingDeletes], for a local edit (the widget's toggle-complete) whose PUT
     * to the server hasn't been confirmed yet. The local cache already holds the edited row, so
     * a sync arriving in the meantime must not let the server's still-stale copy overwrite it —
     * it would otherwise flip a just-completed task back to incomplete for one tick. */
    fun pendingUpdates(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PENDING_UPDATES, emptySet()).orEmpty().toSet()

    fun addPendingUpdate(context: Context, eventId: String) {
        val current = pendingUpdates(context).toMutableSet()
        current.add(eventId)
        prefs(context).edit().putStringSet(KEY_PENDING_UPDATES, current).apply()
    }

    fun removePendingUpdate(context: Context, eventId: String) {
        val current = pendingUpdates(context).toMutableSet()
        if (current.remove(eventId)) {
            prefs(context).edit().putStringSet(KEY_PENDING_UPDATES, current).apply()
        }
    }
}
