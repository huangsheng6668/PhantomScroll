package com.phantom.scroll.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AlphaAnimation
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.phantom.scroll.R
import com.phantom.scroll.data.ScrollDirection
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.ScrollStats
import com.phantom.scroll.data.SettingsIntent
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
 * Orchestrator for the redesigned overlay. Inflates the panel + bubble, collects
 * [SettingsRepository] flows to refresh imperative views, and emits user interactions back.
 * Holds NO business logic beyond view↔state binding. Param logic lives in [ParamSteps];
 * geometry in [OverlayGeometry].
 *
 * MUST be constructed with a Material3-themed context (Slider requires it); the controller wraps
 * the service context in [R.style.Theme_PhantomScroll_Overlay].
 */
class FloatingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var viewScope: CoroutineScope? = null
    private fun launchOnScope(block: suspend CoroutineScope.() -> Unit) {
        viewScope?.launch { block() }
    }
    private var collectJob: Job? = null

    private var repository: SettingsRepository? = null
    private var panelStateFlow: MutableStateFlow<PanelState>? = null
    private var onUpdatePosition: ((x: Int, y: Int) -> Unit)? = null
    private var bound = false

    // panel children
    private val panelRoot: View
    private val settingsRoot: View
    private val settingsButton: View
    private val foldButton: View
    private val statusPill: TextView
    private val statusMeta: TextView
    private val metricCount: TextView
    private val metricElapsed: TextView
    private val cellSpeed: ParamCellView
    private val cellInterval: ParamCellView
    private val cellDistance: ParamCellView
    private val directionCell: View
    private val directionValue: TextView
    private val forgetAppBtn: View
    private val perAppSwitch: MaterialSwitch
    private val perAppLabel: TextView
    private val toggleBtn: com.google.android.material.button.MaterialButton
    private val resetBtn: com.google.android.material.button.MaterialButton
    // bubble
    private val bubble: BubbleView

    private var settingsVisible = false

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
    private var applyingFromFlow = false
    private val perAppChangeListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        repository?.apply(SettingsIntent.PerAppToggled(checked))
    }
    /**
     * Reusable scratch array for [View.getLocationOnScreen], avoiding a per-DOWN `IntArray(2)`
     * allocation on the UI thread during dragging.
     */
    private val tmpLocation = IntArray(2)
    /**
     * Lazily-populated list of the *fixed* interactive children (everything except the
     * ParamCellView sliders, which are only interactive while expanded). Built once after
     * [init] resolves all findViewByIds, then read on every ACTION_DOWN without rebuilding.
     */
    private val fixedInteractive: List<View> by lazy {
        listOf(directionCell, toggleBtn, resetBtn, foldButton, settingsButton, perAppSwitch, forgetAppBtn)
    }
    /**
     * Named Runnable posted after a snap finishes so we can [removeCallbacks] it on detach.
     * An anonymous lambda could not be cancelled and would keep running (holding a reference to
     * this view) for ~270ms after the window is removed — a textbook overlay leak.
     */
    private val snapCompletionRunnable = Runnable {
        if (panelStateFlow?.value == PanelState.Snapping) {
            panelStateFlow?.value = PanelState.Collapsed
        }
    }

    private val density get() = resources.displayMetrics.density
    private fun panelWidthPx() = (OverlayGeometry.PANEL_WIDTH_DP * density).toInt()
    private fun collapsedWidthPx() = (OverlayGeometry.COLLAPSED_WIDTH_DP * density).toInt()

    init {
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_panel, this, true)
        // Set layout params for whole panel
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        )

        bubble = BubbleView(context)
        bubble.visibility = GONE
        addView(bubble, LayoutParams(collapsedWidthPx(), collapsedWidthPx()))

        panelRoot = findViewById(R.id.panel_root)
        settingsRoot = findViewById(R.id.settings_root)
        settingsButton = findViewById(R.id.settings_button)
        foldButton = findViewById(R.id.fold_button)
        statusPill = findViewById(R.id.status_pill)
        statusMeta = findViewById(R.id.status_meta)
        metricCount = findViewById(R.id.metric_count)
        metricElapsed = findViewById(R.id.metric_elapsed)
        cellSpeed = findViewById(R.id.param_cell_speed)
        cellInterval = findViewById(R.id.param_cell_interval)
        cellDistance = findViewById(R.id.param_cell_distance)
        directionCell = findViewById(R.id.direction_cell)
        directionValue = findViewById(R.id.direction_value)
        forgetAppBtn = findViewById(R.id.forget_app_btn)
        perAppSwitch = findViewById(R.id.perapp_switch)
        perAppLabel = findViewById(R.id.perapp_label)
        toggleBtn = findViewById(R.id.toggle_btn)
        resetBtn = findViewById(R.id.reset_btn)

        configureCells()

        foldButton.setOnClickListener { panelStateFlow?.value = PanelState.Collapsed }
        bubble.setOnClickListener { panelStateFlow?.value = PanelState.Expanded }
        settingsButton.setOnClickListener { toggleSettings() }
        toggleBtn.setOnClickListener { repository?.toggleRunning() }
        resetBtn.setOnClickListener {
            launchOnScope { repository?.resetStats() }
        }
        forgetAppBtn.setOnClickListener {
            launchOnScope {
                repository?.apply(SettingsIntent.ForgetActiveApp)
                toast("已忘记当前 App 配置")
            }
        }
        directionCell.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            val active = repo.activeSettings.value
            val next = if (active.direction == ScrollDirection.UP) ScrollDirection.DOWN else ScrollDirection.UP
            launchOnScope { repo.apply(SettingsIntent.SettingEdited { it.copy(direction = next) }) }
        }
        perAppSwitch.setOnCheckedChangeListener(perAppChangeListener)

        applyState(PanelState.Expanded)
        applyRunning(false)
    }

    private fun configureCells() {
        cellSpeed.configure(
            label = "速度",
            valueFrom = 150f, valueTo = 1500f,
            stepResolver = { ParamSteps.toSpeedLabel(it.toLong()) }
        )
        cellInterval.configure(
            label = "间隔",
            valueFrom = 500f, valueTo = 10000f,
            stepResolver = { ParamSteps.toIntervalLabel(it.toLong()) }
        )
        // distance resolver closes over screen height; re-bound on size change via applySettings.
        cellDistance.configure(
            label = "距离",
            valueFrom = 0.30f, valueTo = 0.95f,
            stepResolver = { ratio -> ParamSteps.toDistanceLabel(ratio, screenH()) }
        )
        cellSpeed.onUserChange = { v -> repository?.apply(SettingsIntent.SettingEdited { it.copy(duration = v.toLong()) }) }
        cellInterval.onUserChange = { v -> repository?.apply(SettingsIntent.SettingEdited { it.copy(interval = v.toLong()) }) }
        cellDistance.onUserChange = { v -> repository?.apply(SettingsIntent.SettingEdited { it.copy(distanceRatio = v) }) }
    }

    private fun screenH(): Int = repository?.screenHeight?.value ?: 1

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
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        if (bound) startCollecting()
    }

    override fun onDetachedFromWindow() {
        // Cancel the snap animation and any pending completion callback so the animator /
        // handler queue don't keep this view alive after WindowManager.removeView().
        snapAnimator?.cancel()
        snapAnimator = null
        removeCallbacks(snapCompletionRunnable)
        collectJob?.cancel()
        viewScope?.cancel()
        viewScope = null
        super.onDetachedFromWindow()
    }

    private fun startCollecting() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        val activeScope = viewScope ?: return
        collectJob = activeScope.launch {
            launch { flow.collect { applyState(it) } }
            launch { repo.activeSettings.collect { applySettings(it) } }
            launch { repo.isRunning.collect { applyRunning(it) } }
            launch {
                combine(repo.screenWidth, repo.screenHeight) { w, h -> w to h }
                    .collect { repositionToBounds(it.first, it.second) }
            }
            launch { repo.stats.collect { applyStats(it) } }
            launch { repo.perAppEnabled.collect { applyPerAppEnabled(it) } }
            launch { repo.currentPackage.collect { applyCurrentPackage(it) } }
        }
    }

    private fun applyState(state: PanelState) {
        val repo = repository ?: return
        val screenWidth = repo.screenWidth.value
        when (state) {
            PanelState.Collapsed -> {
                bubble.visibility = VISIBLE
                panelRoot.visibility = GONE
                currentX = OverlayGeometry.edgeX(isLeftEdge, screenWidth, collapsedWidthPx())
                onUpdatePosition?.invoke(currentX, currentY)
            }
            PanelState.Expanded -> {
                panelRoot.visibility = VISIBLE
                bubble.visibility = GONE
                currentX = OverlayGeometry.edgeX(isLeftEdge, screenWidth, panelWidthPx())
                onUpdatePosition?.invoke(currentX, currentY)
            }
            PanelState.Snapping -> {
                panelRoot.visibility = VISIBLE
                bubble.visibility = GONE
            }
        }
    }

    private fun applySettings(s: ScrollSettings) {
        applyingFromFlow = true
        cellSpeed.setValue(s.duration.toFloat(), fromFlow = true)
        cellInterval.setValue(s.interval.toFloat(), fromFlow = true)
        cellDistance.setValue(s.distanceRatio, fromFlow = true)
        applyingFromFlow = false
        directionValue.text = if (s.direction == ScrollDirection.DOWN) "↓ 向下" else "↑ 向上"
    }

    private fun applyRunning(running: Boolean) {
        // status pill: green running / amber paused
        statusPill.text = if (running) "● 运行中" else "● 已暂停"
        statusPill.setBackgroundResource(
            if (running) R.drawable.overlay_status_pill_running else R.drawable.overlay_status_pill_paused
        )
        statusPill.setTextColor(ResourcesCompat.getColor(resources, R.color.overlay_accent.takeIf { running } ?: R.color.overlay_warning, null))
        // main button: running = neutral outline ("暂停滑动"); paused = green filled ("开始滑动")
        toggleBtn.text = if (running) "⏸ 暂停滑动" else "▶ 开始滑动"
        if (running) {
            toggleBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.overlay_surface_2, null))
            toggleBtn.strokeColor = android.content.res.ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.overlay_border, null))
            toggleBtn.strokeWidth = density.toInt().coerceAtLeast(1) // 1dp outline
            toggleBtn.setTextColor(ResourcesCompat.getColor(resources, R.color.overlay_fg, null))
        } else {
            toggleBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.overlay_accent, null))
            toggleBtn.strokeWidth = 0
            toggleBtn.setTextColor(ResourcesCompat.getColor(resources, R.color.overlay_on_accent, null))
        }
    }

    private fun applyStats(stats: ScrollStats) {
        metricCount.text = stats.swipeCount.toString()
        val minutes = Math.round(stats.elapsedMs / 60000.0)
        metricElapsed.text = "约 $minutes 分钟"
    }

    private fun applyPerAppEnabled(enabled: Boolean) {
        perAppSwitch.setOnCheckedChangeListener(null)
        perAppSwitch.isChecked = enabled
        perAppSwitch.setOnCheckedChangeListener(perAppChangeListener)
        updatePerAppLabelVisibility()
    }

    private fun applyCurrentPackage(pkg: String?) {
        statusMeta.text = "前台 · ${pkg ?: "-"}"
        updatePerAppLabelVisibility()
    }

    private fun updatePerAppLabelVisibility() {
        val repo = repository ?: return
        val pkg = repo.currentPackage.value
        val enabled = repo.perAppEnabled.value
        perAppLabel.visibility = if (pkg != null && enabled) VISIBLE else GONE
        perAppLabel.text = if (pkg != null) "当前：$pkg" else ""
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        settingsRoot.visibility = if (settingsVisible) VISIBLE else GONE
        // 120ms crossfade
        val anim = AlphaAnimation(if (settingsVisible) 0f else 1f, if (settingsVisible) 1f else 0f)
        anim.duration = 120
        settingsRoot.startAnimation(anim)
        // if panel now taller and near bottom, re-clamp Y
        repositionToBounds(repository?.screenWidth?.value ?: 0, repository?.screenHeight?.value ?: 0)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun repositionToBounds(screenWidth: Int, screenHeight: Int) {
        val flow = panelStateFlow
        val isCollapsed = flow != null && flow.value == PanelState.Collapsed
        val widthPx = if (isCollapsed) collapsedWidthPx() else panelWidthPx()
        val panelH = panelRoot.height.takeIf { it > 0 }
            ?: (260f * density).toInt()
        val nx = if (!dragging) {
            OverlayGeometry.edgeX(isLeftEdge, screenWidth, widthPx)
        } else {
            OverlayGeometry.clamp(currentX, 0, (screenWidth - widthPx).coerceAtLeast(0))
        }
        // Constrain the RESTING Y for the current orientation: in portrait, pull it into the
        // corner-free safe band (curved-edge phones); in landscape, corners are fine so we just
        // keep it on-screen. Dragging is unconstrained; only this resting position — set on
        // size/config changes and not during a drag — is clamped.
        val ny = OverlayGeometry.clampRestingY(currentY, screenWidth, screenHeight, panelH)
        if (nx != currentX || ny != currentY) {
            currentX = nx
            currentY = ny
            onUpdatePosition?.invoke(nx, ny)
        }
    }

    private fun isTouchInsideView(ev: MotionEvent, view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        view.getLocationOnScreen(tmpLocation)
        return ev.rawX >= tmpLocation[0] && ev.rawX <= tmpLocation[0] + view.width &&
               ev.rawY >= tmpLocation[1] && ev.rawY <= tmpLocation[1] + view.height
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            snapAnimator?.cancel()
            downRawX = ev.rawX; downRawY = ev.rawY
            lastRawX = ev.rawX; lastRawY = ev.rawY
            dragging = false
            // Interactive children = fixed set (cached) + any currently-expanded slider.
            // Only the expanded sliders are dynamic, so we keep that tiny per-DOWN filter and
            // reuse the cached [fixedInteractive] list instead of allocating a fresh list each time.
            val expandedSlider = when {
                cellSpeed.isExpanded -> cellSpeed.slider
                cellInterval.isExpanded -> cellInterval.slider
                cellDistance.isExpanded -> cellDistance.slider
                else -> null
            }
            disallowIntercept = if (expandedSlider != null && isTouchInsideView(ev, expandedSlider)) {
                true
            } else {
                fixedInteractive.any { isTouchInsideView(ev, it) }
            }
        }
        if (disallowIntercept) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                    dragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disallowIntercept = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // Tap outside the panel → auto-hide to the bubble (gets the overlay out of the way
            // while reading). Tapping the bubble re-expands. Closes an open settings sub-panel
            // first so re-expand shows the main panel.
            if (settingsVisible) toggleSettings()
            val flow = panelStateFlow
            if (flow != null && flow.value == PanelState.Expanded) flow.value = PanelState.Collapsed
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
        val repo = repository ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                    dragging = true; lastRawX = ev.rawX; lastRawY = ev.rawY
                }
                if (dragging) {
                    val w = repo.screenWidth.value
                    val h = repo.screenHeight.value
                    val dx = (ev.rawX - lastRawX).toInt()
                    val dy = (ev.rawY - lastRawY).toInt()
                    val panelW = panelRoot.width.takeIf { it > 0 } ?: panelWidthPx()
                    val panelH = panelRoot.height.takeIf { it > 0 } ?: (260f * density).toInt()
                    currentX = OverlayGeometry.clamp(currentX + dx, 0, w - panelW)
                    currentY = OverlayGeometry.clamp(currentY + dy, 0, h - panelH)
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    onUpdatePosition?.invoke(currentX, currentY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disallowIntercept = false
                if (dragging) { dragging = false; performSnap() }
            }
        }
        return true
    }

    private fun performSnap() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        val screenWidth = repo.screenWidth.value
        val screenHeight = repo.screenHeight.value
        val panelWidthPx = panelRoot.width.takeIf { it > 0 } ?: panelWidthPx()
        val panelH = panelRoot.height.takeIf { it > 0 } ?: (260f * density).toInt()
        val target = OverlayGeometry.snapTarget(
            panelCenterX = currentX + panelWidthPx / 2,
            screenWidth = screenWidth,
            panelWidthPx = panelWidthPx
        )
        isLeftEdge = target.isLeftEdge
        // Pull the resting Y to a valid resting point for the current orientation: portrait →
        // corner-free safe band; landscape → corners allowed. A release in a portrait corner
        // animates back to a reachable middle point instead of stranding the bubble on bent glass.
        val targetY = OverlayGeometry.clampRestingY(currentY, screenWidth, screenHeight, panelH)
        flow.value = PanelState.Snapping
        // Animate both axes to the resting point: X to the chosen edge, Y into the safe band.
        val startX = currentX
        val startY = currentY
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                currentX = (startX + (target.x - startX) * t).toInt()
                currentY = (startY + (targetY - startY) * t).toInt()
                onUpdatePosition?.invoke(currentX, currentY)
            }
        }
        snapAnimator?.start()
        postDelayed(snapCompletionRunnable, 270)
    }
}
