package com.phantom.scroll.data

/**
 * Named scene preset. [NOVEL] / [COMIC] are fixed built-ins (spec §3.1);
 * "自定义" is not a [Preset] but the derived [PresetSelection.Custom] state
 * (any global value that matches no built-in).
 */
data class Preset(
    val name: String,
    val settings: ScrollSettings
) {
    companion object {
        /** 小说：长间隔、短距离，适合文字连续阅读。 */
        val NOVEL = Preset(
            name = "小说",
            settings = ScrollSettings(duration = 220L, interval = 2300L, distanceRatio = 0.45f, direction = ScrollDirection.UP)
        )
        /** 漫画：中间隔、大距离，一屏一翻。 */
        val COMIC = Preset(
            name = "漫画",
            settings = ScrollSettings(duration = 220L, interval = 1500L, distanceRatio = 0.55f, direction = ScrollDirection.UP)
        )
        /** All fixed built-ins, in display order. */
        val BUILT_INS: List<Preset> = listOf(NOVEL, COMIC)
    }
}

/**
 * Which preset the current [ScrollSettings] corresponds to.
 * - [BuiltIn]: global exactly matches a built-in [Preset].
 * - [Custom]: global matches no built-in (user-tuned).
 */
sealed interface PresetSelection {
    val displayName: String

    data class BuiltIn(val preset: Preset) : PresetSelection {
        override val displayName: String get() = preset.name
    }

    data object Custom : PresetSelection {
        override val displayName: String get() = "自定义"
    }
}
