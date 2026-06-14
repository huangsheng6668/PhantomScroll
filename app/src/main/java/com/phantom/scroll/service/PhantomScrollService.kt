package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.widget.Toast
import com.phantom.scroll.data.DataStoreProfileStore
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.ui.overlay.PanelState
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Main accessibility service coordinator for PhantomScroll.
 * Owns the [SettingsRepository] (single source of truth) and delegates behavior to
 * [FloatingWindowController], [ScrollOrchestrator], [ServiceEventReceiver].
 */
class PhantomScrollService : AccessibilityService() {

    private val TAG = "PhantomScrollService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository by lazy { SettingsRepository(DataStoreProfileStore(this), serviceScope) }

    val panelStateFlow = MutableStateFlow(PanelState.Expanded)

    private lateinit var floatingWindowController: FloatingWindowController
    private lateinit var scrollOrchestrator: ScrollOrchestrator
    private lateinit var eventReceiver: ServiceEventReceiver

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhantomLog.d(TAG, "Service connected.")
        Toast.makeText(this, "👻 PhantomScroll 自动翻页服务已连接", Toast.LENGTH_SHORT).show()

        val dm = resources.displayMetrics
        repository.setScreenWidth(dm.widthPixels)
        repository.setScreenHeight(dm.heightPixels)

        floatingWindowController = FloatingWindowController(this, repository, serviceScope, panelStateFlow)
        scrollOrchestrator = ScrollOrchestrator(this, repository, serviceScope)
        eventReceiver = ServiceEventReceiver(this, repository) { disableSelf() }

        floatingWindowController.start()
        scrollOrchestrator.start()
        eventReceiver.start()

        serviceScope.launch {
            repository.isRunning.collectLatest { isRunning ->
                NotificationHelper.showNotification(this@PhantomScrollService, isRunning)
            }
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Per-app detection lands in Phase 3. Phase 1 keeps this a no-op.
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        repository.setScreenWidth(dm.widthPixels)
        repository.setScreenHeight(dm.heightPixels)
        PhantomLog.d(TAG, "onConfigurationChanged: ${dm.widthPixels}x${dm.heightPixels}")
    }

    override fun onDestroy() {
        PhantomLog.d(TAG, "Service being destroyed.")
        repository.stopRunning()

        if (::floatingWindowController.isInitialized) floatingWindowController.stop()
        if (::scrollOrchestrator.isInitialized) scrollOrchestrator.stop()
        if (::eventReceiver.isInitialized) eventReceiver.stop()

        NotificationHelper.cancelNotification(this)
        serviceScope.cancel()
        super.onDestroy()
    }
}
