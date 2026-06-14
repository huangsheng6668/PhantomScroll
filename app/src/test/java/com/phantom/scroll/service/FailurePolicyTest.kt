package com.phantom.scroll.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FailurePolicyTest {

    @Test
    fun success_resets_consecutive_count() {
        val policy = FailurePolicy(threshold = 3)
        // two failures, then success clears the count
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        policy.recordSuccess()
        // after reset, need 3 more to auto-pause
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.AutoPause, policy.recordFailure(isRunning = true))
    }

    @Test
    fun reaches_threshold_autoPauses_and_resets() {
        val policy = FailurePolicy(threshold = 3)
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.AutoPause, policy.recordFailure(isRunning = true))
        // counter reset after auto-pause; another failure is the first again
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
    }

    @Test
    fun failure_while_not_running_is_ignored_and_not_counted() {
        // Manual pause / lock screen must not inflate the failure count.
        val policy = FailurePolicy(threshold = 3)
        assertEquals(FailureDecision.Ignored, policy.recordFailure(isRunning = false))
        assertEquals(FailureDecision.Ignored, policy.recordFailure(isRunning = false))
        // still only the first real failure counted afterwards
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.AutoPause, policy.recordFailure(isRunning = true))
    }
}
