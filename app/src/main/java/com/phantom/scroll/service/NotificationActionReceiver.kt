package com.phantom.scroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.util.PhantomLog

/**
 * Static BroadcastReceiver declared in the manifest.
 * Receives control actions (play/pause and stop) from the status bar notification.
 * Using a manifest-declared receiver ensures that even if the app process has been killed,
 * the system will wake up the process to deliver the broadcast, which "pulls up" the app
 * and executes the action.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    private val TAG = "NotificationActionReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        PhantomLog.d(TAG, "onReceive action: $action")

        val activeService = PhantomScrollService.instance
        if (activeService == null) {
            PhantomLog.w(TAG, "PhantomScrollService is not active yet.")
            when (action) {
                NotificationHelper.ACTION_TOGGLE -> {
                    // Race: the process was woken from death and the accessibility
                    // service has not reconnected yet. Queue the toggle;
                    // PhantomScrollService.onServiceConnected consumes it once the
                    // repository exists. (After a process death the notification is
                    // re-posted in the paused state, so "toggle" unambiguously means
                    // "start".)
                    setPendingToggle()
                    PhantomLog.d(TAG, "Queued pending toggle for service reconnect.")
                }
                NotificationHelper.ACTION_STOP -> {
                    // Best-effort cleanup of KeepAliveService if the main service is inactive
                    KeepAliveService.stop(context.applicationContext)
                }
            }
            return
        }

        // Both actions are synchronous, non-suspending mutations (StateFlow write +
        // disableSelf()), so they run inline right here. The old goAsync()+coroutine dance
        // added nothing — a per-broadcast CoroutineScope that was never cancelled — while
        // risking the ~10s broadcast timeout if the main thread got busy before finish().
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
    }

    companion object {
        @Volatile
        private var pendingToggle = false

        private fun setPendingToggle() {
            pendingToggle = true
        }

        /**
         * Consume the toggle queued while the service was (re)connecting. Clears the
         * flag atomically so a later reconnect cannot re-apply a stale request.
         */
        @Synchronized
        fun consumePendingToggle(): Boolean {
            val pending = pendingToggle
            pendingToggle = false
            return pending
        }
    }
}
