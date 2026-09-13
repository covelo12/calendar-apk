package com.covelo.calendar

import android.app.Application
import com.covelo.calendar.alert.NotificationChannels
import com.covelo.calendar.auth.Prefs
import com.covelo.calendar.sync.SyncWorker

class CalendarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        if (Prefs.isEnrolled(this)) {
            SyncWorker.enqueuePeriodic(this)
        }
    }
}
