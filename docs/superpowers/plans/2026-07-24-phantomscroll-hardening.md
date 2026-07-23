# PhantomScroll 健壮性与可测试性加固 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过引入无状态 `SettingsReducer`、收敛三路持久化为单一 debounce collector、补 `kotlinx-coroutines-test` 并发回归测试，系统性地根治反复出现的 per-app race condition，并清理审计到的全部技术债。

**Architecture:** `SettingsRepository` 重构为三层：纯内存 StateHolder + 无状态 `SettingsReducer`（per-app 一致性规则唯一归属）+ 单一 `debounce(500ms)` 持久化 collector。所有状态变更经 `apply(SettingsIntent)` 单一入口。Service 事件过滤抽为纯函数 `PackageChangeExtractor`。`onDestroy` 的 `runBlocking` 改为 IO 协程非阻塞 await flush。

**Tech Stack:** Kotlin + Coroutines/Flow + Preferences DataStore + JUnit4 + `kotlinx-coroutines-test`（`StandardTestDispatcher`/`runTest`/`advanceUntilIdle`，已在 `testImplementation`）。

## Global Constraints

- **不引 Robolectric**：Overlay/gesture/WindowManager 真实硬件交互不 mock，靠手工对齐。
- **DataStore key 不变**：`global.*` / `profile.<pkg>.*` / `perapp.enabled` / `stats.*` key 完全保留，零数据迁移风险。
- **持久化单一入口**：删除 periodic saver 与所有即时 `scope.launch` 落盘；只保留 `debounce(500ms)` collector + `flush()`。
- **状态变更单一入口**：所有对持久化 StateFlow 的写经 `apply(SettingsIntent)` → `SettingsReducer` → `applyDelta`。`isRunning`/屏幕尺寸是非持久化字段，走独立轻量入口。
- **行为变化**：切 App 不再重置 global（`setCurrentPackage:237` 删除）。distanceRatio 屏幕适配仅首次加载应用。
- **日志门控**：禁止 `android.util.Log` 直调，统一走 `com.phantom.scroll.util.PhantomLog`。
- **包名前缀**：`com.phantom.scroll`，data 层在 `data/`，service 层在 `service/`。
- **测试目录**：单测在 `app/src/test/java/com/phantom/scroll/...`。
- **commit message 风格**：`type(scope): 描述`（参考 git log，中英皆可）。

---

## File Structure

| 文件 | 责任 | 操作 |
|------|------|------|
| `data/SettingsIntent.kt` | sealed interface：状态变更意图的唯一表达 | 新建 |
| `data/SettingsReducer.kt` | 无状态纯逻辑：`(Snapshot, Intent) → Delta` + `reconcileInitial` | 新建 |
| `data/SettingsRepository.kt` | 三层重构：StateHolder + `apply()` 单一入口 + 单一 debounce collector + 非阻塞 flush + isRunning 收敛 + 分层错误处理 | 重写 |
| `service/PackageChangeExtractor.kt` | 纯逻辑：无障碍事件类型过滤 + 包名提取 + 高频跳过 | 新建 |
| `service/PhantomScrollService.kt` | `onAccessibilityEvent` 瘦身用 extractor + perAppEnabled 早退；`onDestroy` 非阻塞 flush | 修改 |
| `service/ScreenStateCoordinator.kt` | 适配 isRunning 新 API（构造参数类型不变） | 修改 |
| `service/ScrollOrchestrator.kt` | `stopRunning()` → `isRunning = false` | 修改 |
| `service/NotificationActionReceiver.kt` | `toggleRunning()`/`stopRunning()` 适配 | 修改 |
| `ui/overlay/FloatingOverlayView.kt` | 删 6 处 `android.util.Log.e`；`toggleRunning()` 适配 | 修改 |
| 测试：`data/SettingsReducerTest.kt` | reducer 纯逻辑全分支 | 新建 |
| 测试：`service/PackageChangeExtractorTest.kt` | 事件过滤边界 | 新建 |
| 测试：`data/SettingsRepositoryConcurrencyTest.kt` | 7 个并发回归用例 | 新建 |
| 测试：`data/SettingsRepositoryTest.kt` | 适配 `apply()` 入口 + 改写"切 App 重置 global"断言为相反 | 修改 |

---

## Task 1: SettingsIntent —— 变更意图类型

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/data/SettingsIntent.kt`

**Interfaces:**
- Produces: sealed interface `SettingsIntent` 及其子类型，供 Task 2 的 reducer 与 Task 4 的 repository `apply()` 消费。

- [ ] **Step 1: 创建意图 sealed interface**

```kotlin
package com.phantom.scroll.data

/**
 * The single shape of every state change that flows through [SettingsRepository.apply].
 * Keeping all mutations behind this sealed type makes the per-app consistency rules
 * exhaustively matchable in [SettingsReducer] (a `when` over this type) and lets
 * concurrency tests replay any mutation deterministically.
 *
 * Non-persisted runtime state (isRunning, screen size) does NOT go through intents —
 * those are written directly to their StateFlows since they carry no cross-field rules.
 */
sealed interface SettingsIntent {
    /** Foreground app switched (from PerAppDetector decision). */
    data class PackageSwitched(val pkg: String?) : SettingsIntent
    /** User toggled the per-app switch. */
    data class PerAppToggled(val enabled: Boolean) : SettingsIntent
    /** User edited a parameter (Slider / direction) — writes to the active target. */
    data class SettingEdited(val transform: (ScrollSettings) -> ScrollSettings) : SettingsIntent
    /** Apply a preset's settings to the global defaults. */
    data class PresetApplied(val settings: ScrollSettings) : SettingsIntent
    /** Forget the current app's profile (falls back to global). */
    data object ForgetActiveApp : SettingsIntent
}
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin -q 2>&1 | tail -5`
Expected: 编译通过（无错误；可能有 `sealed interface ... is never used` 之类的 info，忽略）。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/SettingsIntent.kt
git commit -m "feat(data): add SettingsIntent sealed type as the single mutation entry"
```

---

