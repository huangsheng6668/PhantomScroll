package com.phantom.scroll.ui.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.phantom.scroll.R

/**
 * One cell of the 2×2 param grid. Shows label + current step text; tap to reveal an inline
 * [Slider] for fine adjustment. Owns its own expand state. The parent ([FloatingOverlayView])
 * reads [slider] / [isExpanded] to suppress whole-panel drag while the slider is in use.
 *
 * @param stepResolver maps the raw float value to the friendly step label shown when collapsed.
 */
class ParamCellView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelView: TextView
    private val valueView: TextView
    val slider: Slider
    var isExpanded: Boolean = false
        private set

    private var stepResolver: ((Float) -> String)? = null
    var onUserChange: ((Float) -> Unit)? = null
    private var applyingFromFlow = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_param_cell, this, true)
        orientation = VERTICAL
        labelView = findViewById(R.id.param_label)
        valueView = findViewById(R.id.param_value)
        slider = findViewById(R.id.param_slider)

        findViewById<View>(R.id.param_cell_root).setOnClickListener { toggleExpand() }

        slider.addOnChangeListener { _, value, fromUser ->
            refreshValueText(value)
            if (fromUser && !applyingFromFlow) onUserChange?.invoke(value)
        }
    }

    /** Sets static label + slider range + the value→label resolver. Call once after inflate. */
    fun configure(label: String, valueFrom: Float, valueTo: Float, stepResolver: (Float) -> String) {
        labelView.text = label
        slider.valueFrom = valueFrom
        slider.valueTo = valueTo
        slider.value = valueFrom
        this.stepResolver = stepResolver
    }

    /** Pushes [v] from a flow (no writeback) or reflects a user drag (already handled by listener). */
    fun setValue(v: Float, fromFlow: Boolean) {
        applyingFromFlow = true
        slider.value = v.coerceIn(slider.valueFrom, slider.valueTo)
        applyingFromFlow = false
        refreshValueText(slider.value)
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        slider.visibility = if (isExpanded) VISIBLE else GONE
    }

    private fun refreshValueText(v: Float) {
        valueView.text = stepResolver?.invoke(v) ?: ""
    }
}
