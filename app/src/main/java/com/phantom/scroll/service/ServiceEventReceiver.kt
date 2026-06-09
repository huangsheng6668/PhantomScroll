package com.phantom.scroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.phantom.scroll.config.ScrollConfig
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.util.PhantomLog

/**
 * Event receiver coordinator for the service.
 * Handles system events (screen off/on) and notification commands (play, pause, stop).
 * Registers all receivers as [Context.RECEIVER_NOT_EXPORTED] for optimal security.
 */
class ServiceEventReceiver(
    private val context: Context,
    private val config: ScrollConfig,
    private val onStopService: () -> Unit
) {
    private val TAG = "ServiceEventReceiver"

    private var wasRunningBeforeScreenOff = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    PhantomLog.d(TAG, "Screen off → pausing autoscroll.")
                    if (config.isRunning.value) {
                        wasRunningBeforeScreenOff = true
                        config.isRunning.value = false
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    PhantomLog.d(TAG, "User present (unlocked) → restoring autoscroll state.")
                    if (wasRunningBeforeScreenOff) {
                        wasRunningBeforeScreenOff = false
                        config.isRunning.value = true
                    }
                }
            }
        }
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NotificationHelper.ACTION_TOGGLE -> {
                    config.isRunning.value = !config.isRunning.value
                    PhantomLog.d(TAG, "Notification toggle → isRunning: ${config.isRunning.value}")
                }
                NotificationHelper.ACTION_STOP -> {
                    PhantomLog.d(TAG, "Notification stop → disabling service.")
                    config.isRunning.value = false
                    onStopService()
                }
            }
        }
    }

    /**
     * Registers receivers for screen state and notification action intents.
     */
    fun start() {
        // Register screen off / unlock receiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenReceiver, screenFilter)
        }

        // Register notification action receiver
        val notificationFilter = IntentFilter().apply {
            addAction(NotificationHelper.ACTION_TOGGLE)
            addAction(NotificationHelper.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use RECEIVER_NOT_EXPORTED to prevent external apps from spoofing control actions
            context.registerReceiver(notificationActionReceiver, notificationFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(notificationActionReceiver, notificationFilter)
        }
    }

    /**
     * Unregisters all broadcast receivers.
     */
    fun stop() {
        safeUnregister(screenReceiver)
        safeUnregister(notificationActionReceiver)
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            PhantomLog.w(TAG, "Receiver already unregistered or not found: ${e.message}")
        }
    }
}
