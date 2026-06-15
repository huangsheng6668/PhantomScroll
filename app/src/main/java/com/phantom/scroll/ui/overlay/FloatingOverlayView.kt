package com.phantom.scroll.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.slider.Slider
import com.phantom.scroll.R
import com.phantom.scroll.data.Preset
import com.phantom.scroll.data.PresetSelection
import com.phantom.scroll.data.ScrollDirection
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.ScrollStats
import com.phantom.scroll.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Native floating overlay view. Renders the collapsed handle or the expanded panel based on
 * [PanelState], collects [SettingsRepository] flows to refresh itself imperatively, and emits
 * user interactions (slider/button/drag) back into the repository / panel-state flow / position
 * callback. Holds NO business logic beyond view↔state binding.
 *
 * MUST be constructed with a Material3-themed context (the overlay Slider requires it); the
 * controller wraps the service context in [R.style.Theme_PhantomScroll_Overlay].
 */
class FloatingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var repository: SettingsRepository? = null
    private var panelStateFlow: MutableStateFlow<PanelState>? = null
    private var onUpdatePosition: ((x: Int, y: Int) -> Unit)? = null
    private var bound = false

    // panel child views
    private val panelRoot: View
    private val titleText: TextView
    private val foldButton: View
    private val durationSlider: Slider
    private val intervalSlider: Slider
    private val distanceSlider: Slider
    private val durationValue: TextView
    private val intervalValue: TextView
    private val distanceValue: TextView
    private val playButton: Button
    // handle child views
    private val handleRoot: View
    private val handleVisualWrap: View
    private val handleVisual: View

    // Phase 3 controls
    private val presetRow: View
    private val chipNovel: TextView
    private val chipComic: TextView
    private val chipCustom: TextView
    private val directionButton: com.google.android.material.button.MaterialButton
    private val statsRow: View
    private val statsText: TextView
    private val perAppSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private val perAppLabel: TextView

    // position / edge state
    private var currentX = 0
    private var currentY = 200
    private var isLeftEdge = true

    // drag / animation tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private var disallowIntercept = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var snapAnimator: ValueAnimator? = null

    // guard: programmatic slider setValue triggers the change listener; skip repo write then.
    private var applyingFromFlow = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_panel, this, true)
        LayoutInflater.from(context).inflate(R.layout.overlay_handle, this, true)

        panelRoot = findViewById(R.id.panel_root)
        titleText = findViewById(R.id.title_text)
        foldButton = findViewById(R.id.fold_button)
        durationSlider = findViewById(R.id.slider_duration)
        intervalSlider = findViewById(R.id.slider_interval)
        distanceSlider = findViewById(R.id.slider_distance)
        durationValue = findViewById(R.id.value_duration)
        intervalValue = findViewById(R.id.value_interval)
        distanceValue = findViewById(R.id.value_distance)
        playButton = findViewById(R.id.play_button)

        handleRoot = findViewById(R.id.handle_root)
        handleVisualWrap = findViewById(R.id.handle_visual_wrap)
        handleVisual = findViewById(R.id.handle_visual)

        // Phase 3 controls
        presetRow = findViewById(R.id.preset_row)
        chipNovel = findViewById(R.id.chip_novel)
        chipComic = findViewById(R.id.chip_comic)
        chipCustom = findViewById(R.id.chip_custom)
        directionButton = findViewById(R.id.direction_button)
        statsRow = findViewById(R.id.stats_row)
        statsText = findViewById(R.id.stats_text)
        perAppSwitch = findViewById(R.id.perapp_switch)
        perAppLabel = findViewById(R.id.perapp_label)

        // user → repository (only for genuine user changes)
        durationSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateActive { it.copy(duration = value.toLong()) }
        }
        intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateActive { it.copy(interval = value.toLong()) }
        }
        distanceSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateActive { it.copy(distanceRatio = value) }
        }

        handleRoot.setOnClickListener { panelStateFlow?.value = PanelState.Expanded }
        foldButton.setOnClickListener { panelStateFlow?.value = PanelState.Collapsed }
        playButton.setOnClickListener { repository?.toggleRunning() }

        chipNovel.setOnClickListener { scope.launch { repository?.applyPreset(Preset.NOVEL) } }
        chipComic.setOnClickListener { scope.launch { repository?.applyPreset(Preset.COMIC) } }
        chipCustom.setOnClickListener { scope.launch { repository?.applyCustomPreset() } }

        directionButton.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            val active = repo.activeSettings.value
            val next = if (active.direction == ScrollDirection.UP) ScrollDirection.DOWN else ScrollDirection.UP
            scope.launch { repo.updateActive(active.copy(direction = next)) }
        }

        statsRow.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            scope.launch {
                repo.resetStats()
                PhantomToast.show(context, "统计已重置")
            }
        }

        perAppSwitch.setOnCheckedChangeListener { _, checked ->
            // 避免程序化 setChecked 触发写：用 applyingFromFlow 守卫（复用现有标志）
            if (applyingFromFlow) return@setOnCheckedChangeListener
            repository?.setPerAppEnabled(checked)
        }

        perAppLabel.setOnLongClickListener {
            val repo = repository ?: return@setOnLongClickListener false
            scope.launch {
                repo.forgetActiveProfile()
                PhantomToast.show(context, "已忘记当前 App 配置")
            }
            true
        }

        applyState(PanelState.Expanded) // default: panel visible, handle hidden
        applyRunning(false)
    }

    /**
     * Wires dependencies. MUST be called after construction and before the view is added to the
     * WindowManager (so onAttachedToWindow can start collecting immediately).
     */
    fun bind(
        repository: SettingsRepository,
        panelStateFlow: MutableStateFlow<PanelState>,
        initialX: Int,
        initialY: Int,
        onUpdatePosition: (x: Int, y: Int) -> Unit
    ) {
        this.repository = repository
        this.panelStateFlow = panelStateFlow
        this.onUpdatePosition = onUpdatePosition
        this.currentX = initialX
        this.currentY = initialY
        this.bound = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bound) startCollecting()
    }

    override fun onDetachedFromWindow() {
        collectJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun startCollecting() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        collectJob = scope.launch {
            launch { flow.collect { applyState(it) } }
            launch { repo.activeSettings.collect { applySettings(it) } }
            launch { repo.isRunning.collect { applyRunning(it) } }
            launch {
                combine(repo.screenWidth, repo.screenHeight) { w, h -> w to h }
                    .collect { repositionToBounds(it.first, it.second) }
            }
            launch { repo.selectedPreset.collect { applyPresetSelection(it) } }
            launch { repo.stats.collect { applyStats(it) } }
            launch { repo.perAppEnabled.collect { applyPerAppEnabled(it) } }
            launch { repo.currentPackage.collect { applyCurrentPackage(it) } }
        }
    }

    private fun applyState(state: PanelState) {
        val repo = repository ?: return
        val screenWidth = repo.screenWidth.value
        val panelWidthPx = (130f * resources.displayMetrics.density).toInt()
        val handleWidthPx = (32f * resources.displayMetrics.density).toInt()

        when (state) {
            PanelState.Collapsed -> {
                handleRoot.visibility = VISIBLE
                panelRoot.visibility = GONE
                currentX = if (isLeftEdge) 0 else screenWidth - handleWidthPx
                onUpdatePosition?.invoke(currentX, currentY)
            }
            PanelState.Expanded -> {
                panelRoot.visibility = VISIBLE
                handleRoot.visibility = GONE
                currentX = if (isLeftEdge) 0 else screenWidth - panelWidthPx
                onUpdatePosition?.invoke(currentX, currentY)
            }
            PanelState.Snapping -> {
                panelRoot.visibility = VISIBLE
                handleRoot.visibility = GONE
            }
        }
    }

    private fun applySettings(s: ScrollSettings) {
        applyingFromFlow = true
        // Defensive: clamp values to Slider boundaries in case stored data is out-of-bounds.
        durationSlider.value = s.duration.toFloat().coerceIn(durationSlider.valueFrom, durationSlider.valueTo)
        intervalSlider.value = s.interval.toFloat().coerceIn(intervalSlider.valueFrom, intervalSlider.valueTo)
        distanceSlider.value = s.distanceRatio.coerceIn(distanceSlider.valueFrom, distanceSlider.valueTo)
        applyingFromFlow = false
        durationValue.text = "${s.duration}ms"
        intervalValue.text = String.format("%.1fs", s.interval / 1000f)
        distanceValue.text = "${(s.distanceRatio * 100).toInt()}%"
        // direction button glyph tracks the active direction (Task 9)
        directionButton.text = if (s.direction == ScrollDirection.DOWN) "↓" else "↑"
    }

    private fun applyRunning(running: Boolean) {
        playButton.text = if (running) "⏸ 暂停" else "▶ 开始"
        val color = ResourcesCompat.getColor(
            resources,
            if (running) R.color.error_red else R.color.success_green,
            null
        )
        playButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun applyPresetSelection(sel: PresetSelection) {
        // single-select highlight: selected chip uses the selected bg + cyan text
        val selectedBg = ResourcesCompat.getDrawable(resources, R.drawable.overlay_chip_bg_selected, null)
        val plainBg = ResourcesCompat.getDrawable(resources, R.drawable.overlay_chip_bg, null)
        val cyan = ResourcesCompat.getColor(resources, R.color.phantom_cyan, null)
        val plain = ResourcesCompat.getColor(resources, R.color.text_primary, null)
        listOf(
            chipNovel to (sel is PresetSelection.BuiltIn && sel.preset == Preset.NOVEL),
            chipComic to (sel is PresetSelection.BuiltIn && sel.preset == Preset.COMIC),
            chipCustom to (sel is PresetSelection.Custom)
        ).forEach { (chip, on) ->
            chip.background = if (on) selectedBg else plainBg
            chip.setTextColor(if (on) cyan else plain)
        }
    }

    private fun applyStats(stats: ScrollStats) {
        // spec §3.2: elapsed shown in minutes, Math.round(elapsedMs / 60000.0)
        val minutes = Math.round(stats.elapsedMs / 60000.0)
        statsText.text = "已翻 ${stats.swipeCount} 次 · 约 $minutes 分钟"
    }

    private fun applyPerAppEnabled(enabled: Boolean) {
        applyingFromFlow = true
        perAppSwitch.isChecked = enabled
        applyingFromFlow = false
        updatePerAppLabelVisibility()
    }

    private fun applyCurrentPackage(pkg: String?) {
        updatePerAppLabelVisibility()
    }

    private fun updatePerAppLabelVisibility() {
        val repo = repository ?: return
        val pkg = repo.currentPackage.value
        val enabled = repo.perAppEnabled.value
        perAppLabel.visibility = if (pkg != null && enabled) VISIBLE else GONE
        perAppLabel.text = if (pkg != null) "📖 当前：$pkg" else ""
        // 用包名做展示名（避免引 PackageManager 解析 label 的开销与权限故事）；
        // 若需友好名，Phase 4 再加 PackageManager 缓存。
    }

    private object PhantomToast {
        fun show(ctx: Context, msg: String) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateActive(transform: (ScrollSettings) -> ScrollSettings) {
        val repo = repository ?: return
        scope.launch { repo.updateActive(transform(repo.activeSettings.value)) }
    }

    private fun repositionToBounds(screenWidth: Int, screenHeight: Int) {
        // Use actual measured view dimensions; fallback to 130dp / WRAP height estimates.
        val flow = panelStateFlow
        val isCollapsed = flow != null && flow.value == PanelState.Collapsed
        val widthPx = if (isCollapsed) {
            (32f * resources.displayMetrics.density).toInt()
        } else {
            (130f * resources.displayMetrics.density).toInt()
        }
        val panelH = panelRoot.height.takeIf { it > 0 }
            ?: (200f * resources.displayMetrics.density).toInt()
        val nx = if (!dragging) {
            OverlayGeometry.edgeX(isLeftEdge, screenWidth, widthPx)
        } else {
            OverlayGeometry.clamp(currentX, 0, (screenWidth - widthPx).coerceAtLeast(0))
        }
        val ny = OverlayGeometry.clamp(currentY, 0, (screenHeight - panelH).coerceAtLeast(0))
        if (nx != currentX || ny != currentY) {
            currentX = nx
            currentY = ny
            onUpdatePosition?.invoke(nx, ny)
        }
    }

    private fun isTouchInsideView(ev: MotionEvent, view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width &&
               ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
    }

    /**
     * Whole-panel drag: intercept once movement exceeds touch slop. Material Slider calls
     * requestDisallowInterceptTouchEvent while its thumb is dragged, so it still works; button
     * taps don't move so they aren't stolen.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            snapAnimator?.cancel()
            downRawX = ev.rawX; downRawY = ev.rawY
            lastRawX = ev.rawX; lastRawY = ev.rawY
            dragging = false

            // Do not intercept if user touches interactive children to avoid click cancellation or slider lag.
            val inInteractive = isTouchInsideView(ev, durationSlider) ||
                    isTouchInsideView(ev, intervalSlider) ||
                    isTouchInsideView(ev, distanceSlider) ||
                    isTouchInsideView(ev, playButton) ||
                    isTouchInsideView(ev, foldButton) ||
                    isTouchInsideView(ev, presetRow) ||
                    isTouchInsideView(ev, directionButton) ||
                    isTouchInsideView(ev, statsRow) ||
                    isTouchInsideView(ev, perAppSwitch)
            disallowIntercept = inInteractive
        }

        if (disallowIntercept) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                    dragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disallowIntercept = false
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Outside touch (FLAG_WATCH_OUTSIDE_TOUCH in Expanded state) -> collapse.
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val flow = panelStateFlow
            if (flow != null && flow.value == PanelState.Expanded) {
                flow.value = PanelState.Collapsed
            }
            return true
        }
        // Consume ACTION_DOWN to ensure subsequent ACTION_MOVE/UP events are delivered to this window.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            return true
        }
        val repo = repository ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    if (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop) {
                        dragging = true
                        lastRawX = ev.rawX
                        lastRawY = ev.rawY
                    }
                }
                if (dragging) {
                    val w = repo.screenWidth.value
                    val h = repo.screenHeight.value
                    val dx = (ev.rawX - lastRawX).toInt()
                    val dy = (ev.rawY - lastRawY).toInt()
                    // 130dp panel fallback in px; matches repositionToBounds logic.
                    val panelW = panelRoot.width.takeIf { it > 0 }
                        ?: (130f * resources.displayMetrics.density).toInt()
                    val panelH = panelRoot.height.takeIf { it > 0 }
                        ?: (200f * resources.displayMetrics.density).toInt()
                    currentX = OverlayGeometry.clamp(currentX + dx, 0, w - panelW)
                    currentY = OverlayGeometry.clamp(currentY + dy, 0, h - panelH)
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    onUpdatePosition?.invoke(currentX, currentY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disallowIntercept = false
                if (dragging) {
                    dragging = false
                    performSnap()
                }
            }
        }
        return true
    }

    private fun performSnap() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        val screenWidth = repo.screenWidth.value
        val panelWidthPx = panelRoot.width.takeIf { it > 0 }
            ?: (130f * resources.displayMetrics.density).toInt()
        val target = OverlayGeometry.snapTarget(
            panelCenterX = currentX + panelWidthPx / 2,
            screenWidth = screenWidth,
            panelWidthPx = panelWidthPx
        )
        isLeftEdge = target.isLeftEdge
        updateHandleEdge()
        flow.value = PanelState.Snapping
        snapAnimator = ValueAnimator.ofInt(currentX, target.x).apply {
            duration = 250
            addUpdateListener { a ->
                currentX = a.animatedValue as Int
                onUpdatePosition?.invoke(currentX, currentY)
            }
        }
        snapAnimator?.start()
        // Collapse to handle once the snap finishes (small buffer over 250ms).
        postDelayed({ if (flow.value == PanelState.Snapping) flow.value = PanelState.Collapsed }, 270)
    }

    private fun updateHandleEdge() {
        handleVisual.background = ResourcesCompat.getDrawable(
            resources,
            if (isLeftEdge) R.drawable.overlay_handle_left else R.drawable.overlay_handle_right,
            null
        )
        val params = (handleVisualWrap.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (isLeftEdge) (android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
            else (android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
        }
        handleVisualWrap.layoutParams = params
    }
}