## Task 2: SettingsReducer —— 无状态纯逻辑

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/data/SettingsReducer.kt`
- Test: `app/src/test/java/com/phantom/scroll/data/SettingsReducerTest.kt`

**Interfaces:**
- Consumes: `SettingsIntent` (Task 1), `ScrollSettings`/`AppProfile`/`ScrollDirection` (existing in `data/`).
- Produces: `object SettingsReducer` with `reduce(state: SettingsSnapshot, intent: SettingsIntent): SettingsDelta` and `reconcileInitial(loaded: LoadedState, screenHeight: Int): SettingsDelta`. Also defines `SettingsSnapshot`, `SettingsDelta`, `LoadedState` data classes.

- [ ] **Step 1: 写失败测试 —— PackageSwitched 不碰 global（核心行为变化）**

Create `app/src/test/java/com/phantom/scroll/data/SettingsReducerTest.kt`:

```kotlin
package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsReducerTest {

    private fun snapshot(
        global: ScrollSettings = ScrollSettings.DEFAULT,
        profiles: Map<String, AppProfile> = emptyMap(),
        perAppEnabled: Boolean = false,
        currentPackage: String? = null
    ) = SettingsSnapshot(global, profiles, perAppEnabled, currentPackage)

    private val customGlobal = ScrollSettings(duration = 999L, interval = 9999L, distanceRatio = 0.99f, direction = ScrollDirection.UP)

    @Test
    fun packageSwitched_with_profile_enables_perApp() {
        val pkg = "com.example.novel"
        val profile = AppProfile(pkg, ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f))
        val delta = SettingsReducer.reduce(
            snapshot(profiles = mapOf(pkg to profile), currentPackage = null),
            SettingsIntent.PackageSwitched(pkg)
        )
        assertEquals(pkg, delta.currentPackage)
        assertTrue(delta.perAppEnabled!!)
        // global untouched — the key behavior change
        assertNull(delta.global)
    }

    @Test
    fun packageSwitched_without_profile_disables_perApp_and_does_not_touch_global() {
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = null),
            SettingsIntent.PackageSwitched("com.unknown.app")
        )
        assertEquals("com.unknown.app", delta.currentPackage)
        assertFalse(delta.perAppEnabled!!)
        assertNull(delta.global) // does NOT reset global
    }

    @Test
    fun packageSwitched_null_pkg_disables_perApp() {
        val delta = SettingsReducer.reduce(
            snapshot(perAppEnabled = true, currentPackage = "com.a"),
            SettingsIntent.PackageSwitched(null)
        )
        assertNull(delta.currentPackage)
        assertFalse(delta.perAppEnabled!!)
    }

    @Test
    fun perAppToggled_true_clones_global_to_current_pkg_profile() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, currentPackage = pkg),
            SettingsIntent.PerAppToggled(true)
        )
        assertTrue(delta.perAppEnabled!!)
        assertEquals(AppProfile(pkg, customGlobal), delta.upsertedProfile)
    }

    @Test
    fun perAppToggled_false_deletes_current_pkg_profile() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = pkg),
            SettingsIntent.PerAppToggled(false)
        )
        assertFalse(delta.perAppEnabled!!)
        assertEquals(pkg, delta.deletedPackage)
    }

    @Test
    fun settingEdited_writes_profile_when_perApp_on_and_pkg_known() {
        val pkg = "com.example.novel"
        val transformed = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        val delta = SettingsReducer.reduce(
            snapshot(global = customGlobal, perAppEnabled = true, currentPackage = pkg),
            SettingsIntent.SettingEdited { transformed }
        )
        assertEquals(AppProfile(pkg, transformed), delta.upsertedProfile)
        assertNull(delta.global)
    }

    @Test
    fun settingEdited_writes_global_when_perApp_off() {
        val transformed = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        val delta = SettingsReducer.reduce(
            snapshot(perAppEnabled = false),
            SettingsIntent.SettingEdited { transformed }
        )
        assertEquals(transformed, delta.global)
        assertNull(delta.upsertedProfile)
    }

    @Test
    fun presetApplied_writes_global() {
        val preset = ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f, direction = ScrollDirection.UP)
        val delta = SettingsReducer.reduce(snapshot(), SettingsIntent.PresetApplied(preset))
        assertEquals(preset, delta.global)
    }

    @Test
    fun forgetActiveApp_deletes_profile_and_disables_perApp() {
        val pkg = "com.example.novel"
        val delta = SettingsReducer.reduce(
            snapshot(currentPackage = pkg, perAppEnabled = true),
            SettingsIntent.ForgetActiveApp
        )
        assertEquals(pkg, delta.deletedPackage)
        assertFalse(delta.perAppEnabled!!)
    }

    @Test
    fun reconcileInitial_applies_screen_adapted_distanceRatio_only_at_default() {
        // loaded global has DEFAULT distanceRatio (0.75) → screen-adapted (1500/h clamped)
        val loaded = LoadedState(
            global = ScrollSettings.DEFAULT,
            profiles = emptyMap(),
            perAppEnabled = false,
            stats = ScrollStats.ZERO
        )
        val delta = SettingsReducer.reconcileInitial(loaded, screenHeight = 2000)
        assertEquals(0.75f, delta.global!!.distanceRatio, 0.0001f) // 1500/2000 = 0.75
        assertEquals(2000L, delta.global!!.duration) // duration stays default
    }

    @Test
    fun reconcileInitial_keeps_user_distanceRatio_if_not_default() {
        val userSettings = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        val loaded = LoadedState(userSettings, emptyMap(), false, ScrollStats.ZERO)
        val delta = SettingsReducer.reconcileInitial(loaded, screenHeight = 2000)
        assertEquals(0.6f, delta.global!!.distanceRatio, 0.0001f) // user value preserved
    }
}
```

- [ ] **Step 2: 运行测试，验证编译失败（类型未定义）**

Run: `./gradlew test --tests "com.phantom.scroll.data.SettingsReducerTest" 2>&1 | tail -15`
Expected: FAIL —— `SettingsSnapshot`/`SettingsDelta`/`LoadedState`/`SettingsReducer` 未定义。

- [ ] **Step 3: 实现 reducer + 类型**

Create `app/src/main/java/com/phantom/scroll/data/SettingsReducer.kt`:

```kotlin
package com.phantom.scroll.data

/**
 * Read-only snapshot of the repository's persistable state, fed to [SettingsReducer].
 * The reducer never holds mutable state — this is its only input besides the intent.
 */
data class SettingsSnapshot(
    val global: ScrollSettings,
    val profiles: Map<String, AppProfile>,
    val perAppEnabled: Boolean,
    val currentPackage: String?
)

/** What a disk load yields before reconciliation. */
data class LoadedState(
    val global: ScrollSettings,
    val profiles: Map<String, AppProfile>,
    val perAppEnabled: Boolean,
    val stats: ScrollStats
)

/**
 * The set of fields a reducer run wants changed. Only non-null fields are written by
 * [SettingsRepository.applyDelta]; null means "leave unchanged". This keeps the reducer
 * from overwriting fields it has no opinion about, and makes each intent's blast radius
 * explicit.
 */
data class SettingsDelta(
    val global: ScrollSettings? = null,
    val upsertedProfile: AppProfile? = null,
    val deletedPackage: String? = null,
    val perAppEnabled: Boolean? = null,
    val currentPackage: String? = null,
    /** Whether currentPackage should be cleared (distinct from setting it to some string). */
    val clearCurrentPackage: Boolean = false
)

