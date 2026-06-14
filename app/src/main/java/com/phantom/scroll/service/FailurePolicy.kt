package com.phantom.scroll.service

/** Outcome of recording a gesture failure, used by [ScrollOrchestrator]. */
enum class FailureDecision {
    /** 计入一次失败，尚未达阈值，继续循环。 */
    Continue,
    /** 达到阈值，应自动暂停（调用方负责停 isRunning 与用户反馈）。 */
    AutoPause,
    /** 当前未在运行（手动暂停/锁屏），不计失败。 */
    Ignored
}

/**
 * Pure logic that owns consecutive-failure counting and the auto-pause threshold.
 * Extracted from ScrollOrchestrator so it is unit-testable without Android.
 */
class FailurePolicy(private val threshold: Int = 3) {
    private var consecutive = 0

    /**
     * Records a failure. Returns the decision the caller should act on.
     * Failures recorded while [isRunning] is false are [FailureDecision.Ignored]
     * (manual pause / lock screen) and do not increment the counter.
     */
    fun recordFailure(isRunning: Boolean): FailureDecision {
        if (!isRunning) return FailureDecision.Ignored
        consecutive++
        return if (consecutive >= threshold) {
            consecutive = 0
            FailureDecision.AutoPause
        } else {
            FailureDecision.Continue
        }
    }

    /** Clears the counter on a successful gesture. */
    fun recordSuccess() {
        consecutive = 0
    }

    /** Current consecutive failure count since the last success (read-only, for logging). */
    fun runsAfterLastSuccess(): Int = consecutive
}
