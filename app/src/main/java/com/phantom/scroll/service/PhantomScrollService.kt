package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.widget.Toast
import com.phantom.scroll.config.ScrollConfig
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.ui.overlay.PanelState
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Main accessibility service coordinator for PhantomScroll.
 * Delegates logic to specialized components (FloatingWindowController, ScrollOrchestrator, ServiceEventReceiver)
 * to keep the service code clean, robust, and modular.
 */
class PhantomScrollService : AccessibilityService() {

    private val TAG = "PhantomScrollService"

    // Scope for long-running service coroutines (UI and orchestrations)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Configuration and Panel State Flows
    val config by lazy { ScrollConfig(this, serviceScope) }
    val panelStateFlow = MutableStateFlow(PanelState.Expanded)

    // Sub-controllers
    private lateinit var floatingWindowController: FloatingWindowController
    private lateinit var scrollOrchestrator: ScrollOrchestrator
    private lateinit var eventReceiver: ServiceEventReceiver

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhantomLog.d(TAG, "Service connected.")

        Toast.makeText(this, "👻 PhantomScroll 自动翻页服务已连接", Toast.LENGTH_SHORT).show()

        // Initialize display dimensions
        val dm = resources.displayMetrics
        config.screenWidth.value = dm.widthPixels
        config.screenHeight.value = dm.heightPixels

        // Initialize sub-controllers
        floatingWindowController = FloatingWindowController(this, config, serviceScope, panelStateFlow)
        scrollOrchestrator = ScrollOrchestrator(this, config, serviceScope)
        eventReceiver = ServiceEventReceiver(this, config) {
            disableSelf()
        }

        // Start sub-controllers
        floatingWindowController.start()
        scrollOrchestrator.start()
        eventReceiver.start()

        // Observe isRunning to show/update system notification (normal notification, not FGS)
        serviceScope.launch {
            config.isRunning.collectLatest { isRunning ->
                NotificationHelper.showNotification(this@PhantomScrollService, isRunning)
            }
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // No events monitored; configured for window state changes as fallback in XML config
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        config.screenWidth.value = dm.widthPixels
        config.screenHeight.value = dm.heightPixels
        PhantomLog.d(TAG, "onConfigurationChanged: screenWidth = ${dm.widthPixels}, screenHeight = ${dm.heightPixels}")
    }

    override fun onDestroy() {
        PhantomLog.d(TAG, "Service being destroyed.")
        config.isRunning.value = false

        // Stop sub-controllers (guarantees safe cleanup)
        if (::floatingWindowController.isInitialized) {
            floatingWindowController.stop()
        }
        if (::scrollOrchestrator.isInitialized) {
            scrollOrchestrator.stop()
        }
        if (::eventReceiver.isInitialized) {
            eventReceiver.stop()
        }

        // Cancel notification
        NotificationHelper.cancelNotification(this)

        // Cancel all coroutines inside serviceScope
        serviceScope.cancel()

        super.onDestroy()
    }
}
