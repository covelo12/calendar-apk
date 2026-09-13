package com.covelo.calendar

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.covelo.calendar.auth.Prefs
import com.google.android.material.floatingactionbutton.FloatingActionButton

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
        setContentView(R.layout.activity_main)

        if (!Prefs.isEnrolled(this)) {
            startActivity(Intent(this, EnrollmentActivity::class.java))
        }

        requestNotificationPermissionIfNeeded()
        promptExactAlarmPermissionIfNeeded()

        findViewById<FloatingActionButton>(R.id.deviceSetupFab).setOnClickListener {
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
}
