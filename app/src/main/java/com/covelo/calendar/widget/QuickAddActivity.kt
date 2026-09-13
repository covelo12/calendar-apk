package com.covelo.calendar.widget

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
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
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Minimal quick-add dialog launched from a widget's + button — the widget equivalent of
 * Google Calendar's quick-add. Always dated today; anything more specific (time, category,
 * recurrence) is a full app edit away. Shared by both widgets via EXTRA_KIND. */
class QuickAddActivity : AppCompatActivity() {
    private var kind = "event"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched via FLAG_ACTIVITY_NEW_TASK from a widget PendingIntent (no existing task to
        // attach to) — on modern Android/OEM launchers that triggers a full "opening app" task
        // transition (app icon + label card) sized for a normal fullscreen activity. A floating/
        // translucent dialog theme (Theme.Calendar.Dialog) never fully covers that card, leaving
        // it visible behind the small dialog — confirmed on Android 16; neither
        // windowDisablePreview nor overridePendingTransition(0, 0) fixes it (both are window-
        // transition mechanisms, and the transition here is the launcher's, not ours).
        // Theme.Calendar.QuickAdd instead is a normal opaque fullscreen activity, so it gets the
        // launcher's regular full-activity transition — the dialog look is faked entirely in the
        // layout (backdrop + centered card), same as the web app's EventModal.
        setTheme(R.style.Theme_Calendar_QuickAdd)
        setContentView(R.layout.activity_quick_add)

        kind = intent.getStringExtra(EXTRA_KIND) ?: "event"

        findViewById<TextView>(R.id.quickAddHeading).text =
            if (kind == "task") "New task" else "New event today"
        findViewById<TextInputLayout>(R.id.quickAddTitleLayout).hint =
            if (kind == "task") "What do you need to do?" else "Title"

        val titleInput = findViewById<TextInputEditText>(R.id.quickAddTitle)
        titleInput.requestFocus()

        // Replicates a dialog's tap-outside-to-dismiss now that the window itself is fullscreen.
        findViewById<FrameLayout>(R.id.quickAddRoot).setOnClickListener { finish() }

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
            kind = kind,
            title = title,
            date = today,
            startTime = null,
            endTime = null,
            allDay = true,
            startDate = null,
            dueDate = null,
            completed = false,
            alertsRaw = null,
            alertStyle = null,
            color = "#7FBBB3",
            category = "Personal",
            description = null,
            recurringRaw = null
        )
        // Show it immediately — don't make the widget wait on the network round trip.
        EventCache.upsertLocal(this, cached)
        AlertScheduler.rescheduleAll(this)
        DayWidgetProvider.updateAll(this)
        TodoWidgetProvider.updateAll(this)
        Toast.makeText(this, "Added", Toast.LENGTH_SHORT).show()
        finish()

        val body = JSONObject().apply {
            put("events", org.json.JSONArray().put(cached.toServerJson()))
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

    companion object {
        const val EXTRA_KIND = "kind"
    }
}
