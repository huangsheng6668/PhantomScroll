package com.phantom.scroll.config

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ScrollConfig(context: Context, scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("phantom_scroll_prefs", Context.MODE_PRIVATE)

    // Single scroll duration (ms): 200ms - 1500ms, default 500ms
    val scrollDuration = MutableStateFlow(prefs.getLong("scroll_duration", 500L))

    // Scroll interval (ms): 500ms - 10000ms, default 2000ms
    val scrollInterval = MutableStateFlow(prefs.getLong("scroll_interval", 2000L))

    // Scroll distance ratio (percentage of screen height): 30% - 95%, default 75%
    val scrollDistanceRatio = MutableStateFlow(prefs.getFloat("scroll_distance_ratio", 0.75f))

    // Switch controlling auto scrolling
    val isRunning = MutableStateFlow(false)

    // Dynamic screen dimensions (pixels) updated on orientation changes
    val screenWidth = MutableStateFlow(context.resources.displayMetrics.widthPixels)
    val screenHeight = MutableStateFlow(context.resources.displayMetrics.heightPixels)

    init {
        scope.launch {
            scrollDuration.collect { duration ->
                prefs.edit().putLong("scroll_duration", duration).apply()
            }
        }
        scope.launch {
            scrollInterval.collect { interval ->
                prefs.edit().putLong("scroll_interval", interval).apply()
            }
        }
        scope.launch {
            scrollDistanceRatio.collect { ratio ->
                prefs.edit().putFloat("scroll_distance_ratio", ratio).apply()
            }
        }
    }

    // Takes a thread-safe snapshot of the current configurations
    fun snapshot(): ConfigSnapshot {
        return ConfigSnapshot(
            duration = scrollDuration.value,
            interval = scrollInterval.value,
            distanceRatio = scrollDistanceRatio.value
        )
    }
}

data class ConfigSnapshot(
    val duration: Long,
    val interval: Long,
    val distanceRatio: Float
)

