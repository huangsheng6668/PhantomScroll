package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetRegistryTest {

    @Test
    fun selection_novel_when_global_matches_novel() {
        val sel = PresetRegistry.selectionFor(Preset.NOVEL.settings)
        assertEquals(PresetSelection.BuiltIn(Preset.NOVEL), sel)
    }

    @Test
    fun selection_comic_when_global_matches_comic() {
        val sel = PresetRegistry.selectionFor(Preset.COMIC.settings)
        assertEquals(PresetSelection.BuiltIn(Preset.COMIC), sel)
    }

    @Test
    fun selection_custom_when_global_matches_no_builtin() {
        val arbitrary = ScrollSettings(duration = 600L, interval = 2500L, distanceRatio = 0.7f)
        assertEquals(PresetSelection.Custom, PresetRegistry.selectionFor(arbitrary))
    }

    @Test
    fun builtin_presets_have_documented_values() {
        // spec §3.1: 小说 duration 220 / interval 2300 / ratio 0.45 / UP
        assertEquals(220L, Preset.NOVEL.settings.duration)
        assertEquals(2300L, Preset.NOVEL.settings.interval)
        assertEquals(0.45f, Preset.NOVEL.settings.distanceRatio)
        assertEquals(ScrollDirection.UP, Preset.NOVEL.settings.direction)
        // spec §3.1: 漫画 duration 220 / interval 1500 / ratio 0.55 / UP
        assertEquals(220L, Preset.COMIC.settings.duration)
        assertEquals(1500L, Preset.COMIC.settings.interval)
        assertEquals(0.55f, Preset.COMIC.settings.distanceRatio)
        assertEquals(ScrollDirection.UP, Preset.COMIC.settings.direction)
    }

    @Test
    fun custom_display_name_is_stable() {
        assertEquals("自定义", PresetSelection.Custom.displayName)
        assertEquals("小说", PresetSelection.BuiltIn(Preset.NOVEL).displayName)
        assertEquals("漫画", PresetSelection.BuiltIn(Preset.COMIC).displayName)
    }
}
