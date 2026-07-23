package com.phantom.scroll.ui.overlay

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.phantom.scroll.R

/**
 * Collapsed overlay bubble: a 20dp circle carrying the mascot icon. Imperative only.
 *
 * Carries an elevation shadow (the redesign mockup's box-shadow) so the white circle stays
 * visible against light app backgrounds — without it a white bubble vanishes on white pages.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_bubble, this, true)
        // Clip the square launcher icon to the bubble's circle: the icon's content area
        // (16dp square) has a larger diagonal than the 20dp circle's radius, so without
        // clipping its corners poke past the oval background.
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
}
