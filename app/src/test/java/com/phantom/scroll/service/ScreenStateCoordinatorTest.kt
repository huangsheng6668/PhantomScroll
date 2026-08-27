package com.phantom.scroll.service

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStateCoordinatorTest {

    private fun coord(running: Boolean): Pair<ScreenStateCoordinator, MutableStateFlow<Boolean>> {
        val isRunning = MutableStateFlow(running)
        return ScreenStateCoordinator(isRunning) to isRunning
    }

    @Test
    fun screen_off_while_running_pauses_and_remembers() {
        val (c, isRunning) = coord(running = true)
        c.onScreenOff()
        assertEquals(false, isRunning.value)
    }

    @Test
    fun user_present_after_screen_off_restores() {
        val (c, isRunning) = coord(running = true)
        c.onScreenOff()
        c.onUserPresent()
        assertEquals(true, isRunning.value)
    }

    @Test
    fun screen_off_while_not_running_does_not_restore_later() {
        // Regression:熄屏前未运行，亮屏后不得错误自动恢复。
        val (c, isRunning) = coord(running = false)
        c.onScreenOff()
        c.onUserPresent()
        assertEquals(false, isRunning.value)
    }

    @Test
    fun user_present_without_prior_screen_off_is_noop() {
        val (c, isRunning) = coord(running = false)
        c.onUserPresent()
        assertEquals(false, isRunning.value)
    }

    // ---- Persisted resume intent (survives process death while screen off) ---------

    private fun persistedCoord(
        running: Boolean
    ): Triple<ScreenStateCoordinator, MutableStateFlow<Boolean>, BooleanArray> {
        val isRunning = MutableStateFlow(running)
        val persisted = booleanArrayOf(false)
        val c = ScreenStateCoordinator(
            isRunning,
            readPersistedIntent = { persisted[0] },
            writePersistedIntent = { persisted[0] = it }
        )
        return Triple(c, isRunning, persisted)
    }

    @Test
    fun persisted_intent_survives_memory_loss_and_restores_on_unlock() {
        val (c, isRunning, persisted) = persistedCoord(running = true)
        c.onScreenOff()
        // Screen-off persists the intent…
        assertEquals(true, persisted[0])
        // …then the process dies: a FRESH coordinator (no in-memory flag) sees only the
        // persisted store. Unlock must still restore scrolling.
        val isRunning2 = MutableStateFlow(false)
        val persistedRef = persisted
        val c2 = ScreenStateCoordinator(
            isRunning2,
            readPersistedIntent = { persistedRef[0] },
            writePersistedIntent = { persistedRef[0] = it }
        )
        c2.onUserPresent()
        assertEquals(true, isRunning2.value)
        assertEquals(false, persisted[0])
        assertEquals(false, isRunning.value) // original flow untouched by the new coordinator
    }

    @Test
    fun stop_while_screen_on_clears_persisted_intent() {
        val (c, isRunning, persisted) = persistedCoord(running = true)
        c.onScreenOff()
        c.onUserPresent() // restored; intent already false
        isRunning.value = false // user (or auto-pause) stops while the screen is on
        c.onRunningChanged(false)
        assertEquals(false, persisted[0])
        c.onScreenOff() // not running → no intent written
        c.onUserPresent()
        assertEquals(false, isRunning.value) // no phantom restore
    }

    @Test
    fun stop_inside_screen_off_window_keeps_intent() {
        val (c, isRunning, persisted) = persistedCoord(running = true)
        c.onScreenOff()
        assertEquals(true, persisted[0])
        // A running→false transition while the screen is off is screen-caused (the very
        // write onScreenOff performs via the collector) — it must not clear the intent.
        c.onRunningChanged(false)
        assertEquals(true, persisted[0])
        c.onUserPresent()
        assertEquals(true, isRunning.value)
    }
}
