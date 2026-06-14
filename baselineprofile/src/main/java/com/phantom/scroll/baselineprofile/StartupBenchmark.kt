package com.phantom.scroll.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark that measures [StartupTimingMetric] for the permission screen.
 * Run twice — once with [CompilationMode.None] (no profile) and once with
 * [CompilationMode.Partial] (profile installed) — to quantify the Baseline Profile win.
 *
 * Run with:  ./gradlew :baselineprofile:connectedReleaseBenchmark
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoProfile() = benchmark(CompilationMode.None())

    @Test
    fun startupWithProfile() = benchmark(CompilationMode.Partial())

    private fun benchmark(mode: CompilationMode) {
        rule.measureRepeated(
            packageName = "com.phantom.scroll",
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.COLD,
            compilationMode = mode
        ) {
            pressHome()
            startActivityAndWait()
        }
    }
}