/**
 * Stateless pure logic: the single home of every per-app consistency rule.
 * Inputs are immutable; output is a [SettingsDelta] that [SettingsRepository.apply]
 * applies atomically. Safe to replay under concurrency.
 */
object SettingsReducer {

    fun reduce(state: SettingsSnapshot, intent: SettingsIntent): SettingsDelta = when (intent) {
        is SettingsIntent.PackageSwitched -> {
            val hasProfile = intent.pkg != null && state.profiles.containsKey(intent.pkg)
            SettingsDelta(
                currentPackage = intent.pkg,
                clearCurrentPackage = intent.pkg == null,
                perAppEnabled = hasProfile
                // NOTE: deliberately does NOT touch global — switching apps must never reset
                // the user's global defaults (the historical bug from commit 193b9cb).
            )
        }
        is SettingsIntent.PerAppToggled -> {
            if (intent.enabled) {
                val pkg = state.currentPackage
                if (pkg != null) {
                    SettingsDelta(perAppEnabled = true, upsertedProfile = AppProfile(pkg, state.global))
                } else {
                    SettingsDelta(perAppEnabled = true)
                }
            } else {
                SettingsDelta(perAppEnabled = false, deletedPackage = state.currentPackage)
            }
        }
        is SettingsIntent.SettingEdited -> {
            val pkg = state.currentPackage
            if (state.perAppEnabled && pkg != null) {
                val existing = state.profiles[pkg]?.settings ?: state.global
                SettingsDelta(upsertedProfile = AppProfile(pkg, intent.transform(existing)))
            } else {
                SettingsDelta(global = intent.transform(state.global))
            }
        }
        is SettingsIntent.PresetApplied -> SettingsDelta(global = intent.settings)
        SettingsIntent.ForgetActiveApp -> {
            val pkg = state.currentPackage
            SettingsDelta(deletedPackage = pkg, perAppEnabled = false, clearCurrentPackage = pkg == null)
        }
    }

    /**
     * Merges a disk load with defaults. The ONLY place screen-height-adapted distanceRatio
     * is computed — applied once at load, only when the stored global still holds the default
     * distanceRatio (i.e. the user never customized it).
     */
    fun reconcileInitial(loaded: LoadedState, screenHeight: Int): SettingsDelta {
        val adapted = if (loaded.global.distanceRatio == ScrollSettings.DEFAULT.distanceRatio && screenHeight > 0) {
            loaded.global.copy(distanceRatio = (1500f / screenHeight).coerceIn(0.3f, 0.95f))
        } else {
            loaded.global
        }
        return SettingsDelta(
            global = adapted,
            upsertedProfile = null,
            deletedPackage = null,
            perAppEnabled = loaded.perAppEnabled,
            currentPackage = null,
            clearCurrentPackage = true
        )
    }
}
```

- [ ] **Step 4: 运行测试，验证通过**

Run: `./gradlew test --tests "com.phantom.scroll.data.SettingsReducerTest" 2>&1 | tail -15`
Expected: PASS（所有 SettingsReducerTest 用例绿）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/SettingsReducer.kt app/src/test/java/com/phantom/scroll/data/SettingsReducerTest.kt
git commit -m "feat(data): add stateless SettingsReducer as the single home of per-app consistency rules"
```

---

## Task 3: PackageChangeExtractor —— 事件过滤纯逻辑

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/service/PackageChangeExtractor.kt`
- Test: `app/src/test/java/com/phantom/scroll/service/PackageChangeExtractorTest.kt`

**Interfaces:**
- Produces: `object PackageChangeExtractor` with `fun extract(eventType: Int, eventPackage: String?, currentPackage: String?): String?`. Consumed by Task 5 (`PhantomScrollService`).

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/phantom/scroll/service/PackageChangeExtractorTest.kt`:

```kotlin
package com.phantom.scroll.service

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageChangeExtractorTest {

    @Test
    fun returns_null_for_unhandled_event_type() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            "com.example",
            null
        )
        assertEquals(null, result)
    }

    @Test
    fun returns_package_for_window_state_changed() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            "com.example.novel",
            "com.other"
        )
        assertEquals("com.example.novel", result)
    }

    @Test
    fun returns_package_for_window_content_changed_when_package_differs() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.example.novel",
            "com.other"
        )
        assertEquals("com.example.novel", result)
    }

    @Test
    fun returns_null_for_window_content_changed_when_package_unchanged() {
        // high-frequency content events with no package change are skipped
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            "com.example.novel",
            "com.example.novel"
        )
        assertEquals(null, result)
    }

    @Test
    fun returns_null_package_when_event_package_is_null() {
        val result = PackageChangeExtractor.extract(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            null,
            "com.other"
        )
        assertEquals(null, result)
    }
}
```

- [ ] **Step 2: 运行测试，验证失败**

Run: `./gradlew test --tests "com.phantom.scroll.service.PackageChangeExtractorTest" 2>&1 | tail -15`
Expected: FAIL —— `PackageChangeExtractor` 未定义。

- [ ] **Step 3: 实现 extractor**

Create `app/src/main/java/com/phantom/scroll/service/PackageChangeExtractor.kt`:

```kotlin
package com.phantom.scroll.service

import android.view.accessibility.AccessibilityEvent

/**
 * Pure logic extracted from PhantomScrollService.onAccessibilityEvent: decides whether an
 * accessibility event should be considered for per-app handling, and if so returns its
 * package name. No Android framework references except the [AccessibilityEvent] type
 * constants — unit-testable on the JVM.
 *
 * @return the package name to feed PerAppDetector, or null if the event should be ignored.
 */
object PackageChangeExtractor {

    fun extract(eventType: Int, eventPackage: String?, currentPackage: String?): String? {
        val isWindowStateChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isWindowContentChanged = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isWindowStateChanged && !isWindowContentChanged) return null

        // Skip high-frequency content-changed events when the package hasn't changed.
        if (isWindowContentChanged && eventPackage == currentPackage) return null

        return eventPackage // may be null
    }
}
```

- [ ] **Step 4: 运行测试，验证通过**

