package com.covelo.calendar.widget

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.covelo.calendar.BuildConfig
import com.covelo.calendar.R
import com.covelo.calendar.alert.AlertScheduler
import com.covelo.calendar.auth.ApiClient
import com.covelo.calendar.sync.CachedEvent
import com.covelo.calendar.sync.EventCache
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Minimal "add an event for today" dialog, launched from the widget's + button — the widget
 * equivalent of Google Calendar's quick-add. Always a same-day, all-day event; anything more
 * specific (time, category, recurrence) is a full app edit away. */
class QuickAddActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_Calendar_Dialog)
        setContentView(R.layout.activity_quick_add)

        val titleInput = findViewById<TextInputEditText>(R.id.quickAddTitle)
        titleInput.requestFocus()

        findViewById<MaterialButton>(R.id.quickAddCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.quickAddSave).setOnClickListener {
            val title = titleInput.text?.toString()?.trim().orEmpty()
            if (title.isEmpty()) {
                finish()
                return@setOnClickListener
            }
            save(title)
        }
    }

    private fun save(title: String) {
        val zone = ZoneId.of(BuildConfig.SERVER_TIME_ZONE)
        val today = LocalDate.now(zone).toString()
        val id = UUID.randomUUID().toString()

        val cached = CachedEvent(
            id = id,
            kind = "event",
            title = title,
            date = today,
            startTime = null,
            allDay = true,
            startDate = null,
            dueDate = null,
            completed = false,
            alertsRaw = null,
            alertStyle = null,
            color = "#7FBBB3",
            category = "Personal"
        )
        // Show it immediately — don't make the widget wait on the network round trip.
        EventCache.upsertLocal(this, cached)
        AlertScheduler.rescheduleAll(this)
        DayWidgetProvider.updateAll(this)
        Toast.makeText(this, "Added", Toast.LENGTH_SHORT).show()
        finish()

        val body = JSONObject().apply {
            put("events", org.json.JSONArray().put(JSONObject().apply {
                put("id", id)
                put("kind", "event")
                put("title", title)
                put("date", today)
                put("allDay", true)
                put("color", "#7FBBB3")
                put("category", "Personal")
            }))
        }
        lifecycleScope.launch {
            try {
                ApiClient.authedRequest(this@QuickAddActivity, "/events", "PUT", body.toString())
            } catch (e: Exception) {
                // Already visible locally; if the network call failed it'll look "added" here
                // but missing on other devices until you retry (no offline write-queue yet).
            }
        }
    }
}
