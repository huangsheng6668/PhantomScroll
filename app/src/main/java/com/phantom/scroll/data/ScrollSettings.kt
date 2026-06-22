package com.phantom.scroll.data

/**
 * Immutable scroll configuration. The single set of tunables that drives gesture generation.
 * @param duration 单次滑动时长 ms，范围 150..1500
 * @param interval 两次滑动间隔 ms，范围 500..10000
 * @param distanceRatio 滑动距离占屏幕安全区高度比例，范围 0.30..0.95
 * @param direction 滑动方向
 */
data class ScrollSettings(
    val duration: Long,
    val interval: Long,
    val distanceRatio: Float,
    val direction: ScrollDirection = ScrollDirection.UP
) {
    companion object {
        /** 与旧 ScrollConfig 默认值一致，保证迁移后行为不变。 */
        val DEFAULT = ScrollSettings(
            duration = 500L,
            interval = 2000L,
            distanceRatio = 0.75f,
            direction = ScrollDirection.UP
        )
    }
}
