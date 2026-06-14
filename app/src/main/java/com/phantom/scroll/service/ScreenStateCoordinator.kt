package com.phantom.scroll.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pure-ish state machine for screen on/off → autoscroll pause/resume.
 * Operates on the shared [isRunning] flow; holds no Android references,
 * so it is unit-testable. Extracted from ServiceEventReceiver.
 */
class ScreenStateCoordinator(private val isRunning: MutableStateFlow<Boolean>) {
    private var wasRunningBeforeScreenOff = false

    /** ACTION_SCREEN_OFF: pause and remember prior state. */
    fun onScreenOff() {
        if (isRunning.value) {
            wasRunningBeforeScreenOff = true
            isRunning.value = false
        }
    }

    /** ACTION_USER_PRESENT: restore only if it was running before screen-off. */
    fun onUserPresent() {
        if (wasRunningBeforeScreenOff) {
            wasRunningBeforeScreenOff = false
            isRunning.value = true
        }
    }
}
