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
}
