package com.phantom.scroll.service

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import com.phantom.scroll.R
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.ui.overlay.FloatingOverlayView
import com.phantom.scroll.ui.overlay.PanelState
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Manages the WindowManager-based global floating overlay using the NATIVE [FloatingOverlayView].
 * Handles permission polling, view add/remove, position + flag updates. No Compose.
 */
class FloatingWindowController(
    private val context: Context,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope,
    private val panelStateFlow: MutableStateFlow<PanelState>
) {
    private val TAG = "FloatingWindowController"

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var floatingView: FloatingOverlayView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var isFloatingWindowAdded = false
    private var permissionPollingJob: Job? = null

    init {
        scope.launch {
            panelStateFlow.collectLatest { updateLayoutParamsForState(it) }
        }
    }

    fun start() {
        if (Settings.canDrawOverlays(context)) {
            setupFloatingWindow()
        } else {
            PhantomLog.d(TAG, "Overlay permission not granted. Starting polling.")
            startOverlayPermissionPolling()
        }
    }

    fun stop() {
        permissionPollingJob?.cancel()
        floatingView?.let { view ->
            try {
                if (isFloatingWindowAdded) windowManager.removeView(view)
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
                    PhantomLog.d(TAG, "Overlay permission granted; adding window.")
                    withContext(Dispatchers.Main.immediate) { setupFloatingWindow() }
                    break
                }
            }
            if (attempts >= 300) PhantomLog.w(TAG, "Overlay permission polling timed out.")
        }
    }

    private fun setupFloatingWindow() {
        if (isFloatingWindowAdded) return
        if (!Settings.canDrawOverlays(context)) {
            PhantomLog.w(TAG, "Skipping setup: overlay permission not granted.")
            Toast.makeText(context, "⚠ 悬浮窗权限未授予，请先在 App 中授权", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 200
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            floatingParams = params

            // Material3 themed context so the overlay's Slider inflates correctly.
            val themedContext = ContextThemeWrapper(context, R.style.Theme_PhantomScroll_Overlay)
            val view = FloatingOverlayView(themedContext)
            view.bind(
                repository = repository,
                panelStateFlow = panelStateFlow,
                initialX = params.x,
                initialY = params.y
            ) { x, y -> updateFloatingPosition(x, y) }
            floatingView = view

            windowManager.addView(view, params)
            isFloatingWindowAdded = true
            PhantomLog.d(TAG, "Native floating window added.")
        } catch (e: Exception) {
            PhantomLog.e(TAG, "Failed to add floating window: ${e.message}", e)
            Toast.makeText(context, "❌ 悬浮窗显示失败: ${e.message}", Toast.LENGTH_LONG).show()
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
        var targetFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (state == PanelState.Expanded) {
            targetFlags = targetFlags or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        }
        if (params.flags != targetFlags) {
            params.flags = targetFlags
            try {
                windowManager.updateViewLayout(view, params)
                PhantomLog.d(TAG, "Updated overlay flags for state $state")
            } catch (e: Exception) {
                PhantomLog.w(TAG, "Failed to update flags: ${e.message}")
            }
        }
    }
}
