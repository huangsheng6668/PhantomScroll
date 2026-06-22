package com.phantom.scroll.ui.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.phantom.scroll.R

/**
 * Collapsed overlay bubble: 56dp circle + live count badge. Imperative refresh only;
 * [setCount] short-circuits identical text to avoid layout passes at ~0.6 swipe/sec.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val countView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_bubble, this, true)
        countView = findViewById(R.id.bubble_count)
    }

    /** Renders [count] via [BadgeFormatter]; skips setText when unchanged. */
    fun setCount(count: Int) {
        val text = BadgeFormatter.format(count)
        if (countView.text.toString() != text) countView.text = text
    }
}
