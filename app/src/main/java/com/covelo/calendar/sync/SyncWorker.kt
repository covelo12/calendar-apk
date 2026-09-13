package com.covelo.calendar.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.ApiClient
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.widget.DayWidgetProvider
import com.covelo.calendar.widget.ProgressWidgetProvider
import com.covelo.calendar.widget.TodoWidgetProvider
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Periodic background pull of GET /events?since=... into the local cache, followed by
 * recomputing/rescheduling alerts. 15 minutes is WorkManager's floor for periodic work
 * (handoff §2) — real-time delivery still comes from AlarmManager, not from this tick. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Prefs.isEnrolled(applicationContext)) return Result.success()

        return try {
            retryPendingDeletes()
            retryPendingUpdates()

            val since = Prefs.lastSync(applicationContext)
            val path = if (since != null) "/events?since=${java.net.URLEncoder.encode(since, "UTF-8")}" else "/events"
            val result = ApiClient.authedRequest(applicationContext, path)
            if (result.statusCode != 200) {
                android.util.Log.e(TAG, "Sync failed: HTTP ${result.statusCode} ${result.body.take(200)}")
                return Result.retry()
            }

            val payload = JSONObject(result.body)
            val events = payload.getJSONArray("events")
            EventCache.applyServerEvents(applicationContext, events, isIncremental = since != null)
            // Prefer the cursor the server hands back: it's in the server's own clock and in the
            // exact format the updated_at values it gets compared against are written in. A cursor
            // taken from this device's clock instead can silently narrow the next sync to nothing
            // if the two disagree on either — which is precisely how this sync broke before.
            val cursor = payload.optString("syncedAt").ifBlank { Instant.now().toString() }
            Prefs.saveLastSync(applicationContext, cursor)

            AlertScheduler.rescheduleAll(applicationContext)
            DayWidgetProvider.updateAll(applicationContext)
            TodoWidgetProvider.updateAll(applicationContext)
            ProgressWidgetProvider.updateAll(applicationContext)
            android.util.Log.i(TAG, "Sync ok: ${events.length()} rows, incremental=${since != null}, cursor=$cursor")
            // Only worth waking the UI when something actually came back — otherwise every idle
            // 15-minute tick would make the WebView re-fetch for nothing.
            if (events.length() > 0) DataChangeNotifier.broadcast(applicationContext)
            Result.success()
        } catch (e: Exception) {
            // Silence here is what let a completely dead sync look healthy for so long.
            android.util.Log.e(TAG, "Sync threw", e)
            Result.retry()
        }
    }

    /** A widget delete whose DELETE call failed earlier (offline, dropped request) left its id in
     * Prefs.pendingDeletes so the fetch below can't let the still-live server row back into the
     * cache. Actually retry it here — without this, "will retry next sync" was a promise the app
     * never kept, and the event just stayed a phantom until it happened to get deleted again. */
    private suspend fun retryPendingDeletes() {
        for (id in Prefs.pendingDeletes(applicationContext)) {
            try {
                val res = ApiClient.authedRequest(applicationContext, "/events/$id", "DELETE")
                if (res.statusCode in 200..299 || res.statusCode == 404) {
                    Prefs.removePendingDelete(applicationContext, id)
                    android.util.Log.i(TAG, "Pending delete confirmed: $id")
                } else {
                    android.util.Log.e(TAG, "Pending delete $id failed: HTTP ${res.statusCode}")
                }
            } catch (e: Exception) {
                // Leave it pending; the next tick tries again.
                android.util.Log.e(TAG, "Pending delete $id threw", e)
            }
        }
    }

    /** A widget toggle-complete whose PUT hasn't been confirmed yet — same shape as
     * retryPendingDeletes, resending the locally cached row (which already holds the edited
     * value) rather than reconstructing anything. */
    private suspend fun retryPendingUpdates() {
        for (id in Prefs.pendingUpdates(applicationContext)) {
            try {
                val cached = EventCache.loadAll(applicationContext)[id]
                if (cached == null) {
                    // Nothing local to resend (e.g. it was since deleted) — nothing to retry.
                    Prefs.removePendingUpdate(applicationContext, id)
                    continue
                }
                val body = JSONObject().apply { put("events", org.json.JSONArray().put(cached.toServerJson())) }
                val res = ApiClient.authedRequest(applicationContext, "/events", "PUT", body.toString())
                if (res.statusCode in 200..299) {
                    Prefs.removePendingUpdate(applicationContext, id)
                    android.util.Log.i(TAG, "Pending update confirmed: $id")
                } else {
                    android.util.Log.e(TAG, "Pending update $id failed: HTTP ${res.statusCode}")
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Pending update $id threw", e)
            }
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val PERIODIC_WORK_NAME = "calendar_sync_periodic"
        private const val ONE_OFF_WORK_NAME = "calendar_sync_one_off"

        /** Without this a sync attempted with no connection just fails and burns a backoff step,
         * so the next real chance to sync is pushed further away the worse the network is.
         * Waiting for a network instead means the queued work runs the moment one exists. */
        private val requiresNetwork = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(requiresNetwork)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun enqueueOneOff(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(requiresNetwork)
                .build()
            // Appended rather than REPLACE: replacing cancels the sync already in flight, and
            // now that every widget action and every write in the page asks for one, two edits
            // in quick succession had the second killing the first mid-request — observed as
            // "Sync threw: JobCancellationException" with the round trip wasted. Each run reads
            // the current queues and cursor when it starts, so letting the in-flight one finish
            // and following it with another loses nothing.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_OFF_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
