package com.phantom.scroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.util.PhantomLog

/**
 * Event receiver coordinator for the service.
 * Handles system events (screen off/on) via [ScreenStateCoordinator] and notification
 * commands (toggle/stop). All receivers registered as [Context.RECEIVER_NOT_EXPORTED].
 */
class ServiceEventReceiver(
    private val context: Context,
    private val repository: SettingsRepository,
    private val onStopService: () -> Unit
) {
    private val TAG = "ServiceEventReceiver"
    private val screenState = ScreenStateCoordinator(repository.isRunningMutable)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    PhantomLog.d(TAG, "Screen off → pausing autoscroll.")
                    screenState.onScreenOff()
                }
                Intent.ACTION_USER_PRESENT -> {
                    PhantomLog.d(TAG, "User present (unlocked) → restoring autoscroll state.")
                    screenState.onUserPresent()
                }
            }
        }
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NotificationHelper.ACTION_TOGGLE -> {
                    repository.toggleRunning()
                    PhantomLog.d(TAG, "Notification toggle → isRunning: ${repository.isRunning.value}")
                }
                NotificationHelper.ACTION_STOP -> {
                    PhantomLog.d(TAG, "Notification stop → disabling service.")
                    repository.stopRunning()
                    onStopService()
                }
            }
        }
    }

    fun start() {
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            screenFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val notificationFilter = IntentFilter().apply {
            addAction(NotificationHelper.ACTION_TOGGLE)
            addAction(NotificationHelper.ACTION_STOP)
        }
        ContextCompat.registerReceiver(
            context,
            notificationActionReceiver,
            notificationFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

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
