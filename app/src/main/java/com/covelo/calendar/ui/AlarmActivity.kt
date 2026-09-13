package com.covelo.calendar.ui

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.covelo.calendar.alert.AlarmReceiver
import com.google.android.material.button.MaterialButton
import android.media.MediaPlayer

/** The stock-Clock-app-style full-screen ring. Launched by AlarmReceiver's fullScreenIntent
 * for anything with alertStyle == "alarm" (handoff §6) — loops a ringtone + vibration until
 * the user dismisses or snoozes, and shows over the lock screen. */
class AlarmActivity : AppCompatActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var notificationId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)

        setContentView(com.covelo.calendar.R.layout.activity_alarm)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        findViewById<TextView>(com.covelo.calendar.R.id.alarmTitle).text = title
        findViewById<TextView>(com.covelo.calendar.R.id.alarmBody).text = body

        findViewById<MaterialButton>(com.covelo.calendar.R.id.dismissButton).setOnClickListener {
            stopRinging()
            finish()
        }
        findViewById<MaterialButton>(com.covelo.calendar.R.id.snoozeButton).setOnClickListener {
            stopRinging()
            snooze()
            finish()
        }

        startRinging()
        NotificationManagerCompat.from(this).cancel(notificationId)
    }

    private fun startRinging() {
        try {
            val alarmSoundUri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@AlarmActivity, alarmSoundUri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // No default alarm sound available on this device — vibration alone still wakes the user.
        }

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val pattern = longArrayOf(0, 800, 800)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun stopRinging() {
        mediaPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mediaPlayer = null
        vibrator?.cancel()
    }

    private fun snooze() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        val body = intent.getStringExtra(EXTRA_BODY) ?: ""
        val snoozeKey = "snooze-$notificationId-${System.currentTimeMillis()}"

        val snoozeIntent = Intent(this, AlarmReceiver::class.java).apply {
            data = android.net.Uri.parse("calendarapp://alert/$snoozeKey")
            putExtra(AlarmReceiver.EXTRA_KEY, snoozeKey)
            putExtra(AlarmReceiver.EXTRA_TITLE, title)
            putExtra(AlarmReceiver.EXTRA_BODY, body)
            putExtra(AlarmReceiver.EXTRA_ALERT_STYLE, "alarm")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, snoozeKey.hashCode(), snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = System.currentTimeMillis() + 5 * 60 * 1000
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, pendingIntent), pendingIntent)
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_BODY = "body"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
