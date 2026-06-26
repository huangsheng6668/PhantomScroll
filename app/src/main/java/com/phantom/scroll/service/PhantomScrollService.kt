package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager
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
    /**
     * Catches otherwise-unhandled exceptions from long-lived collectors (notification refresh,
     * DataStore persistence, panelState flag updates). SupervisorJob already stops a failing
     * child from cancelling its siblings; this handler makes the failure observable (logged)
     * instead of letting the child die silently. Does not apply to dispatchGesture callbacks,
     * which run on their own binder thread outside this scope.
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        PhantomLog.e(TAG, "Uncaught coroutine exception in serviceScope", e)
    }
    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    val repository by lazy { SettingsRepository(DataStoreProfileStore(this), serviceScope) }

    val panelStateFlow = MutableStateFlow(PanelState.Expanded)

    private lateinit var floatingWindowController: FloatingWindowController
    private lateinit var scrollOrchestrator: ScrollOrchestrator
    private lateinit var eventReceiver: ServiceEventReceiver

    /**
     * Packages that must NEVER become currentPackage: SystemUI (status bar / recents), the app's
     * own package, and (extensible) Launchers / IMEs. Kept as a field so real-device tweaks are
     * localized. spec §3.4.
     */
    private val systemPackageDenylist: Set<String> by lazy {
        setOf(
            "com.android.systemui",
            packageName // own package
        )
    }

    private val perAppDetector by lazy { PerAppDetector(ownPackage = packageName, denylist = systemPackageDenylist) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        PhantomLog.d(TAG, "Service connected.")
        Toast.makeText(this, "👻 PhantomScroll 自动翻页服务已连接", Toast.LENGTH_SHORT).show()

        updateScreenDimensions()

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
        val pkg = event?.packageName?.toString() ?: return
        val decision = perAppDetector.evaluate(
            eventPackage = pkg,
            currentPackage = repository.currentPackage.value,
            perAppEnabled = repository.perAppEnabled.value,
            nowMs = System.currentTimeMillis()
        )
        if (decision is PerAppDecision.Handle) {
            repository.setCurrentPackage(decision.packageToSet)
            PhantomLog.d(TAG, "Per-app: currentPackage → ${decision.packageToSet}")
        }
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
    }

    private fun updateScreenDimensions() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            repository.setScreenWidth(bounds.width())
            repository.setScreenHeight(bounds.height())
            PhantomLog.d(TAG, "updateScreenDimensions (API 30+): ${bounds.width()}x${bounds.height()}")
        } else {
            val dm = resources.displayMetrics
            repository.setScreenWidth(dm.widthPixels)
            repository.setScreenHeight(dm.heightPixels)
            PhantomLog.d(TAG, "updateScreenDimensions: ${dm.widthPixels}x${dm.heightPixels}")
        }
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
