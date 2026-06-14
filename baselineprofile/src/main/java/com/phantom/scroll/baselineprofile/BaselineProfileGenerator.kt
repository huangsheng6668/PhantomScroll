package com.phantom.scroll.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for the app's critical user journey: cold-launching
 * [com.phantom.scroll.MainActivity] (the Compose permission screen) and waiting for first frame.
 *
 * Run with:  ./gradlew :baselineprofile:generateReleaseBaselineProfile
 * (requires a connected device/emulator, API 28+, non-debuggable release-ish build).
 *
 * The generated profile lands in app/src/release/generated/baselineProfiles/baseline-prof.txt
 * and is automatically embedded in the release APK by the androidx.baselineprofile plugin.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() = baselineRule.collect("phantomscroll-startup") {
        // Cold launch the permission screen and wait for the first frame to render.
        // This is the only Compose surface in the app post-Phase-2, so it dominates startup cost.
        pressHome()
        startActivityAndWait()
        // Give the Compose hierarchy a moment to settle (permission cards inflate).
        Thread.sleep(500)
    }
}