Run: `./gradlew test --tests "com.phantom.scroll.service.PackageChangeExtractorTest" 2>&1 | tail -15`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/PackageChangeExtractor.kt app/src/test/java/com/phantom/scroll/service/PackageChangeExtractorTest.kt
git commit -m "feat(service): extract PackageChangeExtractor pure logic from onAccessibilityEvent"
```

---

## Task 4: SettingsRepository 三层重构

**Files:**
- Rewrite: `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt` (current 296 lines)

**Interfaces:**
- Consumes: `SettingsIntent` (Task 1), `SettingsReducer`/`SettingsSnapshot`/`SettingsDelta`/`LoadedState` (Task 2), `ProfileStore` (existing).
- Produces: 重构后的 `SettingsRepository` with: `apply(intent)`、`flush()`（非阻塞 suspend）、`var isRunning` + `toggleRunning()`、`incrementStats`/`resetStats`/`setScreenWidth`/`setScreenHeight`（无副作用）、`awaitInitialized()`。删除：periodic saver、即时 launch 落盘、`updateGlobal`/`updateActive`/`upsertProfile`/`deleteProfile`/`setCurrentPackage`/`setPerAppEnabled`/`forgetActiveProfile`/`setRunning`/`stopRunning`/`startRunning`/`isRunningMutable`。

> 这是最大的任务。它替换整个文件。保留所有现有只读 StateFlow 暴露（`global`/`profiles`/`perAppEnabled`/`currentPackage`/`stats`/`isRunning`/`screenWidth`/`screenHeight`/`activeSettings`）—— 下游（ScrollOrchestrator/FloatingOverlayView/Service）只读消费它们，签名不变。

- [ ] **Step 1: 完整重写 SettingsRepository.kt**

Overwrite `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt` with:

```kotlin
package com.phantom.scroll.data

import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for scroll settings, per-app profiles, stats and runtime flags.
 *
 * Three-layer design:
 *  1. StateHolder — the [MutableStateFlow]s below; only [applyDelta] writes to the
 *     persistable ones.
 *  2. SettingsReducer — the stateless pure logic (in [SettingsReducer]) that decides
 *     how an intent transforms state; the single home of per-app consistency rules.
 *  3. Persistence — ONE debounce(500ms) collector over the persistable flows, started
 *     only after the initial load completes (so default seed values are never written
 *     back over real disk values). [flush] forces an immediate, awaitable write on
 *     service shutdown.
 *
 * Mutations enter only via [apply] (persistable) or the runtime mutators (isRunning /
 * screen size — non-persisted, no cross-field rules).
 */
