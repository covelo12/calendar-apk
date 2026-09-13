package com.covelo.calendar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.covelo.calendar.alert.AlarmReceiver
import com.covelo.calendar.auth.ApiClient
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.sync.SyncWorker
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

/** One-time native device enrollment — separate from (and unrelated to) whatever the WebView's
 * own Sync modal enrolls; see handoff §3 ("each device you enroll ... is its own row"). */
class EnrollmentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enroll)

        val apiBaseInput = findViewById<TextInputEditText>(R.id.apiBaseInput)
        val labelInput = findViewById<TextInputEditText>(R.id.labelInput)
        val secretInput = findViewById<TextInputEditText>(R.id.secretInput)
        val enrollButton = findViewById<MaterialButton>(R.id.enrollButton)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val statusText = findViewById<TextView>(R.id.statusText)

        apiBaseInput.setText(Prefs.apiBase(this) ?: BuildConfig.DEFAULT_API_BASE_URL)
        labelInput.setText(android.os.Build.MODEL)

        enrollButton.setOnClickListener {
            val apiBase = apiBaseInput.text?.toString()?.trim()?.trimEnd('/').orEmpty()
            val label = labelInput.text?.toString()?.trim().orEmpty()
            val secret = secretInput.text?.toString().orEmpty()

            if (apiBase.isEmpty() || label.isEmpty() || secret.isEmpty()) {
                statusText.text = "All fields are required."
                return@setOnClickListener
            }

            enrollButton.isEnabled = false
            progress.visibility = View.VISIBLE
            statusText.text = ""

            lifecycleScope.launch {
                try {
                    val deviceId = ApiClient.registerDevice(apiBase, secret, label)
                    Prefs.saveEnrollment(this@EnrollmentActivity, apiBase, deviceId)
                    SyncWorker.enqueuePeriodic(this@EnrollmentActivity)
                    SyncWorker.enqueueOneOff(this@EnrollmentActivity)
                    Toast.makeText(this@EnrollmentActivity, "Device enrolled", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    statusText.text = e.message ?: "Enrollment failed."
                } finally {
                    enrollButton.isEnabled = true
                    progress.visibility = View.GONE
                    secretInput.setText("") // never persist the bootstrap secret past this call
                }
            }
        }

        findViewById<MaterialButton>(R.id.testAlarmButton).setOnClickListener {
            fireTestAlert(alertStyle = "alarm", title = "Test alarm", body = "This is what an important item rings like")
        }
        findViewById<MaterialButton>(R.id.testNotificationButton).setOnClickListener {
            fireTestAlert(alertStyle = "notification", title = "Test notification", body = "This is what a regular reminder looks like")
        }
    }

    /** Fires the exact same code path a real due alert would (AlarmReceiver), immediately,
     * so you can confirm sound/full-screen/permissions actually work on this phone. */
    private fun fireTestAlert(alertStyle: String, title: String, body: String) {
        val key = "test-$alertStyle-${System.currentTimeMillis()}"
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            data = Uri.parse("calendarapp://alert/$key")
            putExtra(AlarmReceiver.EXTRA_KEY, key)
            putExtra(AlarmReceiver.EXTRA_TITLE, title)
            putExtra(AlarmReceiver.EXTRA_BODY, body)
            putExtra(AlarmReceiver.EXTRA_ALERT_STYLE, alertStyle)
        }
        sendBroadcast(intent)
    }
}
