package com.phantom.scroll.config

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.util.DisplayMetrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollConfigTest {

    private val sharedPreferences: SharedPreferences = mock()
    private val editor: SharedPreferences.Editor = mock()
    private val context: Context = mock()
    private val resources: Resources = mock()
    private val displayMetrics = DisplayMetrics().apply {
        widthPixels = 1080
        heightPixels = 2400
    }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        whenever(context.getSharedPreferences(any(), any())).thenReturn(sharedPreferences)
        whenever(context.resources).thenReturn(resources)
        whenever(resources.displayMetrics).thenReturn(displayMetrics)
        
        whenever(sharedPreferences.edit()).thenReturn(editor)
        whenever(editor.putLong(any(), any())).thenReturn(editor)
        whenever(editor.putFloat(any(), any())).thenReturn(editor)
        
        // Setup defaults in mock shared preferences
        whenever(sharedPreferences.getLong(eq("scroll_duration"), any())).thenReturn(600L)
        whenever(sharedPreferences.getLong(eq("scroll_interval"), any())).thenReturn(3000L)
        whenever(sharedPreferences.getFloat(eq("scroll_distance_ratio"), any())).thenReturn(0.80f)
    }

    @Test
    fun testScrollConfig_initialization() = testScope.runTest {
        val config = ScrollConfig(context, testScope.backgroundScope)

        // Verify loaded properties from shared preferences
        assertEquals(600L, config.scrollDuration.value)
        assertEquals(3000L, config.scrollInterval.value)
        assertEquals(0.80f, config.scrollDistanceRatio.value)

        // Verify initial screen dimensions
        assertEquals(1080, config.screenWidth.value)
        assertEquals(2400, config.screenHeight.value)
    }

    @Test
    fun testScrollConfig_snapshot() = testScope.runTest {
        val config = ScrollConfig(context, testScope.backgroundScope)
        val snapshot = config.snapshot()

        assertEquals(600L, snapshot.duration)
        assertEquals(3000L, snapshot.interval)
        assertEquals(0.80f, snapshot.distanceRatio)
    }
}