class SettingsRepository(
    private val store: ProfileStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val _global = MutableStateFlow(ScrollSettings.DEFAULT)
    val global: StateFlow<ScrollSettings> = _global.asStateFlow()

    private val _profiles = MutableStateFlow<Map<String, AppProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, AppProfile>> = _profiles.asStateFlow()

    private val _perAppEnabled = MutableStateFlow(false)
    val perAppEnabled: StateFlow<Boolean> = _perAppEnabled.asStateFlow()

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    private val _stats = MutableStateFlow(ScrollStats.ZERO)
    val stats: StateFlow<ScrollStats> = _stats.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    /** Backing mutable flow for components that drive isRunning directly (ScreenStateCoordinator). */
    val isRunning: MutableStateFlow<Boolean> = _isRunning

    private val _screenWidth = MutableStateFlow(0)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()
    private val _screenHeight = MutableStateFlow(0)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()

    /**
     * Resolved effective settings: the per-app profile for [currentPackage] when per-app is on,
     * otherwise the global defaults. This is the single value gesture generation consumes.
     */
    val activeSettings: StateFlow<ScrollSettings> =
        combine(_perAppEnabled, _currentPackage, _profiles, _global) { enabled, pkg, profiles, global ->
            if (enabled && pkg != null) profiles[pkg]?.settings ?: global else global
        }.stateIn(scope, SharingStarted.Eagerly, ScrollSettings.DEFAULT)

    private val initialized = CompletableDeferred<Unit>()
    private var persistenceJob: Job? = null

    init {
        scope.launch(ioDispatcher) {
            // 1. Explicit load — blocks this coroutine (not the main thread).
            val loaded = LoadedState(
                global = store.loadGlobal(),
                profiles = store.loadProfiles(),
                perAppEnabled = store.loadPerAppEnabled(),
                stats = store.loadStats()
            )
            // 2. Reconcile defaults + screen-adapted distanceRatio exactly once.
            applyDelta(SettingsReducer.reconcileInitial(loaded, _screenHeight.value))
            // 3. Start the single persistence collector ONLY after disk values are in place,
            //    so seeded defaults are never written back over real values.
            startPersistenceCollector()
            initialized.complete()
        }
    }

    /** Suspends until the initial load + reconciliation has completed. For tests. */
    suspend fun awaitInitialized() = initialized.await()

    private fun startPersistenceCollector() {
        persistenceJob = scope.launch(ioDispatcher) {
            combine(_global, _profiles, _perAppEnabled, _stats) { g, p, e, s ->
                PersistableSnapshot(g, p, e, s)
            }.debounce(PERSIST_DEBOUNCE_MS)
                .collect { snapshot -> savePersistable(snapshot) }
        }
    }

    private suspend fun savePersistable(snapshot: PersistableSnapshot) {
        // I/O failure is non-fatal: log and let the next debounce tick retry. Throwing would
        // cancel the collector and silently stop ALL persistence.
        try {
            store.saveGlobal(snapshot.global)
            store.saveAllProfiles(snapshot.profiles)
            store.savePerAppEnabled(snapshot.perAppEnabled)
            store.saveStats(snapshot.stats)
        } catch (e: Exception) {
            PhantomLog.e(TAG, "Persist failed (will retry on next change): ${e.message}", e)
        }
    }

    /**
     * Forces an immediate, awaitable write of current in-memory state, skipping the debounce.
     * Called on service shutdown. Non-blocking from the caller's perspective — the caller
     * awaits completion on an IO dispatcher (Service.onDestroy launches this on Dispatchers.IO).
     */
    suspend fun flush() {
        // Wait for initial load so we don't flush defaults over real disk values.
        initialized.await()
        savePersistable(
            PersistableSnapshot(_global.value, _profiles.value, _perAppEnabled.value, _stats.value)
        )
    }

    // ---- the single mutation entry for persistable state ----

    /**
     * Applies [intent] via [SettingsReducer] and writes the resulting delta atomically.
     * Thread-safe: StateFlow updates are atomic; the reducer is stateless and replayable.
     */
    fun apply(intent: SettingsIntent) {
        val snapshot = SettingsSnapshot(
            global = _global.value,
            profiles = _profiles.value,
            perAppEnabled = _perAppEnabled.value,
            currentPackage = _currentPackage.value
        )
        val delta = SettingsReducer.reduce(snapshot, intent)
        // Reducer is pure logic — a delta it can't produce is a programming error.
        // (It currently can't fail, but this guard future-proofs new intents.)
        applyDelta(delta)
    }

    /** Applies a delta to the backing flows. The ONLY place persistable flows are written. */
    private fun applyDelta(delta: SettingsDelta) {
        delta.global?.let { _global.value = it }
        if (delta.upsertedProfile != null) {
            _profiles.update { it + (delta.upsertedProfile.packageName to delta.upsertedProfile) }
        }
        if (delta.deletedPackage != null) {
            _profiles.update { it - delta.deletedPackage }
        }
        delta.perAppEnabled?.let { _perAppEnabled.value = it }
        if (delta.clearCurrentPackage) {
            _currentPackage.value = null
        } else if (delta.currentPackage != null) {
            _currentPackage.value = delta.currentPackage
        }
    }

    // ---- non-persisted runtime mutators (no cross-field rules) ----

    var isRunningValue: Boolean
        get() = _isRunning.value
        set(value) { _isRunning.value = value }

    fun toggleRunning() { _isRunning.value = !_isRunning.value }

    fun setScreenWidth(value: Int) { _screenWidth.value = value }
    fun setScreenHeight(value: Int) { _screenHeight.value = value }

    fun incrementStats(swipeDelta: Long = 1, elapsedDeltaMs: Long) {
        _stats.update { it.copy(swipeCount = it.swipeCount + swipeDelta, elapsedMs = it.elapsedMs + elapsedDeltaMs) }
    }
    fun resetStats() { _stats.value = ScrollStats.ZERO }

    private data class PersistableSnapshot(
        val global: ScrollSettings,
        val profiles: Map<String, AppProfile>,
        val perAppEnabled: Boolean,
        val stats: ScrollStats
    )

    private companion object {
        const val TAG = "SettingsRepository"
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}
```

> 关于 `isRunning`：为了保留 `ScreenStateCoordinator` 直接持 `MutableStateFlow<Boolean>` 的现有模式（它需要可变句柄来 `onScreenOff/onUserPresent` 里直接赋值），`val isRunning: MutableStateFlow<Boolean>` 直接暴露可变流（替换原 `isRunningMutable`）。下游读 `repository.isRunning.value`、`ScreenStateCoordinator` 写 `repository.isRunning.value = ...`。`isRunningValue` 的 var setter 是给纯赋值场景的便捷入口。`stopRunning()`/`startRunning()`/`setRunning()` 删除——调用方改用 `isRunning.value = ...` 或 `isRunningValue = ...`。

- [ ] **Step 2: 编译验证（预期下游调用方报错）**

Run: `./gradlew compileDebugKotlin 2>&1 | grep -E "error:|\.kt:" | head -30`
Expected: 编译错误来自下游引用了已删除的 API（`updateGlobal`/`setCurrentPackage`/`setPerAppEnabled`/`stopRunning`/`toggleRunning`/`forgetActiveProfile`/`updateActive`/`upsertProfile`/`deleteProfile`/`isRunningMutable`）。这些在 Task 5-9 修复。先记录报错的文件与行，下一步逐个修。

- [ ] **Step 3: Commit（重构本身，下游适配在后续任务）**

```bash
git add app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt
git commit -m "refactor(data): rewrite SettingsRepository into 3 layers (StateHolder + reducer + single debounce collector)

- apply(SettingsIntent) is the single mutation entry; per-app rules live in SettingsReducer
- single debounce(500ms) collector replaces periodic saver + ad-hoc launch saves
- collector starts only after initial load completes (no default-overwrite race)
- flush() is non-blocking + awaitable; awaits initialization
- isRunning API collapsed to MutableStateFlow + toggleRunning()
- remove setter side effect on global.distanceRatio (now reducer-only at load)
- layered error handling: I/O failures logged+retried"
```

---

## Task 5: PhantomScrollService 适配新 API

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt`

**Interfaces:**
- Consumes: `SettingsRepository.apply`/`flush`/`isRunning` (Task 4), `PackageChangeExtractor` (Task 3).

- [ ] **Step 1: 改 onAccessibilityEvent 用 extractor + apply + perAppEnabled 早退**

Edit `PhantomScrollService.kt`, replace the existing `onAccessibilityEvent` method (current `:102-128`) with:

```kotlin
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
```

Add the import at the top of the file (with the other `com.phantom.scroll.data.*` imports):

```kotlin
import com.phantom.scroll.data.SettingsIntent
```

- [ ] **Step 2: 改 onDestroy 非阻塞 flush**

Edit `PhantomScrollService.kt`, replace the existing `onDestroy` method (current `:157-184`) with:

```kotlin
    override fun onDestroy() {
        instance = null
        PhantomLog.d(TAG, "Service being destroyed. Flushing settings...")
        repository.isRunning.value = false

        // Non-blocking flush: launch on IO, await flush (with timeout), then tear down.
        // runBlocking on the main thread was an ANR risk; this keeps shutdown off-main.
        serviceScope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.withTimeout(1500L) { repository.flush() }
            } catch (e: Exception) {
                PhantomLog.e(TAG, "Failed to flush settings on destroy: ${e.message}")
            }
            if (::floatingWindowController.isInitialized) floatingWindowController.stop()
            if (::scrollOrchestrator.isInitialized) scrollOrchestrator.stop()
            if (::eventReceiver.isInitialized) eventReceiver.stop()
            if (::keepAliveWindow.isInitialized) keepAliveWindow.hide()
            KeepAliveService.stop(this@PhantomScrollService)
            NotificationHelper.cancelNotification(this@PhantomScrollService)
            serviceScope.cancel()
            super@PhantomScrollService.onDestroy()
        }
    }
```

> 注意：`super.onDestroy()` 移到 IO 协程末尾调用，确保组件停止 + scope 取消后才回调父类。`repository.stopRunning()` 改为 `repository.isRunning.value = false`（API 已收敛）。

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileDebugKotlin 2>&1 | grep -E "error:" | head -20`
Expected: PhantomScrollService.kt 不再有错误（其余文件 Task 6-9 修）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt
git commit -m "refactor(service): use PackageChangeExtractor + apply(intent) in onAccessibilityEvent; non-blocking flush in onDestroy"
```

---

## Task 6: ScreenStateCoordinator + ScrollOrchestrator 适配 isRunning

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/ScreenStateCoordinator.kt`
- Modify: `app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt:281`

**Interfaces:**
- Consumes: `SettingsRepository.isRunning` (now `MutableStateFlow<Boolean>`, Task 4).

- [ ] **Step 1: ScreenStateCoordinator 不需要改**

`ScreenStateCoordinator.kt:10` 构造参数已是 `MutableStateFlow<Boolean>`，`ServiceEventReceiver:23` 传入 `repository.isRunningMutable`。`isRunningMutable` 被重命名为 `isRunning`（类型仍是 `MutableStateFlow<Boolean>`）。所以只改 `ServiceEventReceiver`（Task 8）。ScreenStateCoordinator 本身无需改动 —— 跳过此 step。

- [ ] **Step 2: ScrollOrchestrator —— stopRunning() → isRunning.value = false**

Edit `app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt:281`, replace:

```kotlin
                repository.stopRunning()
```

with:

```kotlin
                repository.isRunning.value = false
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew compileDebugKotlin 2>&1 | grep -E "ScrollOrchestrator|ScreenStateCoordinator" | grep error | head`
Expected: 无错误。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt
git commit -m "refactor(service): adapt ScrollOrchestrator to isRunning StateFlow API"
```

---

## Task 7: NotificationActionReceiver 适配

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/NotificationActionReceiver.kt:44,49`

- [ ] **Step 1: 替换 toggleRunning() 和 stopRunning()**

Read the file first to see exact context at lines 44 and 49, then:

- At `:44` replace `activeService.repository.toggleRunning()` — keep as-is (`toggleRunning()` 仍存在于新 API).
- At `:49` replace `activeService.repository.stopRunning()` with `activeService.repository.isRunning.value = false`.

> `toggleRunning()` 在 Task 4 的新 repository 中保留了，所以 `:44` 无需改。只有 `:49` 的 `stopRunning()` 需改。

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin 2>&1 | grep "NotificationActionReceiver" | grep error | head`
Expected: 无错误。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/NotificationActionReceiver.kt
git commit -m "refactor(service): adapt NotificationActionReceiver to isRunning StateFlow API"
```

---

## Task 8: ServiceEventReceiver 适配 isRunningMutable → isRunning

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/ServiceEventReceiver.kt:23`

- [ ] **Step 1: 替换 isRunningMutable**

Edit `ServiceEventReceiver.kt:23`, replace:

```kotlin
    private val screenState = ScreenStateCoordinator(repository.isRunningMutable)
```

with:

```kotlin
    private val screenState = ScreenStateCoordinator(repository.isRunning)
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew compileDebugKotlin 2>&1 | grep "ServiceEventReceiver" | grep error | head`
Expected: 无错误。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/ServiceEventReceiver.kt
git commit -m "refactor(service): use repository.isRunning in ServiceEventReceiver"
```

---

## Task 9: FloatingOverlayView 适配 + 删调试日志

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`

- [ ] **Step 1: 删除 6 处 android.util.Log.e 调试日志**

Delete these lines (each is a standalone statement; remove the whole line):
- `:228` — `android.util.Log.e("PhantomScrollUI", "onAttachedToWindow: bound=$bound, viewScope created")`
- `:241` — `android.util.Log.e("PhantomScrollUI", "onDetachedFromWindow: viewScope cancelled and cleared")`
- `:249` — `android.util.Log.e("PhantomScrollUI", "startCollecting: starting coroutine flow collection")`
- `:288` — `android.util.Log.e("PhantomScrollUI", "applySettings: duration=${s.duration}, interval=${s.interval}, ratio=${s.distanceRatio}, dir=${s.direction}")`
- `:328` — `android.util.Log.e("PhantomScrollUI", "applyPerAppEnabled: enabled=$enabled")`
- `:336` — `android.util.Log.e("PhantomScrollUI", "applyCurrentPackage: pkg=$pkg")`

> 删除后检查 `applySettings`/`applyPerAppEnabled`/`applyCurrentPackage` 方法体仍合法（这些 log 行只是独立语句，删除不影响逻辑）。

- [ ] **Step 2: perAppSwitch 监听器适配新 API**

Edit `FloatingOverlayView.kt:97-99`, the `perAppChangeListener` currently calls `repository?.setPerAppEnabled(checked)`. Replace with `apply`:

```kotlin
    private val perAppChangeListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        repository?.apply(SettingsIntent.PerAppToggled(checked))
    }
```

- [ ] **Step 3: forgetAppBtn / directionCell / resetBtn / cell onUserChange 适配 apply**

These currently call the deleted `forgetActiveProfile()`/`updateActive()`/`resetStats()`/`updateActive()`. Update each:

- `:165-167` resetBtn — `repository?.resetStats()` 不变（`resetStats` 保留）。
- `:168-173` forgetAppBtn — replace `repository?.forgetActiveProfile()` with `repository?.apply(SettingsIntent.ForgetActiveApp)`.
- `:174-179` directionCell — replace `repo.updateActive(active.copy(direction = next))` with `repo.apply(SettingsIntent.SettingEdited { it.copy(direction = next) })`.
- `:203-205` cell `onUserChange` (in `configureCells`) — replace each `updateActive { it.copy(...) }`:
  - `cellSpeed.onUserChange = { v -> updateActive { it.copy(duration = v.toLong()) } }` → `cellSpeed.onUserChange = { v -> repository?.apply(SettingsIntent.SettingEdited { it.copy(duration = v.toLong()) }) }`
  - `cellInterval.onUserChange = { v -> updateActive { it.copy(interval = v.toLong()) } }` → `cellInterval.onUserChange = { v -> repository?.apply(SettingsIntent.SettingEdited { it.copy(interval = v.toLong()) }) }`
  - `cellDistance.onUserChange = { v -> updateActive { it.copy(distanceRatio = v) } }` → `cellDistance.onUserChange = { v -> repository?.apply(SettingsIntent.SettingEdited { it.copy(distanceRatio = v) }) }`

- [ ] **Step 4: 删除现已无用的 updateActive 私有方法**

Delete the `updateActive(transform: (ScrollSettings) -> ScrollSettings)` private method (current `:363-366`) since all its callers now use `apply(SettingsIntent.SettingEdited {...})` directly.

- [ ] **Step 5: 添加 import**

Add to the imports:

```kotlin
import com.phantom.scroll.data.SettingsIntent
```

- [ ] **Step 6: toggleRunning() 调用不变**

`:164` `toggleBtn.setOnClickListener { repository?.toggleRunning() }` — `toggleRunning()` 在新 API 中保留，无需改。

- [ ] **Step 7: 编译验证**

Run: `./gradlew compileDebugKotlin 2>&1 | grep -E "FloatingOverlayView" | grep error | head`
Expected: 无错误。

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt
git commit -m "refactor(overlay): adapt FloatingOverlayView to apply(intent); remove 6 debug log.e calls"
```

---

## Task 10: 全量编译 + 现有测试适配

**Files:**
- Modify: `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 4 新 API（`apply`/`flush`/`isRunning`/`awaitInitialized`）。

- [ ] **Step 1: 全量编译确认主代码无错**

Run: `./gradlew compileDebugKotlin 2>&1 | grep -E "error:" | head`
Expected: 无 error。若有遗漏的调用方报错，按报错逐一适配（用 `apply(intent)` / `isRunning.value` 替换）。

- [ ] **Step 2: 重写 SettingsRepositoryTest 适配新 API**

Overwrite `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt` with tests against the new `apply()` API. Key change: the old test `setCurrentPackage_resets_global_to_default_when_no_profile` is replaced by its inverse `packageSwitched_does_not_touch_global`. Full file:

```kotlin
package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    private fun TestScope.repoWith(store: FakeProfileStore): SettingsRepository {
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher]) {
            "TestScope must carry a CoroutineDispatcher"
        }
        return SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
            .also { advanceUntilIdle() } // let init load + reconcile + start collector run
    }

    @Test
    fun loads_initial_values_from_store() = runTest {
        val store = FakeProfileStore().apply {
            global = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        }
        val repo = repoWith(store)
        assertEquals(800L, repo.global.value.duration)
        assertEquals(0.6f, repo.global.value.distanceRatio)
    }

    @Test
    fun activeSettings_falls_back_to_global_when_perApp_disabled() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_uses_profile_when_perApp_enabled_and_pkg_matches() = runTest {
        val profileSettings = ScrollSettings(duration = 999L, interval = 1111L, distanceRatio = 0.42f)
        val store = FakeProfileStore().apply {
            profiles["com.example.novel"] = AppProfile("com.example.novel", profileSettings)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        assertEquals(profileSettings, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_falls_back_to_global_when_pkg_has_no_profile() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PerAppToggled(true))
        repo.apply(SettingsIntent.PackageSwitched("com.unknown.app"))
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun packageSwitched_does_not_touch_global() = runTest {
        // Replaces old setCurrentPackage_resets_global_to_default — the behavior change:
        // switching to a profile-less app must NOT reset the user's global defaults.
        val repo = repoWith(FakeProfileStore())
        val custom = ScrollSettings(duration = 999L, interval = 9999L, distanceRatio = 0.99f, direction = ScrollDirection.UP)
        repo.apply(SettingsIntent.PresetApplied(custom))
        assertEquals(custom, repo.global.value)
        repo.apply(SettingsIntent.PackageSwitched("com.unknown.app"))
        assertEquals(custom, repo.global.value) // unchanged
    }

    @Test
    fun presetApplied_persists_after_debounce() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val updated = ScrollSettings(duration = 650L, interval = 2200L, distanceRatio = 0.8f)
        repo.apply(SettingsIntent.PresetApplied(updated))
        repo.flush()
        assertEquals(updated, store.global)
    }

    @Test
    fun perAppToggled_true_creates_profile_and_persists() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        repo.flush()
        assertEquals(repo.global.value, store.profiles["com.a"]?.settings)
        assertTrue(store.perAppEnabled)
    }

    @Test
    fun perAppToggled_false_deletes_profile() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        repo.apply(SettingsIntent.PerAppToggled(false))
        repo.flush()
        assertNull(store.profiles["com.a"])
        assertFalse(store.perAppEnabled)
    }

    @Test
    fun stats_increment_and_reset() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 2000L)
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 3000L)
        assertEquals(2L, repo.stats.value.swipeCount)
        assertEquals(5000L, repo.stats.value.elapsedMs)
        repo.resetStats()
        assertEquals(ScrollStats.ZERO, repo.stats.value)
    }

    @Test
    fun stats_persist_after_flush() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.incrementStats(swipeDelta = 5, elapsedDeltaMs = 1000L)
        repo.flush()
        assertEquals(5L, store.stats.swipeCount)
    }

    @Test
    fun isRunning_is_not_persisted_and_toggleable() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.isRunning.value = true
        advanceUntilIdle()
        assertEquals(true, repo.isRunning.value)
        repo.toggleRunning()
        assertEquals(false, repo.isRunning.value)
    }

    @Test
    fun currentPackage_initially_null() = runTest {
        val repo = repoWith(FakeProfileStore())
        assertNull(repo.currentPackage.value)
    }

    @Test
    fun settingEdited_writes_profile_when_perApp_on_and_pkg_known() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        val s = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.apply(SettingsIntent.SettingEdited { s })
        repo.flush()
        assertEquals(s, store.profiles["com.example.novel"]?.settings)
        assertEquals(ScrollSettings.DEFAULT, repo.global.value)
    }

    @Test
    fun settingEdited_writes_global_when_perApp_off() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val s = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        repo.apply(SettingsIntent.SettingEdited { s })
        repo.flush()
        assertEquals(s, repo.global.value)
        assertEquals(s, store.global)
        assertTrue(store.profiles.isEmpty())
    }

    @Test
    fun forgetActiveApp_removes_current_pkg_profile() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.example.novel"] = AppProfile("com.example.novel", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.example.novel"))
        advanceUntilIdle()
        repo.apply(SettingsIntent.ForgetActiveApp)
        repo.flush()
        assertNull(store.profiles["com.example.novel"])
        assertFalse(repo.perAppEnabled.value)
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `./gradlew test --tests "com.phantom.scroll.data.*" 2>&1 | tail -20`
Expected: 所有 data 层测试 PASS（含 SettingsReducerTest + SettingsRepositoryTest）。

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt
git commit -m "test(data): adapt SettingsRepositoryTest to apply(intent) API; assert package switch no longer resets global"
```

---

## Task 11: 并发回归测试（7 个历史 race）

**Files:**
- Create: `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryConcurrencyTest.kt`

**Interfaces:**
- Consumes: Task 4 新 API。

> 这 7 个用例用 `runTest` + `advanceUntilIdle` 虚拟时间控制，覆盖每一次历史 race commit。

- [ ] **Step 1: 写并发回归测试**

Create `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryConcurrencyTest.kt`:

```kotlin
package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsRepositoryConcurrencyTest {

    private fun TestScope.repoWith(store: FakeProfileStore): SettingsRepository {
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher])
        return SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
            .also { advanceUntilIdle() }
    }

    // Race 1 (commit 6d95e25): defaults written back over real disk values during load.
    @Test
    fun load_completes_before_persistence_starts_no_default_overwrite() = runTest {
        val realDiskGlobal = ScrollSettings(duration = 800L, interval = 3000L, distanceRatio = 0.6f)
        val store = FakeProfileStore().apply { global = realDiskGlobal }
        val repo = repoWith(store)
        // After load, disk value must be intact (not overwritten by the DEFAULT seed).
        repo.flush()
        assertEquals(realDiskGlobal, store.global)
    }

    // Race 2 (commit 349feea): rapid per-app toggle leaves inconsistent profile/flag.
    @Test
    fun rapid_perApp_toggle_ends_consistent() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repeat(5) {
            repo.apply(SettingsIntent.PerAppToggled(true))
            repo.apply(SettingsIntent.PerAppToggled(false))
        }
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        assertTrue(repo.perAppEnabled.value)
        assertEquals("com.a", repo.profiles.value.keys.singleOrNull())
    }

    // Race 3 (commit 6d95e25): edit during a package switch writes to the wrong (old) target.
    @Test
    fun edit_during_package_switch_writes_to_current_not_old() = runTest {
        val repo = repoWith(FakeProfileStore())
        repo.apply(SettingsIntent.PackageSwitched("com.old"))
        repo.apply(SettingsIntent.PerAppToggled(true)) // per-app on, profile for com.old
        repo.apply(SettingsIntent.PackageSwitched("com.new"))
        repo.apply(SettingsIntent.PerAppToggled(true)) // profile for com.new
        // Edit now targets com.new, not com.old.
        val edited = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.apply(SettingsIntent.SettingEdited { edited })
        advanceUntilIdle()
        assertEquals(edited, repo.profiles.value["com.new"]?.settings)
        assertNotEquals(edited, repo.profiles.value["com.old"]?.settings)
    }

    // Race 4 (T2): flush is non-blocking — runs on IO dispatcher, doesn't hang the caller.
    @Test
    fun flush_completes_without_blocking() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PresetApplied(ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f)))
        repo.flush() // suspend; completes within virtual time
        assertEquals(700L, store.global.duration)
    }

    // Race 5 (commit ededb56): high-frequency writes collapse to a single persisted write per debounce.
    @Test
    fun debounce_collapses_high_frequency_writes() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repeat(10) { i ->
            repo.apply(SettingsIntent.PresetApplied(ScrollSettings(duration = (500L + i), interval = 2000L, distanceRatio = 0.7f)))
        }
        // Before debounce elapses, disk still holds the seed/default.
        // advance past debounce window.
        advanceTimeBy(600)
        advanceUntilIdle()
        // Disk reflects the last value; intermediate ones collapsed.
        assertEquals(509L, store.global.duration)
    }

    // Race 6: flush on shutdown does not lose the last change.
    @Test
    fun flush_before_scope_cancel_persists_last_change() = runTest {
        val store = FakeProfileStore()
        val repoScope = CoroutineScope(coroutineContext + Job())
        val testDispatcher = checkNotNull(coroutineContext[CoroutineDispatcher])
        val repo = SettingsRepository(store, repoScope, ioDispatcher = testDispatcher)
        advanceUntilIdle()
        val last = ScrollSettings(duration = 750L, interval = 2500L, distanceRatio = 0.72f)
        repo.apply(SettingsIntent.PresetApplied(last))
        repo.flush() // explicit flush before cancel
        repoScope.cancel()
        assertEquals(last, store.global)
    }

    // Race 7 (commit f430d93): forgetActiveApp is atomic (profile delete + perApp off together).
    @Test
    fun forgetActiveApp_is_atomic() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.a"] = AppProfile("com.a", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.apply(SettingsIntent.PackageSwitched("com.a"))
        repo.apply(SettingsIntent.PerAppToggled(true))
        advanceUntilIdle()
        repo.apply(SettingsIntent.ForgetActiveApp)
        // After one apply(), both effects visible together (no intermediate inconsistent state).
        assertNull(repo.profiles.value["com.a"])
        assertFalse(repo.perAppEnabled.value)
    }
}
```

- [ ] **Step 2: 运行并发测试**

Run: `./gradlew test --tests "com.phantom.scroll.data.SettingsRepositoryConcurrencyTest" 2>&1 | tail -20`
Expected: 所有 7 个用例 PASS。若某个用例因虚拟时间/`advanceTimeBy` 行为不符，调整时间推进方式（`runTest` 下 `debounce` 由虚拟时间驱动）。

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/phantom/scroll/data/SettingsRepositoryConcurrencyTest.kt
git commit -m "test(data): add 7 concurrency regression tests covering historical per-app races"
```

---

## Task 12: 全量测试 + 死代码确认 + 验收

**Files:**
- 无新文件；运行全量测试与扫描。

- [ ] **Step 1: 全量单测**

Run: `./gradlew test 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL，全部测试 PASS（含原有 12 文件 + 新增 3 文件）。

- [ ] **Step 2: 确认无 android.util.Log 直调（T1）**

Run: `grep -rn "android.util.Log" app/src/main/java/`
Expected: 无输出（所有日志走 PhantomLog）。

- [ ] **Step 3: 确认无 runBlocking 在主线程（T2）**

Run: `grep -rn "runBlocking" app/src/main/java/`
Expected: 无输出。

- [ ] **Step 4: 确认死代码已清（T3/T5 + 已删 API）**

Run: `grep -rn "periodicSave\|enablePeriodicSave\|PERSIST_DEBOUNCE_MS.*500L.*unreferenced\|isRunningMutable\|stopRunning\|startRunning\|setRunning\b\|setCurrentPackage\|setPerAppEnabled\|forgetActiveProfile\|updateActive\|upsertProfile\|deleteProfile\|updateGlobal" app/src/main/java/ app/src/test/java/`
Expected: 仅在 SettingsRepository 内部可能出现 `setRunning`/`PERSIST_DEBOUNCE_MS`（合理），其余被删 API 无残留引用。逐一核对输出合理。

- [ ] **Step 5: 确认 release 构建绿**

Run: `./gradlew assembleRelease 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit（如有 lint/扫描发现的清理）**

若 Step 2-4 发现残留，修复后：

```bash
git add -A
git commit -m "chore: final cleanup after hardening refactor"
```

若无可改，跳过此 step。

- [ ] **Step 7: 更新记忆（项目状态）**

更新 memory 文件记录本次加固已完成（reducer 三层重构、并发测试、技术债清理），供后续对话参考。由执行者在任务完成后操作。

---

## Self-Review 结果

**1. Spec 覆盖**：
- 第 1 节（reducer/intent）→ Task 1, 2 ✓
- 第 2 节（单一持久化/并发模型/isRunning 收敛/flush）→ Task 4 ✓
- 第 3.1 节（PackageChangeExtractor）→ Task 3, 5 ✓
- 第 3.2 节（7 个并发回归测试）→ Task 11 ✓
- 第 3.3 节（Overlay 不引 Robolectric）→ 不需任务，靠手工对齐 ✓
- 第 4 节技术债：T1→Task 9；T2→Task 4+5；T3→Task 4；T4→Task 2(reconcileInitial)；T5→Task 4；T7→Task 4+6+7+8+9；T8→Task 4 ✓
- 第 6 节文件清单 → 全覆盖 ✓
- 第 7 节验收 → Task 12 ✓

**2. 占位符扫描**：无 TBD/TODO；每个代码 step 含完整代码。

**3. 类型一致性**：`SettingsIntent` 子类型名在 Task 1 定义、Task 2 reducer 消费、Task 5/9 调用方使用 —— 一致。`apply(SettingsIntent)` 签名一致。`isRunning` 类型 `MutableStateFlow<Boolean>` 在 Task 4 定义、Task 6/8 消费一致。`flush()` suspend 非阻塞签名一致。

**已知执行注意点**（非占位符，是真实实现自由度）：
- Task 4 Step 1 注释说明了 `isRunning` 暴露策略的理由。
- Task 11 用例 5 的 `advanceTimeBy(600)` 依赖 `runTest` 虚拟时间驱动 debounce —— 若实际行为不符，执行者调整时间推进。
