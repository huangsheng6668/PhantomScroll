package com.phantom.scroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Static BroadcastReceiver declared in the manifest.
 * Receives control actions (play/pause and stop) from the status bar notification.
 * Using a manifest-declared receiver ensures that even if the app process has been killed,
 * the system will wake up the process to deliver the broadcast, which "pulls up" the app
 * and executes the action.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    private val TAG = "NotificationActionReceiver"
    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        PhantomLog.d(TAG, "onReceive action: $action")

        val activeService = PhantomScrollService.instance
        if (activeService == null) {
            PhantomLog.w(TAG, "PhantomScrollService is not active. Action ignored.")
            if (action == NotificationHelper.ACTION_STOP) {
                // Best-effort cleanup of KeepAliveService if the main service is inactive
                KeepAliveService.stop(context.applicationContext)
            }
            return
        }

        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    NotificationHelper.ACTION_TOGGLE -> {
                        activeService.repository.toggleRunning()
                        PhantomLog.d(TAG, "Toggled running state. New state: ${activeService.repository.isRunning.value}")
                    }
                    NotificationHelper.ACTION_STOP -> {
                        PhantomLog.d(TAG, "Stopping service via notification.")
                        activeService.repository.isRunning.value = false
                        activeService.disableSelf()
                    }
                }
            } catch (e: Exception) {
                PhantomLog.e(TAG, "Error handling broadcast action: $action", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
