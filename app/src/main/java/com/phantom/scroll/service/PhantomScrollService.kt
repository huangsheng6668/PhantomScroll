package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.TouchInteractionController
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.Display
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.ContextCompat
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
     * Set at the top of [onDestroy]. Suppresses side effects (notification re-post) of the
     * final isRunning=false emission that teardown itself triggers. Volatile: read by the
     * serviceScope collector, written from onDestroy (both main thread, but cheap safety).
     */
    @Volatile
    private var tearingDown = false
    /**
     * 1×1 像素 `TYPE_ACCESSIBILITY_OVERLAY` 保活窗（参考 gkd / 李跳跳）。与交互式控制面板
     * [floatingWindowController] 完全独立：后者用 `TYPE_APPLICATION_OVERLAY` 负责拖拽/手势 UI，
     * 本窗只负责让进程拥有一个"可见窗口"以降低 oom_adj，规避 LowMemoryKiller。
     */
    private lateinit var keepAliveWindow: KeepAliveWindow

    /**
     * Packages that must NEVER become currentPackage: SystemUI (status bar / recents), the app's
     * own package, plus — resolved at first use — every installed home launcher and every
     * enabled input method.
     *
     * The IME part is critical: opening the keyboard fires window-state events carrying the
     * IME package while the reading app is still in the foreground; without this filter the
     * overlay would display the keyboard's package and per-app profiles would be written
     * for it. The launcher part prevents going home from registering a launcher profile.
     */
    private val systemPackageDenylist: Set<String> by lazy {
        buildSet {
            add("com.android.systemui")
            add(packageName) // own package
            addAll(resolveLauncherPackages())
            addAll(resolveImePackages())
        }
    }

    /** All packages that can serve as the home screen (usually one; several on some ROMs). */
    private fun resolveLauncherPackages(): Set<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    } catch (e: Exception) {
        PhantomLog.w(TAG, "Failed to resolve launcher packages: ${e.message}")
        emptySet()
    }

    /** All currently enabled input-method packages (system + third-party keyboards). */
    private fun resolveImePackages(): Set<String> = try {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.enabledInputMethodList.mapNotNull { it.packageName }.toSet()
    } catch (e: Exception) {
        PhantomLog.w(TAG, "Failed to resolve IME packages: ${e.message}")
        emptySet()
    }

    /**
     * Runtime-only preferences (deliberately separate from the DataStore-backed
     * [SettingsRepository]): holds the "resume after unlock" intent so the
     * screen-off → process-death → unlock path can still restore scrolling.
     */
    private val runtimePrefs by lazy { getSharedPreferences("phantom_runtime", Context.MODE_PRIVATE) }

    private val perAppDetector by lazy { PerAppDetector(ownPackage = packageName, denylist = systemPackageDenylist) }

    /**
     * Trailing-edge debounce job for rapid foreground bursts (A→B→A). When the detector
     * debounces a switch it remembers the latest candidate; this job applies it after the
     * window so the final foreground app is never lost. Cancelled by any subsequent Handle
     * or by a re-scheduled pending apply.
     */
    private var debounceJob: Job? = null

    override fun onServiceConnected() {
        instance = this
        super.onServiceConnected()
        PhantomLog.d(TAG, "Service connected.")
        Toast.makeText(this, "👻 PhantomScroll 自动翻页服务已连接", Toast.LENGTH_SHORT).show()

        updateScreenDimensions()

        floatingWindowController = FloatingWindowController(this, repository, serviceScope, panelStateFlow)
        scrollOrchestrator = ScrollOrchestrator(this, repository, serviceScope)
        eventReceiver = ServiceEventReceiver(
            context = this,
            repository = repository,
            scope = serviceScope,
            readResumeIntent = { runtimePrefs.getBoolean(KEY_RESUME_AFTER_UNLOCK, false) },
            writeResumeIntent = { enabled ->
                runtimePrefs.edit().putBoolean(KEY_RESUME_AFTER_UNLOCK, enabled).apply()
            },
            onStopService = { disableSelf() }
        )

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

        // Best-effort: observe real-user touch interaction (API 31+). Failure here must
        // never block the service's core features.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val controller = getTouchInteractionController(Display.DEFAULT_DISPLAY)
                controller.registerCallback(ContextCompat.getMainExecutor(this), touchCallback)
                touchController = controller
            } catch (e: Exception) {
                PhantomLog.e(TAG, "Failed to register touch interaction observer: ${e.message}", e)
            }
        }

        serviceScope.launch {
            repository.isRunning.collectLatest { isRunning ->
                // Teardown guard: onDestroy flips isRunning to false BEFORE this scope is
                // cancelled; without the guard that final emission would re-post the
                // resident notification just for cancelNotification() to remove it moments
                // later — one wasted notification build (and icon pass) per shutdown.
                if (!tearingDown) {
                    NotificationHelper.showNotification(this@PhantomScrollService, isRunning)
                }
            }
        }

        // The user may have pressed the notification's play/pause button while the
        // process was dead and the service was still reconnecting. Apply it now that
        // the repository exists.
        if (NotificationActionReceiver.consumePendingToggle()) {
            repository.toggleRunning()
            PhantomLog.d(TAG, "Applied pending notification toggle.")
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val event = event ?: return

        // Cheap int filter FIRST: Kotlin evaluates call arguments eagerly, so passing
        // event.packageName?.toString() below would run for EVERY delivered event even
        // though extract() keeps only TYPE_WINDOW_STATE_CHANGED. (The service config
        // subscribes to window-state changes only — content-changed events were dropped
        // from the subscription along with the smart-interval feature that consumed them.)
        // The exact `==` matches extract()'s own check.
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = PackageChangeExtractor.extract(
            event.eventType,
            event.packageName?.toString()
        ) ?: return

        val decision = perAppDetector.evaluate(
            eventPackage = pkg,
            currentPackage = repository.currentPackage.value,
            nowMs = System.currentTimeMillis()
        )
        when (decision) {
            is PerAppDecision.Handle -> {
                // A direct switch supersedes any scheduled trailing-edge apply.
                debounceJob?.cancel()
                repository.apply(SettingsIntent.PackageSwitched(decision.packageToSet))
                PhantomLog.d(TAG, "Per-app package switch -> ${decision.packageToSet}")
            }
            is PerAppDecision.Skip -> {
                // A switch was debounced (rapid A→B→A): (re)schedule a trailing-edge apply
                // of the LATEST candidate once the window elapses, so the final foreground
                // app is never lost. Noise events don't clear the candidate and only
                // re-extend the quiet window slightly.
                if (perAppDetector.pendingPackage() != null) {
                    debounceJob?.cancel()
                    debounceJob = serviceScope.launch {
                        delay(perAppDetector.debounceWindowMs + DEBOUNCE_APPLY_GRACE_MS)
                        val pending = perAppDetector.takePending(System.currentTimeMillis())
                        if (pending != null && pending != repository.currentPackage.value) {
                            repository.apply(SettingsIntent.PackageSwitched(pending))
                            PhantomLog.d(TAG, "Trailing-edge package apply -> $pending")
                        }
                    }
                }
            }
        }
    }

    /**
     * Observes the framework's touch-interaction state (API 31+) so the orchestrator can
     * yield to the user's own finger. `STATE_TOUCH_INTERACTING` is only reported for REAL
     * user touches — gestures injected via dispatchGesture carry synthetic flags and never
     * enter this state machine (AOSP AccessibilityInputFilter). On API < 31 no equivalent
     * public API exists; the callback simply never fires and the orchestrator's
     * cancellation grace window stays inert.
     */
    private var touchController: TouchInteractionController? = null
    private val touchCallback = object : TouchInteractionController.Callback {
        override fun onMotionEvent(event: MotionEvent) {
            // State changes below are sufficient to detect "user finger on screen".
        }

        override fun onStateChanged(state: Int) {
            if (!::scrollOrchestrator.isInitialized) return
            scrollOrchestrator.onUserTouch(state == TouchInteractionController.STATE_TOUCH_INTERACTING)
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
        tearingDown = true
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                touchController?.unregisterCallback(touchCallback)
            } catch (e: Exception) {
                PhantomLog.w(TAG, "Failed to unregister touch observer: ${e.message}")
            }
            touchController = null
        }
        if (::keepAliveWindow.isInitialized) keepAliveWindow.hide()
        KeepAliveService.stop(this)
        NotificationHelper.cancelNotification(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        /**
         * Extra quiet time beyond the detector's debounce window before a trailing-edge
         * pending apply fires; guards against scheduling/clock jitter at the window edge.
         */
        private const val DEBOUNCE_APPLY_GRACE_MS = 50L

        /** runtimePrefs key: scrolling was active when the screen went off. */
        private const val KEY_RESUME_AFTER_UNLOCK = "resume_after_unlock"

        @Volatile
        var instance: PhantomScrollService? = null
            private set
    }
}
