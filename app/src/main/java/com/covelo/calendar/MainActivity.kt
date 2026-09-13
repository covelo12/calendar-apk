package com.covelo.calendar

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.covelo.calendar.auth.Prefs

/**
 * WebView shell over the deployed PWA — this gets 100% UI parity with the web app for free
 * (handoff §2). All the *new* work (real alarms) lives in the native sync/alert packages,
 * running independently of whatever's happening inside this WebView.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transparent statusBarColor (Theme.Calendar) alone isn't enough — without this the
        // system still reserves/insets the status bar area and paints it black by default
        // rather than letting the WebView's own surface draw underneath it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        if (!Prefs.isEnrolled(this)) {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
        promptExactAlarmPermissionIfNeeded()
        promptBatteryOptimizationExemptionIfNeeded()

        findViewById<ImageButton>(R.id.deviceSetupFab).setOnClickListener {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        webView.loadUrl(BuildConfig.PWA_URL)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun promptExactAlarmPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(AlarmManager::class.java)
            if (alarmManager?.canScheduleExactAlarms() == false) {
                Toast.makeText(this, "Grant \"Alarms & reminders\" so important alerts ring on time", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
    }

    /** OEM battery managers (Samsung's "Put unused apps to sleep" / Freecess in particular) can
     * freeze this app's process in the background hard enough that the periodic sync job and a
     * widget delete's goAsync() coroutine never get to finish their network call — the sync silently
     * goes stale and a delete looks "stuck" (removed from the widget, never actually gone on the
     * server). Being on the OS's battery-optimization allowlist is what stops that freezing. */
    private fun promptBatteryOptimizationExemptionIfNeeded() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(
                this,
                "Allow unrestricted battery use so sync and reminders don't get delayed",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }
}
