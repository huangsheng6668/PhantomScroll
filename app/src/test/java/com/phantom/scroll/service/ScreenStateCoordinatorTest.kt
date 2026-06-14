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
}
