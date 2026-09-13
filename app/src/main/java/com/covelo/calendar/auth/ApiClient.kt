package com.covelo.calendar.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class ApiException(val statusCode: Int, message: String) : IOException(message)

data class HttpResult(val statusCode: Int, val body: String)

/** Thin REST client mirroring src/lib/deviceAuth.ts + src/lib/syncApi.ts on the web side —
 * same enrollment, challenge/response auth, and event endpoints, against the same server. */
object ApiClient {

    private suspend fun rawRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): HttpResult = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            HttpResult(status, text)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun registerDevice(apiBase: String, bootstrapToken: String, label: String): String {
        val jwk = KeystoreSigner.ensureKeyPair()
        val payload = JSONObject().apply {
            put("label", label)
            put("publicKeyJwk", jwk)
        }
        val result = rawRequest(
            "$apiBase/devices/register",
            "POST",
            mapOf("X-Bootstrap-Token" to bootstrapToken),
            payload.toString()
        )
        if (result.statusCode != 201) {
            val error = runCatching { JSONObject(result.body).optString("error") }.getOrNull()
            throw ApiException(result.statusCode, error ?: "Registration failed (${result.statusCode})")
        }
        return JSONObject(result.body).getString("deviceId")
    }

    private suspend fun fetchSessionToken(context: Context, forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            val cached = Prefs.sessionToken(context)
            if (cached != null && System.currentTimeMillis() < Prefs.sessionExpiresAt(context)) return cached
        }

        val apiBase = Prefs.apiBase(context) ?: throw IOException("Device not enrolled.")
        val deviceId = Prefs.deviceId(context) ?: throw IOException("Device not enrolled.")

        val challengeResult = rawRequest("$apiBase/auth/challenge?deviceId=$deviceId", "GET")
        if (challengeResult.statusCode != 200) throw ApiException(challengeResult.statusCode, "Could not reach the server for an auth challenge.")
        val nonce = JSONObject(challengeResult.body).getString("nonce")

        val signature = KeystoreSigner.signToBase64Url(nonce.toByteArray(StandardCharsets.UTF_8))

        val verifyBody = JSONObject().apply {
            put("deviceId", deviceId)
            put("nonce", nonce)
            put("signature", signature)
        }
        val verifyResult = rawRequest("$apiBase/auth/verify", "POST", body = verifyBody.toString())
        if (verifyResult.statusCode != 200) throw ApiException(verifyResult.statusCode, "Auth challenge verification failed.")

        val verifyJson = JSONObject(verifyResult.body)
        val token = verifyJson.getString("token")
        val expiresIn = verifyJson.getLong("expiresIn")
        // Refresh a little early so a near-expiry token is never used for a real request.
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000 - 30_000
        Prefs.saveSession(context, token, expiresAt)
        return token
    }

    /** Authenticated request against `path` (e.g. "/events?since=..."). Re-authenticates once
     * and retries on a 401 — the server's session store is in-memory and forgets everyone on
     * every restart/redeploy, so a stale-looking 401 is normal, not an error state. */
    suspend fun authedRequest(context: Context, path: String, method: String = "GET", body: String? = null): HttpResult {
        val apiBase = Prefs.apiBase(context) ?: throw IOException("Device not enrolled.")

        suspend fun attempt(forceRefresh: Boolean): HttpResult {
            val token = fetchSessionToken(context, forceRefresh)
            return rawRequest("$apiBase$path", method, mapOf("Authorization" to "Bearer $token"), body)
        }

        val first = attempt(false)
        if (first.statusCode == 401) return attempt(true)
        return first
    }
}
