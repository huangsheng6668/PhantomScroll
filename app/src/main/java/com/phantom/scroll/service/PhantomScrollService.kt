package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager
import android.widget.Toast
import com.phantom.scroll.data.DataStoreProfileStore
import com.phantom.scroll.data.SettingsIntent
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
     * 1×1 像素 `TYPE_ACCESSIBILITY_OVERLAY` 保活窗（参考 gkd / 李跳跳）。与交互式控制面板
     * [floatingWindowController] 完全独立：后者用 `TYPE_APPLICATION_OVERLAY` 负责拖拽/手势 UI，
     * 本窗只负责让进程拥有一个"可见窗口"以降低 oom_adj，规避 LowMemoryKiller。
     */
    private lateinit var keepAliveWindow: KeepAliveWindow

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
        instance = this
        super.onServiceConnected()
        PhantomLog.d(TAG, "Service connected.")
        Toast.makeText(this, "👻 PhantomScroll 自动翻页服务已连接", Toast.LENGTH_SHORT).show()

        updateScreenDimensions()

        floatingWindowController = FloatingWindowController(this, repository, serviceScope, panelStateFlow)
        scrollOrchestrator = ScrollOrchestrator(this, repository, serviceScope)
        eventReceiver = ServiceEventReceiver(this, repository) { disableSelf() }

        // Keep-alive overlay window: a 1px TYPE_ACCESSIBILITY_OVERLAY window (mirrors gkd's
        // useAliveOverlayView). Best-effort, never blocks other features on failure.
        keepAliveWindow = KeepAliveWindow(this)
        keepAliveWindow.show()

        // Start the independent foreground KeepAliveService (mirrors gkd's StatusService). Doing this
        // from onServiceConnected is the key: while the AccessibilityService is connected the process
        // counts as having a foreground component, so startForegroundService() here takes the
        // foreground-start exemption path instead of throwing ForegroundServiceStartNotAllowedException.
        // That resident notification is what keeps the process at foreground priority so it survives
        // memory pressure / OEM cleanup, and lets the user see the service is alive.
        KeepAliveService.start(this)

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
        val event = event ?: return
        // Zero-overhead early-out when per-app is off (spec §3.4).
        if (!repository.perAppEnabled.value) return

        val pkg = PackageChangeExtractor.extract(
            event.eventType,
            event.packageName?.toString(),
            repository.currentPackage.value
        ) ?: return

        val decision = perAppDetector.evaluate(
            eventPackage = pkg,
            currentPackage = repository.currentPackage.value,
            nowMs = System.currentTimeMillis()
        )
        if (decision is PerAppDecision.Handle) {
            repository.apply(SettingsIntent.PackageSwitched(decision.packageToSet))
            PhantomLog.d(TAG, "Per-app package switch -> ${decision.packageToSet}")
        }
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenDimensions()
        // Guard against the system occasionally tearing down the overlay window on rotation etc.
        if (::keepAliveWindow.isInitialized) keepAliveWindow.ensureShown()
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
        instance = null
        PhantomLog.d(TAG, "Service being destroyed. Flushing settings...")
        repository.isRunning.value = false

        // Synchronous main-thread teardown with bounded IO flush (max 1.5s timeout).
        // removeView()/super.onDestroy() MUST run on the main thread; running them inside a
        // background IO coroutine causes CalledFromWrongThreadException and lifecycle violations.
        // runBlocking(Dispatchers.IO) confines only the disk flush to IO with a hard timeout,
        // then teardown resumes synchronously on the main thread.
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(1500L) { repository.flush() }
            }
        } catch (e: Exception) {
            PhantomLog.e(TAG, "Failed to flush settings on destroy: ${e.message}")
        }

        if (::floatingWindowController.isInitialized) floatingWindowController.stop()
        if (::scrollOrchestrator.isInitialized) scrollOrchestrator.stop()
        if (::eventReceiver.isInitialized) eventReceiver.stop()
        if (::keepAliveWindow.isInitialized) keepAliveWindow.hide()
        KeepAliveService.stop(this)
        NotificationHelper.cancelNotification(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: PhantomScrollService? = null
            private set
    }
}
