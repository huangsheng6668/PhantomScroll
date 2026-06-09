package com.phantom.scroll.service

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.phantom.scroll.config.ScrollConfig
import com.phantom.scroll.ui.overlay.FloatingPanel
import com.phantom.scroll.ui.overlay.PanelState
import com.phantom.scroll.ui.theme.OverlayTheme
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Controller class that manages the WindowManager-based global floating window overlay.
 * Handles Compose integration, dynamic flag updates, drag boundaries, and lifecycle owners.
 */
class FloatingWindowController(
    private val context: Context,
    private val config: ScrollConfig,
    private val scope: CoroutineScope,
    private val panelStateFlow: MutableStateFlow<PanelState>
) {
    private val TAG = "FloatingWindowController"

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var isFloatingWindowAdded = false
    private var permissionPollingJob: Job? = null

    init {
        // Observe panelState changes → update WindowManager flags dynamically
        scope.launch {
            panelStateFlow.collectLatest { state ->
                updateLayoutParamsForState(state)
            }
        }
    }

    /**
     * Attempts to show the floating overlay. If permission is not granted, launches
     * a periodic check polling up to 300 attempts (5 minutes).
     */
    fun start() {
        if (Settings.canDrawOverlays(context)) {
            setupFloatingWindow()
        } else {
            PhantomLog.d(TAG, "Overlay permission not granted yet. Starting permission polling.")
            startOverlayPermissionPolling()
        }
    }

    /**
     * Safely tears down the overlay view and cancels any running permission polling checks.
     */
    fun stop() {
        permissionPollingJob?.cancel()
        floatingView?.let { view ->
            try {
                if (isFloatingWindowAdded) {
                    overlayLifecycleOwner?.onPause()
                    windowManager.removeView(view)
                    overlayLifecycleOwner?.onDestroy()
                }
            } catch (e: Exception) {
                PhantomLog.e(TAG, "Error removing overlay: ${e.message}", e)
            }
        }
        floatingView = null
        isFloatingWindowAdded = false
    }

    private fun startOverlayPermissionPolling() {
        permissionPollingJob = scope.launch {
            var attempts = 0
            while (!isFloatingWindowAdded && attempts < 300) {
                delay(1000)
                attempts++
                if (Settings.canDrawOverlays(context)) {
                    PhantomLog.d(TAG, "Overlay permission granted! Adding floating window now.")
                    withContext(Dispatchers.Main.immediate) {
                        android.widget.Toast.makeText(context, "✓ 检测到悬浮窗权限已授予，正在启动悬浮窗...", android.widget.Toast.LENGTH_SHORT).show()
                        setupFloatingWindow()
                    }
                    break
                }
            }
            if (attempts >= 300) {
                PhantomLog.w(TAG, "Overlay permission polling timed out after 5 minutes.")
            }
        }
    }

    private fun setupFloatingWindow() {
        if (isFloatingWindowAdded) return
        if (!Settings.canDrawOverlays(context)) {
            PhantomLog.w(TAG, "Overlay permission not granted. Skipping setupFloatingWindow.")
            android.widget.Toast.makeText(context, "⚠ 悬浮窗权限未授予，请先在 App 中授权", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            val lifecycleOwner = OverlayLifecycleOwner()
            overlayLifecycleOwner = lifecycleOwner
            lifecycleOwner.onCreate()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 200
            }
            floatingParams = params

            floatingView = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycleOwner)
                setViewTreeViewModelStoreOwner(lifecycleOwner)
                setViewTreeSavedStateRegistryOwner(lifecycleOwner)

                setContent {
                    val pState by panelStateFlow.collectAsState()
                    OverlayTheme {
                        FloatingPanel(
                            config = config,
                            panelState = pState,
                            onPanelStateChange = { newState ->
                                panelStateFlow.value = newState
                            },
                            onUpdatePosition = { x, y -> updateFloatingPosition(x, y) }
                        )
                    }
                }

                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        PhantomLog.d(TAG, "Outside touch detected. Collapsing panel.")
                        if (panelStateFlow.value == PanelState.Expanded) {
                            panelStateFlow.value = PanelState.Collapsed
                        }
                        true
                    } else {
                        false
                    }
                }
            }

            windowManager.addView(floatingView, params)
            // CRITICAL: onResume must be called AFTER addView, otherwise Compose gestures won't work
            lifecycleOwner.onResume()
            isFloatingWindowAdded = true
            PhantomLog.d(TAG, "Floating window added successfully.")
            android.widget.Toast.makeText(context, "✓ 自动滑动悬浮窗已就绪！", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            PhantomLog.e(TAG, "Failed to add floating window: ${e.message}", e)
            android.widget.Toast.makeText(context, "❌ 悬浮窗显示失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun updateFloatingPosition(newX: Int, newY: Int) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        params.x = newX
        params.y = newY
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            PhantomLog.w(TAG, "Failed to update view layout: ${e.message}")
        }
    }

    private fun updateLayoutParamsForState(state: PanelState) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        val targetFlags = if (state == PanelState.Expanded) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            try {
                windowManager.updateViewLayout(view, params)
                PhantomLog.d(TAG, "Updated layout params flags to $targetFlags for state $state")
            } catch (e: Exception) {
                PhantomLog.w(TAG, "Failed to update layout params flags: ${e.message}")
            }
        }
    }
}
