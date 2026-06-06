package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.phantom.scroll.config.ScrollConfig
import com.phantom.scroll.gesture.GestureEngine
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.ui.overlay.FloatingPanel
import com.phantom.scroll.ui.theme.OverlayTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

class PhantomScrollService : AccessibilityService() {

    private val TAG = "PhantomScrollService"

    // Core business state
    val config by lazy { ScrollConfig(this, serviceScope) }
    private val gestureEngine = GestureEngine()

    // Service-level Coroutine Scope (Main.immediate for UI orchestration)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // WindowManager overlay properties
    private var floatingView: ComposeView? = null
    private lateinit var windowManager: WindowManager
    private lateinit var overlayLifecycleOwner: OverlayLifecycleOwner
    private var floatingParams: WindowManager.LayoutParams? = null
    private var isFloatingWindowAdded = false

    // Screen state receiver: pauses scrolling on screen off, resumes on user unlock (user present)
    private var wasRunningBeforeScreenOff = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen off → pausing autoscroll.")
                    if (config.isRunning.value) {
                        wasRunningBeforeScreenOff = true
                        config.isRunning.value = false
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "User present (unlocked) → restoring autoscroll state.")
                    if (wasRunningBeforeScreenOff) {
                        wasRunningBeforeScreenOff = false
                        config.isRunning.value = true
                    }
                }
            }
        }
    }

    // Notification action receiver: handles toggle/stop broadcasts from notification buttons
    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NotificationHelper.ACTION_TOGGLE -> {
                    config.isRunning.value = !config.isRunning.value
                    Log.d(TAG, "Notification toggle → isRunning: ${config.isRunning.value}")
                }
                NotificationHelper.ACTION_STOP -> {
                    Log.d(TAG, "Notification stop → disabling service.")
                    config.isRunning.value = false
                    disableSelf()
                }
            }
        }
    }

    // Gesture failure counter (3 consecutive failures → auto-pause)
    private var consecutiveFailures = 0

    // ========================= Lifecycle =========================

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected.")
        instance = this

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Show diagnostic Toast to verify service connection
        android.widget.Toast.makeText(this, "👻 PhantomScroll 自动翻页服务已连接", android.widget.Toast.LENGTH_SHORT).show()

        // Setup notification channel & start foreground
        NotificationHelper.createChannel(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.buildNotification(this, false),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.buildNotification(this, false)
            )
        }

        // Observe isRunning changes → update notification
        serviceScope.launch {
            config.isRunning.collectLatest { isRunning ->
                val notification = NotificationHelper.buildNotification(
                    this@PhantomScrollService, isRunning
                )
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NotificationHelper.NOTIFICATION_ID, notification)
            }
        }

        // Setup Compose overlay floating window if permission is granted, otherwise poll
        if (Settings.canDrawOverlays(this)) {
            setupFloatingWindow()
        } else {
            Log.d(TAG, "Overlay permission not granted yet. Starting permission polling.")
            startOverlayPermissionPolling()
        }

        // Register screen off / unlock receiver
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, screenFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, screenFilter)
        }

        // Register notification action receiver
        val notificationFilter = IntentFilter().apply {
            addAction(NotificationHelper.ACTION_TOGGLE)
            addAction(NotificationHelper.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationActionReceiver, notificationFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationActionReceiver, notificationFilter)
        }

        // Start the autoscroll loop
        startScrollingLoop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed — we only use this service for gesture injection
    }

    override fun onInterrupt() {
        // Not needed
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        config.screenWidth.value = dm.widthPixels
        config.screenHeight.value = dm.heightPixels
        Log.d(TAG, "onConfigurationChanged: screenWidth = ${dm.widthPixels}, screenHeight = ${dm.heightPixels}")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service being destroyed.")
        config.isRunning.value = false

        // Safely unregister broadcast receivers
        safeUnregister(screenReceiver)
        safeUnregister(notificationActionReceiver)

        // Safely remove overlay view and lifecycle cleanup
        floatingView?.let { view ->
            try {
                if (isFloatingWindowAdded) {
                    overlayLifecycleOwner.onPause()
                    windowManager.removeView(view)
                    overlayLifecycleOwner.onDestroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}")
            }
        }
        floatingView = null
        isFloatingWindowAdded = false

        // Cancel all coroutines
        serviceScope.cancel()

        instance = null
        super.onDestroy()
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered: ${e.message}")
        }
    }

    // ========================= Floating Window =========================

    private fun startOverlayPermissionPolling() {
        serviceScope.launch {
            while (!isFloatingWindowAdded) {
                delay(1000)
                if (Settings.canDrawOverlays(this@PhantomScrollService)) {
                    Log.d(TAG, "Overlay permission granted! Adding floating window now.")
                    withContext(Dispatchers.Main.immediate) {
                        android.widget.Toast.makeText(this@PhantomScrollService, "✓ 检测到悬浮窗权限已授予，正在启动悬浮窗...", android.widget.Toast.LENGTH_SHORT).show()
                        setupFloatingWindow()
                    }
                    break
                }
            }
        }
    }

    private fun setupFloatingWindow() {
        if (isFloatingWindowAdded) return
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted. Skipping setupFloatingWindow.")
            android.widget.Toast.makeText(this, "⚠ 悬浮窗权限未授予，请先在 App 中授权", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        try {
            overlayLifecycleOwner = OverlayLifecycleOwner()
            overlayLifecycleOwner.onCreate()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 200
            }
            floatingParams = params

            floatingView = ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(overlayLifecycleOwner)
                setViewTreeViewModelStoreOwner(overlayLifecycleOwner)
                setViewTreeSavedStateRegistryOwner(overlayLifecycleOwner)

                setContent {
                    OverlayTheme {
                        FloatingPanel(
                            config = config,
                            onUpdatePosition = { x, y -> updateFloatingPosition(x, y) }
                        )
                    }
                }
            }

            windowManager.addView(floatingView, params)
            // CRITICAL: onResume must be called AFTER addView, otherwise Compose gestures won't work
            overlayLifecycleOwner.onResume()
            isFloatingWindowAdded = true
            Log.d(TAG, "Floating window added successfully.")
            android.widget.Toast.makeText(this, "✓ 自动滑动悬浮窗已就绪！", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window: ${e.message}", e)
            android.widget.Toast.makeText(this, "❌ 悬浮窗显示失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
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
            Log.w(TAG, "Failed to update view layout: ${e.message}")
        }
    }

    // ========================= Scroll Loop =========================

    private fun startScrollingLoop() {
        serviceScope.launch {
            while (isActive) {
                // Suspend until isRunning becomes true
                config.isRunning.first { it }

                // Double-check after resume
                if (!config.isRunning.value) continue

                val dm = resources.displayMetrics
                val screenWidth = dm.widthPixels
                val screenHeight = dm.heightPixels
                val snap = config.snapshot()

                // Generate path on Dispatchers.Default (CPU-intensive)
                val (path, finalDuration) = gestureEngine.generateGesturePath(
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    distanceRatio = snap.distanceRatio,
                    durationMs = snap.duration
                )

                // Dispatch gesture and WAIT for completion via suspendCancellableCoroutine
                val gestureSucceeded = withContext(Dispatchers.Main.immediate) {
                    if (!isActive || !config.isRunning.value) return@withContext false

                    suspendCancellableCoroutine { cont ->
                        val stroke = GestureDescription.StrokeDescription(path, 0L, finalDuration)
                        val gesture = GestureDescription.Builder().addStroke(stroke).build()

                        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                consecutiveFailures = 0
                                if (cont.isActive) cont.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                consecutiveFailures++
                                Log.w(TAG, "Gesture cancelled. Failures: $consecutiveFailures")
                                if (consecutiveFailures >= 3) {
                                    Log.e(TAG, "3 consecutive failures → auto-pausing.")
                                    config.isRunning.value = false
                                    consecutiveFailures = 0
                                }
                                if (cont.isActive) cont.resume(false)
                            }
                        }, null)

                        // If dispatchGesture itself returned false (e.g. service not ready)
                        if (!dispatched) {
                            Log.w(TAG, "dispatchGesture returned false.")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }

                if (!gestureSucceeded) continue

                // Wait interval with Bio-Noise (non-blocking coroutine delay)
                val noiseInterval = gestureEngine.addBioNoise(snap.interval.toFloat(), 0.08f)
                    .toLong().coerceIn(400, 12000)
                delay(noiseInterval)
            }
        }
    }

    companion object {
        var instance: PhantomScrollService? = null
            private set
    }
}
