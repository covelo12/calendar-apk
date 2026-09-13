package com.covelo.calendar

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.sync.DataChangeNotifier
import com.covelo.calendar.sync.SyncWorker

/**
 * WebView shell over the deployed PWA — this gets 100% UI parity with the web app for free
 * (handoff §2). All the *new* work (real alarms) lives in the native sync/alert packages,
 * running independently of whatever's happening inside this WebView.
 */
class MainActivity : AppCompatActivity() {
    private companion object {
        const val TAG = "CalendarShell"
    }

    private lateinit var webView: WebView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Fires when a native sync brought new data down, so the page can show it right away
     * instead of waiting out its own 60-second poll. */
    private val dataChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // The web app already re-pulls whenever its window regains focus, so reuse that
            // rather than making the page expose a bespoke hook — this then also works against
            // whatever build of the PWA happens to be deployed right now.
            webView.evaluateJavascript(
                "window.dispatchEvent(new Event('focus')); typeof window.AndroidSync?.requestSync"
            ) { result -> android.util.Log.i(TAG, "Nudged WebView to re-pull (bridge=$result)") }
        }
    }

    /** Lets the page ask for an immediate sync after it writes something, so the widgets stop
     * waiting up to 15 minutes for the next periodic tick to notice. Deliberately takes no
     * arguments and returns nothing: the whole surface is "please sync my own data, now". */
    private inner class SyncBridge {
        @JavascriptInterface
        fun requestSync() {
            runOnUiThread {
                // A WebView follows any link it is given, so confirm we are still on our own
                // page before honouring this — a JavascriptInterface is reachable by whatever
                // happens to be loaded.
                val url = webView.url ?: return@runOnUiThread
                if (!url.startsWith(BuildConfig.PWA_URL)) return@runOnUiThread
                android.util.Log.i(TAG, "Page asked for an immediate sync")
                SyncWorker.enqueueOneOff(this@MainActivity)
            }
        }
    }

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
        webView.addJavascriptInterface(SyncBridge(), "AndroidSync")
        webView.loadUrl(BuildConfig.PWA_URL)
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            dataChangeReceiver,
            IntentFilter(DataChangeNotifier.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataChangeReceiver)
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
