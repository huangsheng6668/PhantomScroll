package com.phantom.scroll.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pure-ish state machine for screen on/off → autoscroll pause/resume.
 * Operates on the shared [isRunning] flow; holds no Android references,
 * so it is unit-testable. Extracted from ServiceEventReceiver.
 *
 * **Resume intent persistence.** "Was running before screen-off" lives in TWO places:
 * the in-memory flag and (via the injected [readPersistedIntent] / [writePersistedIntent]
 * callbacks, e.g. SharedPreferences) on disk. The persisted copy exists so the
 * "亮屏后恢复" promise survives process death while the screen is off — that is exactly
 * when aggressive OEM killers strike. Feed [onRunningChanged] from a collector over
 * [isRunning] so a pause that happens while the screen is ON (manual pause, auto-pause,
 * stop) invalidates any persisted intent; a pause inside the screen-off window does not.
 *
 * Note: the intent deliberately survives reboots (no BOOT_COMPLETED invalidation) —
 * resuming after a reboot-unlock matches the autostart guide's "重启后自动恢复服务状态".
 */
class ScreenStateCoordinator(
    private val isRunning: MutableStateFlow<Boolean>,
    /** Reads the persisted resume intent (called on ACTION_USER_PRESENT only). */
    private val readPersistedIntent: () -> Boolean = { false },
    /** Persists / clears the resume intent. */
    private val writePersistedIntent: (Boolean) -> Unit = {}
) {
    private var wasRunningBeforeScreenOff = false

    /**
     * True between [onScreenOff] and [onUserPresent] — marks the window in which an
     * isRunning=false transition is screen-caused (see [onRunningChanged]).
     */
    private var screenOff = false

    /** ACTION_SCREEN_OFF: pause and remember prior state (memory + persisted intent). */
    fun onScreenOff() {
        screenOff = true
        if (isRunning.value) {
            wasRunningBeforeScreenOff = true
            // Persist BEFORE flipping isRunning: the isRunning collector may run inline
            // (Main.immediate) and must not observe/clear the intent we are about to set.
            writePersistedIntent(true)
            isRunning.value = false
        }
    }

    /**
     * ACTION_USER_PRESENT: restore only if it was running before screen-off — in memory
     * OR persisted (the latter covers "process died while the screen was off").
     */
    fun onUserPresent() {
        screenOff = false
        if (wasRunningBeforeScreenOff || readPersistedIntent()) {
            wasRunningBeforeScreenOff = false
            writePersistedIntent(false)
            if (!isRunning.value) isRunning.value = true
        }
    }

    /**
     * Mirrors isRunning transitions (feed from a collector). A stop while the screen is
     * ON is a deliberate user/system decision and clears the persisted resume intent;
     * a stop inside the screen-off window is screen-caused and keeps it.
     */
    fun onRunningChanged(running: Boolean) {
        if (!running && !screenOff) writePersistedIntent(false)
    }
}
