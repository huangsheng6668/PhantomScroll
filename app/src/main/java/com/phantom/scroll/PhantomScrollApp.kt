package com.phantom.scroll

import android.app.Application
import com.phantom.scroll.notification.NotificationHelper

class PhantomScrollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Foreground Service Notification Channel
        NotificationHelper.createChannel(this)
    }
}
