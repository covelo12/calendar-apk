package com.covelo.calendar.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.ApiClient
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.widget.DayWidgetProvider
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
            val since = Prefs.lastSync(applicationContext)
            val path = if (since != null) "/events?since=${java.net.URLEncoder.encode(since, "UTF-8")}" else "/events"
            val result = ApiClient.authedRequest(applicationContext, path)
            if (result.statusCode != 200) return Result.retry()

            val events = JSONObject(result.body).getJSONArray("events")
            EventCache.applyServerEvents(applicationContext, events, isIncremental = since != null)
            Prefs.saveLastSync(applicationContext, Instant.now().toString())

            AlertScheduler.rescheduleAll(applicationContext)
            DayWidgetProvider.updateAll(applicationContext)
            TodoWidgetProvider.updateAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "calendar_sync_periodic"
        private const val ONE_OFF_WORK_NAME = "calendar_sync_one_off"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun enqueueOneOff(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_OFF_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
