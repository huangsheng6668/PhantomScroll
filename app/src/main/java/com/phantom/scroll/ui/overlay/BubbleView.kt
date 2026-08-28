package com.phantom.scroll.ui.overlay

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.phantom.scroll.R

/**
 * Collapsed overlay bubble: a 20dp dark-glass circle (translucent surface + cyan ring)
 * carrying the vector ghost glyph. Imperative only.
 *
 * Keeps an elevation shadow so the bubble still separates from dark app backgrounds;
 * on light pages the dark fill itself provides the contrast the old white bubble lacked.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val pressInterpolator = DecelerateInterpolator()

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_bubble, this, true)
        // Clip the glyph to the bubble's circle so nothing can poke past the oval background.
        findViewById<ImageView>(R.id.bubble_icon)?.let { icon ->
            icon.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            icon.clipToOutline = true
        }
        // Circular outline + elevation → drop shadow that pops the bubble off any background.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        elevation = 6f * resources.displayMetrics.density
    }

    /** Tactile press feedback: shrink while pressed, spring back on release. */
    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        val target = if (pressed) 0.85f else 1f
        animate().scaleX(target).scaleY(target)
            .setDuration(90)
            .setInterpolator(pressInterpolator)
            .start()
    }
}
