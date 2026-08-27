package com.phantom.scroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Event receiver coordinator for the service.
 * Handles system events (screen off/on) via [ScreenStateCoordinator] and notification
 * commands (toggle/stop). All receivers registered as [Context.RECEIVER_NOT_EXPORTED].
 *
 * Also mirrors [SettingsRepository.isRunning] into [ScreenStateCoordinator.onRunningChanged]
 * so a pause while the screen is ON clears the persisted resume intent.
 *
 * @param scope service scope the isRunning collector runs in (main-thread dispatcher).
 * @param readResumeIntent reads the persisted "resume after unlock" intent.
 * @param writeResumeIntent persists / clears that intent.
 */
class ServiceEventReceiver(
    private val context: Context,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val readResumeIntent: () -> Boolean = { false },
    private val writeResumeIntent: (Boolean) -> Unit = {},
    private val onStopService: () -> Unit
) {
    private val TAG = "ServiceEventReceiver"
    private val screenState = ScreenStateCoordinator(
        isRunning = repository.isRunning,
        readPersistedIntent = readResumeIntent,
        writePersistedIntent = writeResumeIntent
    )

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

    private var runningJob: Job? = null

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
        runningJob = scope.launch {
            repository.isRunning.collect { screenState.onRunningChanged(it) }
        }
    }

    fun stop() {
        safeUnregister(screenReceiver)
        runningJob?.cancel()
        runningJob = null
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            PhantomLog.w(TAG, "Receiver already unregistered or not found: ${e.message}")
        }
    }
}
