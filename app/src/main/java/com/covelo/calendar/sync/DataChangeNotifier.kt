package com.covelo.calendar.sync

import android.content.Context
import android.content.Intent

/**
 * Announces "the local cache just changed" to whatever part of this app is currently on screen.
 *
 * The WebView and the native layer keep two separate copies of the same calendar — localStorage
 * on one side, event_cache.json on the other — and the only thing that reconciles them is the
 * server. Left to its own devices the WebView only re-pulls on focus or on a 60s timer, so a
 * change that arrived natively (a widget edit, a periodic sync) could sit invisible in the app
 * the user is actually looking at for up to a minute. This closes that gap: the sync that just
 * learned something tells the WebView to go and look now.
 *
 * Scoped to our own package, so it is not an exported broadcast anyone else can see or send.
 */
object DataChangeNotifier {
    const val ACTION = "com.covelo.calendar.DATA_CHANGED"

    fun broadcast(context: Context) {
        context.sendBroadcast(Intent(ACTION).setPackage(context.packageName))
    }
}
