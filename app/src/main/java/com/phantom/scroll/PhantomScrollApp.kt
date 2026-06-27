package com.phantom.scroll

import android.app.Application
import com.phantom.scroll.notification.NotificationHelper

class PhantomScrollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Create the low-importance notification channel used for the service's foreground
        // notification. It must exist BEFORE PhantomScrollService calls startForeground(), otherwise
        // startForeground throws on Android 8.0+. IMPORTANCE_LOW keeps it silent (no ringing).
        NotificationHelper.createChannel(this)
    }
}
