package com.phantom.scroll

import android.app.Application
import com.phantom.scroll.notification.NotificationHelper

class PhantomScrollApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Create the low-importance notification channel used for the service status
        // notification (this is a normal notification, not a foreground-service notification).
        NotificationHelper.createChannel(this)
    }
}
