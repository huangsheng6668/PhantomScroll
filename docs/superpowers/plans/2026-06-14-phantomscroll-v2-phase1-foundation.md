# PhantomScroll V2 · Phase 1 实施计划：可维护性地基

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入 `data/` 领域模型层与 `SettingsRepository` 单一真相源，持久化解耦为 `ProfileStore` 接口并迁移到 DataStore，把核心循环失败逻辑与锁屏状态机抽离为纯逻辑并补单测 —— **对外行为零变化**。

**Architecture:** 新增 `data/` 包承载领域模型与仓库；`ProfileStore` 接口隔离持久化（生产用 DataStore + SharedPreferencesMigration，测试用内存 Fake）；`ScrollOrchestrator`/`ServiceEventReceiver` 切换为读 `SettingsRepository`，失败/锁屏逻辑分别委托纯逻辑类 `FailurePolicy`/`ScreenStateCoordinator`；旧的 `ScrollConfig` 降级为 `@Deprecated` 桥接适配器，仅为让即将在 Phase 2 删除的 Compose `FloatingPanel` 继续编译。

**Tech Stack:** Kotlin, kotlinx.coroutines (Flow/StateFlow), Preferences DataStore, JUnit 4, kotlinx-coroutines-test, Mockito + mockito-kotlin。

**Spec:** [docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md](../specs/2026-06-14-phantomscroll-v2-optimization-design.md) §Phase 1

> **本计划范围：仅 Phase 1。** Phase 2（悬浮窗原生重写）/ Phase 3（产品功能）/ Phase 4（性能收尾）各有独立计划，在前一阶段闸门通过后撰写。
>
> **提交约定：** 每个任务末尾的 `git commit` 均需在提交信息末尾追加一行 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`（下文各 commit 命令示例中不再重复写出该行）。
>
> **测试命令约定：** 本项目用 Gradle wrapper。跑单个测试类用 `./gradlew :app:testDebugUnitTest --tests "<全限定类名>"`；跑全部单测用 `./gradlew :app:testDebugUnitTest`；构建校验用 `./gradlew assembleDebug`。所有命令预期最终输出 `BUILD SUCCESSFUL`。

---

## 文件结构（Phase 1 新增/修改）

**新增（`app/src/main/java/com/phantom/scroll/`）：**

| 文件 | 职责 |
|------|------|
| `data/ScrollDirection.kt` | 枚举 UP/DOWN |
| `data/ScrollSettings.kt` | 全局设置数据类 + `DEFAULT` |
| `data/AppProfile.kt` | 按 App 配置数据类 |
| `data/ScrollStats.kt` | 统计数据类 + `ZERO` |
| `data/Preset.kt` | 预设数据类（Phase 1 仅空壳，Phase 3 填内置常量） |
| `data/ProfileStore.kt` | 持久化接口（全 `suspend`） |
| `data/MigrationMapper.kt` | 纯函数：旧 SharedPrefs key → `ScrollSettings` |
| `data/SettingsRepository.kt` | 单一真相源 |
| `data/DataStoreProfileStore.kt` | DataStore 实现 + `SharedPreferencesMigration` |
| `service/FailurePolicy.kt` | 纯逻辑：失败计数/自动暂停 + `FailureDecision` |
| `service/ScreenStateCoordinator.kt` | 纯逻辑：锁屏暂停/亮屏恢复 |

**新增测试（`app/src/test/java/com/phantom/scroll/`）：**

| 文件 | 职责 |
|------|------|
| `data/ScrollSettingsTest.kt` | `DEFAULT` 契约 |
| `data/MigrationMapperTest.kt` | 迁移映射（全值/空值/部分值） |
| `data/SettingsRepositoryTest.kt` | activeSettings 解析、profile CRUD、stats、debounce 落盘（用 `FakeProfileStore`） |
| `data/FakeProfileStore.kt` | 内存测试替身，实现 `ProfileStore` |
| `service/FailurePolicyTest.kt` | 失败计数/阈值/忽略语义 |
| `service/ScreenStateCoordinatorTest.kt` | 锁屏状态机 |

**修改：**

| 文件 | 改动 |
|------|------|
| `service/ScrollOrchestrator.kt` | 读 `SettingsRepository`，失败逻辑委托 `FailurePolicy` |
| `service/ServiceEventReceiver.kt` | 读 `SettingsRepository`，状态机委托 `ScreenStateCoordinator` |
| `service/PhantomScrollService.kt` | 构造并持有 `SettingsRepository`，`isRunning` 观察改用 repository |
| `config/ScrollConfig.kt` | 降级为 `@Deprecated` 桥接适配器，委托 repository |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | 加 DataStore 依赖 |

**迁移测试：**

| 文件 | 改动 |
|------|------|
| `app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt` | 原接口已变；仅保留适配器逻辑断言，其余迁移到 `SettingsRepositoryTest` |

---

## Task 1：添加 DataStore 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1：在版本目录新增 DataStore 版本与库声明**

在 `gradle/libs.versions.toml` 的 `[versions]` 段追加（放在 `coroutines = "1.9.0"` 行之后即可）：

```toml
datastore = "1.1.1"
```

在 `[libraries]` 段追加（放在 coroutines 库声明之后）：

```toml
# DataStore (persistence)
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```

- [ ] **Step 2：在 app 模块依赖中引用**

编辑 `app/build.gradle.kts`，在 `dependencies { ... }` 块内、`implementation(libs.kotlinx.coroutines.android)` 行之后追加：

```kotlin
    // DataStore (async persistence, replaces SharedPreferences)
    implementation(libs.androidx.datastore.preferences)
```

- [ ] **Step 3：验证构建通过**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（依赖可解析）

- [ ] **Step 4：Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Preferences DataStore dependency"
```

---

## Task 2：领域模型（data/ 纯数据类）

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/data/ScrollDirection.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/ScrollSettings.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/AppProfile.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/ScrollStats.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/Preset.kt`
- Test: `app/src/test/java/com/phantom/scroll/data/ScrollSettingsTest.kt`

- [ ] **Step 1：先写 ScrollSettings.DEFAULT 契约测试**

Create `app/src/test/java/com/phantom/scroll/data/ScrollSettingsTest.kt`:

```kotlin
package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollSettingsTest {
    @Test
    fun defaults_match_legacy_scroll_config() {
        // Must equal the old ScrollConfig defaults so behavior is unchanged after migration.
        val d = ScrollSettings.DEFAULT
        assertEquals(500L, d.duration)
        assertEquals(2000L, d.interval)
        assertEquals(0.75f, d.distanceRatio)
        assertEquals(ScrollDirection.UP, d.direction)
    }
}
```

- [ ] **Step 2：运行测试确认失败（类不存在）**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.ScrollSettingsTest"`
Expected: FAIL（编译失败，`ScrollSettings` / `ScrollDirection` 未解析）

- [ ] **Step 3：创建 ScrollDirection 与 ScrollSettings**

Create `app/src/main/java/com/phantom/scroll/data/ScrollDirection.kt`:

```kotlin
package com.phantom.scroll.data

enum class ScrollDirection {
    /** 手指上滑，内容向上滚动（翻到下一页） */
    UP,
    /** 手指下滑，内容向下滚动（翻到上一页） */
    DOWN
}
```

Create `app/src/main/java/com/phantom/scroll/data/ScrollSettings.kt`:

```kotlin
package com.phantom.scroll.data

/**
 * Immutable scroll configuration. The single set of tunables that drives gesture generation.
 * @param duration 单次滑动时长 ms，范围 200..1500
 * @param interval 两次滑动间隔 ms，范围 500..10000
 * @param distanceRatio 滑动距离占屏幕安全区高度比例，范围 0.30..0.95
 * @param direction 滑动方向
 */
data class ScrollSettings(
    val duration: Long,
    val interval: Long,
    val distanceRatio: Float,
    val direction: ScrollDirection = ScrollDirection.UP
) {
    companion object {
        /** 与旧 ScrollConfig 默认值一致，保证迁移后行为不变。 */
        val DEFAULT = ScrollSettings(
            duration = 500L,
            interval = 2000L,
            distanceRatio = 0.75f,
            direction = ScrollDirection.UP
        )
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.ScrollSettingsTest"`
Expected: PASS

- [ ] **Step 5：创建 AppProfile / ScrollStats / Preset（纯数据，无独立测试，由后续 repository 测试覆盖）**

Create `app/src/main/java/com/phantom/scroll/data/AppProfile.kt`:

```kotlin
package com.phantom.scroll.data

/** Per-app override of [ScrollSettings], keyed by [packageName]. */
data class AppProfile(
    val packageName: String,
    val settings: ScrollSettings
)
```

Create `app/src/main/java/com/phantom/scroll/data/ScrollStats.kt`:

```kotlin
package com.phantom.scroll.data

/** Cumulative runtime statistics. Persisted across service restarts. */
data class ScrollStats(
    val swipeCount: Long,
    val elapsedMs: Long
) {
    companion object {
        val ZERO = ScrollStats(swipeCount = 0L, elapsedMs = 0L)
    }
}
```

Create `app/src/main/java/com/phantom/scroll/data/Preset.kt`:

```kotlin
package com.phantom.scroll.data

/**
 * Named scene preset. Phase 1 ships only the model shell;
 * built-in constants (小说/漫画) are filled in Phase 3.
 */
data class Preset(
    val name: String,
    val settings: ScrollSettings
)
```

- [ ] **Step 6：验证全部单测通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（含已有 GestureEngineTest / ScrollConfigTest）

- [ ] **Step 7：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/ app/src/test/java/com/phantom/scroll/data/ScrollSettingsTest.kt
git commit -m "feat(data): add domain models (ScrollSettings/AppProfile/ScrollStats/Preset/ScrollDirection)"
```

---

## Task 3：FailurePolicy（纯逻辑 + TDD）

**Files:**
- Test: `app/src/test/java/com/phantom/scroll/service/FailurePolicyTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/service/FailurePolicy.kt`

- [ ] **Step 1：写失败测试**

Create `app/src/test/java/com/phantom/scroll/service/FailurePolicyTest.kt`:

```kotlin
package com.phantom.scroll.service

import org.junit.Assert.assertEquals
import org.junit.Test

class FailurePolicyTest {

    @Test
    fun success_resets_consecutive_count() {
        val policy = FailurePolicy(threshold = 3)
        // two failures, then success clears the count
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        policy.recordSuccess()
        // after reset, need 3 more to auto-pause
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.AutoPause, policy.recordFailure(isRunning = true))
    }

    @Test
    fun reaches_threshold_autoPauses_and_resets() {
        val policy = FailurePolicy(threshold = 3)
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.AutoPause, policy.recordFailure(isRunning = true))
        // counter reset after auto-pause; another failure is the first again
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
    }

    @Test
    fun failure_while_not_running_is_ignored_and_not_counted() {
        // Manual pause / lock screen must not inflate the failure count.
        val policy = FailurePolicy(threshold = 3)
        assertEquals(FailureDecision.Ignored, policy.recordFailure(isRunning = false))
        assertEquals(FailureDecision.Ignored, policy.recordFailure(isRunning = false))
        // still only the first real failure counted afterwards
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.Continue, policy.recordFailure(isRunning = true))
        assertEquals(FailureDecision.AutoPause, policy.recordFailure(isRunning = true))
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.FailurePolicyTest"`
Expected: FAIL（`FailurePolicy` / `FailureDecision` 未解析）

- [ ] **Step 3：实现 FailurePolicy**

Create `app/src/main/java/com/phantom/scroll/service/FailurePolicy.kt`:

```kotlin
package com.phantom.scroll.service

/** Outcome of recording a gesture failure, used by [ScrollOrchestrator]. */
enum class FailureDecision {
    /** 计入一次失败，尚未达阈值，继续循环。 */
    Continue,
    /** 达到阈值，应自动暂停（调用方负责停 isRunning 与用户反馈）。 */
    AutoPause,
    /** 当前未在运行（手动暂停/锁屏），不计失败。 */
    Ignored
}

/**
 * Pure logic that owns consecutive-failure counting and the auto-pause threshold.
 * Extracted from ScrollOrchestrator so it is unit-testable without Android.
 */
class FailurePolicy(private val threshold: Int = 3) {
    private var consecutive = 0

    /**
     * Records a failure. Returns the decision the caller should act on.
     * Failures recorded while [isRunning] is false are [FailureDecision.Ignored]
     * (manual pause / lock screen) and do not increment the counter.
     */
    fun recordFailure(isRunning: Boolean): FailureDecision {
        if (!isRunning) return FailureDecision.Ignored
        consecutive++
        return if (consecutive >= threshold) {
            consecutive = 0
            FailureDecision.AutoPause
        } else {
            FailureDecision.Continue
        }
    }

    /** Clears the counter on a successful gesture. */
    fun recordSuccess() {
        consecutive = 0
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.FailurePolicyTest"`
Expected: PASS

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/FailurePolicy.kt app/src/test/java/com/phantom/scroll/service/FailurePolicyTest.kt
git commit -m "feat(service): extract FailurePolicy pure logic with unit tests"
```

---

## Task 4：ScreenStateCoordinator（纯逻辑 + TDD）

**Files:**
- Test: `app/src/test/java/com/phantom/scroll/service/ScreenStateCoordinatorTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/service/ScreenStateCoordinator.kt`

- [ ] **Step 1：写失败测试**

Create `app/src/test/java/com/phantom/scroll/service/ScreenStateCoordinatorTest.kt`:

```kotlin
package com.phantom.scroll.service

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenStateCoordinatorTest {

    private fun coord(running: Boolean): Pair<ScreenStateCoordinator, MutableStateFlow<Boolean>> {
        val isRunning = MutableStateFlow(running)
        return ScreenStateCoordinator(isRunning) to isRunning
    }

    @Test
    fun screen_off_while_running_pauses_and_remembers() {
        val (c, isRunning) = coord(running = true)
        c.onScreenOff()
        assertEquals(false, isRunning.value)
    }

    @Test
    fun user_present_after_screen_off_restores() {
        val (c, isRunning) = coord(running = true)
        c.onScreenOff()
        c.onUserPresent()
        assertEquals(true, isRunning.value)
    }

    @Test
    fun screen_off_while_not_running_does_not_restore_later() {
        // Regression:熄屏前未运行，亮屏后不得错误自动恢复。
        val (c, isRunning) = coord(running = false)
        c.onScreenOff()
        c.onUserPresent()
        assertEquals(false, isRunning.value)
    }

    @Test
    fun user_present_without_prior_screen_off_is_noop() {
        val (c, isRunning) = coord(running = false)
        c.onUserPresent()
        assertEquals(false, isRunning.value)
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.ScreenStateCoordinatorTest"`
Expected: FAIL（`ScreenStateCoordinator` 未解析）

- [ ] **Step 3：实现 ScreenStateCoordinator**

Create `app/src/main/java/com/phantom/scroll/service/ScreenStateCoordinator.kt`:

```kotlin
package com.phantom.scroll.service

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Pure-ish state machine for screen on/off → autoscroll pause/resume.
 * Operates on the shared [isRunning] flow; holds no Android references,
 * so it is unit-testable. Extracted from ServiceEventReceiver.
 */
class ScreenStateCoordinator(private val isRunning: MutableStateFlow<Boolean>) {
    private var wasRunningBeforeScreenOff = false

    /** ACTION_SCREEN_OFF: pause and remember prior state. */
    fun onScreenOff() {
        if (isRunning.value) {
            wasRunningBeforeScreenOff = true
            isRunning.value = false
        }
    }

    /** ACTION_USER_PRESENT: restore only if it was running before screen-off. */
    fun onUserPresent() {
        if (wasRunningBeforeScreenOff) {
            wasRunningBeforeScreenOff = false
            isRunning.value = true
        }
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.ScreenStateCoordinatorTest"`
Expected: PASS

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/ScreenStateCoordinator.kt app/src/test/java/com/phantom/scroll/service/ScreenStateCoordinatorTest.kt
git commit -m "feat(service): extract ScreenStateCoordinator pure logic with unit tests"
```

---

## Task 5：ProfileStore 接口 + FakeProfileStore 测试替身

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/data/ProfileStore.kt`
- Create: `app/src/test/java/com/phantom/scroll/data/FakeProfileStore.kt`

- [ ] **Step 1：创建 ProfileStore 接口（全 suspend）**

Create `app/src/main/java/com/phantom/scroll/data/ProfileStore.kt`:

```kotlin
package com.phantom.scroll.data

/**
 * Persistence boundary for [SettingsRepository]. All reads/writes are suspend
 * because the production backing store (DataStore) is inherently async I/O.
 * Tests supply [FakeProfileStore] (in-memory) to keep repository logic pure-JVM.
 */
interface ProfileStore {
    suspend fun loadGlobal(): ScrollSettings
    suspend fun loadProfiles(): Map<String, AppProfile>
    suspend fun loadPerAppEnabled(): Boolean
    suspend fun loadStats(): ScrollStats

    suspend fun saveGlobal(settings: ScrollSettings)
    suspend fun saveProfile(profile: AppProfile)
    suspend fun deleteProfile(packageName: String)
    suspend fun savePerAppEnabled(enabled: Boolean)
    suspend fun saveStats(stats: ScrollStats)
}
```

- [ ] **Step 2：创建 FakeProfileStore（内存测试替身）**

Create `app/src/test/java/com/phantom/scroll/data/FakeProfileStore.kt`:

```kotlin
package com.phantom.scroll.data

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory ProfileStore for unit tests. Thread-safe; records all mutations
 * so tests can assert what was persisted.
 */
class FakeProfileStore : ProfileStore {
    @Volatile var global: ScrollSettings = ScrollSettings.DEFAULT
    @Volatile var perAppEnabled: Boolean = false
    @Volatile var stats: ScrollStats = ScrollStats.ZERO
    val profiles: MutableMap<String, AppProfile> = ConcurrentHashMap()

    override suspend fun loadGlobal(): ScrollSettings = global
    override suspend fun loadProfiles(): Map<String, AppProfile> = profiles.toMap()
    override suspend fun loadPerAppEnabled(): Boolean = perAppEnabled
    override suspend fun loadStats(): ScrollStats = stats

    override suspend fun saveGlobal(settings: ScrollSettings) { global = settings }
    override suspend fun saveProfile(profile: AppProfile) { profiles[profile.packageName] = profile }
    override suspend fun deleteProfile(packageName: String) { profiles.remove(packageName) }
    override suspend fun savePerAppEnabled(enabled: Boolean) { perAppEnabled = enabled }
    override suspend fun saveStats(stats: ScrollStats) { this.stats = stats }
}
```

- [ ] **Step 3：验证编译通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（无新测试，仅编译校验）

- [ ] **Step 4：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/ProfileStore.kt app/src/test/java/com/phantom/scroll/data/FakeProfileStore.kt
git commit -m "feat(data): add ProfileStore interface and in-memory FakeProfileStore"
```

---

## Task 6：MigrationMapper（纯迁移逻辑 + TDD）

**Files:**
- Test: `app/src/test/java/com/phantom/scroll/data/MigrationMapperTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/MigrationMapper.kt`

> 把"旧 SharedPrefs 三个 key → ScrollSettings"的映射抽成纯函数，脱离 DataStore 机器单独单测；DataStore 的 `SharedPreferencesMigration` 在 Task 8 复用它。

- [ ] **Step 1：写失败测试**

Create `app/src/test/java/com/phantom/scroll/data/MigrationMapperTest.kt`:

```kotlin
package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationMapperTest {

    @Test
    fun all_legacy_values_present_maps_directly() {
        val s = MigrationMapper.buildGlobalFromLegacy(
            duration = 700L, interval = 4000L, ratio = 0.55f
        )
        assertEquals(700L, s.duration)
        assertEquals(4000L, s.interval)
        assertEquals(0.55f, s.distanceRatio)
        // direction is a new field with no legacy source → default UP
        assertEquals(ScrollDirection.UP, s.direction)
    }

    @Test
    fun all_null_like_new_install_uses_defaults() {
        val s = MigrationMapper.buildGlobalFromLegacy(duration = null, interval = null, ratio = null)
        assertEquals(ScrollSettings.DEFAULT, s)
    }

    @Test
    fun partial_values_fill_missing_with_defaults() {
        val s = MigrationMapper.buildGlobalFromLegacy(duration = 900L, interval = null, ratio = null)
        assertEquals(900L, s.duration)
        assertEquals(ScrollSettings.DEFAULT.interval, s.interval)
        assertEquals(ScrollSettings.DEFAULT.distanceRatio, s.distanceRatio)
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.MigrationMapperTest"`
Expected: FAIL（`MigrationMapper` 未解析）

- [ ] **Step 3：实现 MigrationMapper**

Create `app/src/main/java/com/phantom/scroll/data/MigrationMapper.kt`:

```kotlin
package com.phantom.scroll.data

/**
 * Pure mapping from legacy SharedPreferences keys to the new [ScrollSettings] model.
 * Kept side-effect-free so the migration transformation is unit-testable without DataStore.
 */
object MigrationMapper {
    fun buildGlobalFromLegacy(
        duration: Long?,
        interval: Long?,
        ratio: Float?
    ): ScrollSettings = ScrollSettings(
        duration = duration ?: ScrollSettings.DEFAULT.duration,
        interval = interval ?: ScrollSettings.DEFAULT.interval,
        distanceRatio = ratio ?: ScrollSettings.DEFAULT.distanceRatio,
        direction = ScrollDirection.UP // new field, no legacy source
    )
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.MigrationMapperTest"`
Expected: PASS

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/MigrationMapper.kt app/src/test/java/com/phantom/scroll/data/MigrationMapperTest.kt
git commit -m "feat(data): add pure MigrationMapper for SharedPreferences -> ScrollSettings"
```

---

## Task 7：SettingsRepository（单一真相源 + TDD）

**Files:**
- Test: `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt`

> 设计要点（来自 spec 审阅细化）：构造时 `init` 在传入 scope 内异步并发加载（`load*` 均 suspend），先以 `DEFAULT`/`ZERO` 作为初值，加载完成后回填；写入走 `drop(1).debounce(500)` 节流落盘；非 suspend 方法（`setCurrentPackage` 等）仅同步改内存 `StateFlow`，落盘由 collector 异步完成，故从任意线程调用都安全无阻塞。

- [ ] **Step 1：写失败测试（覆盖解析/CRUD/stats/debounce）**

Create `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt`:

```kotlin
package com.phantom.scroll.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    private fun repoWith(store: FakeProfileStore) = runTest {
        SettingsRepository(store, this).also { advanceUntilIdle() } // let init loads complete
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
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setCurrentPackage("com.example.novel")
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
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        advanceUntilIdle()
        assertEquals(profileSettings, repo.activeSettings.value)
    }

    @Test
    fun activeSettings_falls_back_to_global_when_pkg_has_no_profile() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.unknown.app")
        advanceUntilIdle()
        assertEquals(repo.global.value, repo.activeSettings.value)
    }

    @Test
    fun updateGlobal_persists_after_debounce() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val updated = ScrollSettings(duration = 650L, interval = 2200L, distanceRatio = 0.8f)
        repo.updateGlobal(updated)
        advanceUntilIdle() // advance virtual time past 500ms debounce
        assertEquals(updated, store.global)
    }

    @Test
    fun upsert_and_delete_profile_persist() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        val s = ScrollSettings(duration = 500L, interval = 2000L, distanceRatio = 0.75f)
        repo.upsertProfile("com.a", s)
        advanceUntilIdle()
        assertEquals(s, store.profiles["com.a"]?.settings)
        repo.deleteProfile("com.a")
        advanceUntilIdle()
        assertNull(store.profiles["com.a"])
    }

    @Test
    fun stats_increment_and_reset() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 2000L)
        repo.incrementStats(swipeDelta = 1, elapsedDeltaMs = 3000L)
        assertEquals(2L, repo.stats.value.swipeCount)
        assertEquals(5000L, repo.stats.value.elapsedMs)
        repo.resetStats()
        assertEquals(ScrollStats.ZERO, repo.stats.value)
    }

    @Test
    fun stats_persist_after_debounce() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.incrementStats(swipeDelta = 5, elapsedDeltaMs = 1000L)
        advanceUntilIdle()
        assertEquals(5L, store.stats.swipeCount)
    }

    @Test
    fun setPerAppEnabled_persists() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        advanceUntilIdle()
        assertTrue(store.perAppEnabled)
    }

    @Test
    fun isRunning_is_not_persisted() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.isRunning.value = true
        advanceUntilIdle()
        // store has no isRunning field by design; nothing to assert except no crash + value held in memory
        assertEquals(true, repo.isRunning.value)
    }

    @Test
    fun currentPackage_initially_null() = runTest {
        val repo = repoWith(FakeProfileStore())
        assertNull(repo.currentPackage.value)
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.SettingsRepositoryTest"`
Expected: FAIL（`SettingsRepository` 未解析）

- [ ] **Step 3：实现 SettingsRepository**

Create `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt`:

```kotlin
@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.phantom.scroll.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single source of truth for all scroll settings, per-app profiles, stats and runtime flags.
 *
 * Initialization is asynchronous: the constructor seeds [global]/[profiles]/[stats] with
 * defaults and launches suspend [ProfileStore] reads in [scope]; values backfill once loaded.
 *
 * Non-suspend mutators (e.g. [setCurrentPackage], [incrementStats]) only mutate in-memory
 * [MutableStateFlow]s synchronously; persistence is performed by debounced collectors launched
 * in [scope], so these mutators are thread-safe and non-blocking from any caller (incl. Binder).
 */
class SettingsRepository(
    private val store: ProfileStore,
    scope: CoroutineScope
) {
    // ---- editable global defaults ----
    private val _global = MutableStateFlow(ScrollSettings.DEFAULT)
    val global: StateFlow<ScrollSettings> = _global.asStateFlow()

    // ---- per-app profiles ----
    private val _profiles = MutableStateFlow<Map<String, AppProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, AppProfile>> = _profiles.asStateFlow()

    private val _perAppEnabled = MutableStateFlow(false)
    val perAppEnabled: StateFlow<Boolean> = _perAppEnabled.asStateFlow()

    private val _currentPackage = MutableStateFlow<String?>(null)
    val currentPackage: StateFlow<String?> = _currentPackage.asStateFlow()

    // ---- runtime stats ----
    private val _stats = MutableStateFlow(ScrollStats.ZERO)
    val stats: StateFlow<ScrollStats> = _stats.asStateFlow()

    // ---- runtime-only flags (not persisted) ----
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // dynamic display dimensions (pixels)
    private val _screenWidth = MutableStateFlow(0)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()
    private val _screenHeight = MutableStateFlow(0)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()

    fun setScreenWidth(value: Int) { _screenWidth.value = value }
    fun setScreenHeight(value: Int) { _screenHeight.value = value }

    /**
     * Resolved effective settings: the per-app profile for [currentPackage] when per-app is on,
     * otherwise the global defaults. This is the single value gesture generation consumes.
     */
    val activeSettings: StateFlow<ScrollSettings> =
        combine(_perAppEnabled, _currentPackage, _profiles, _global) { enabled, pkg, profiles, global ->
            if (enabled && pkg != null) profiles[pkg]?.settings ?: global else global
        }.stateIn(scope, SharingStarted.Eagerly, ScrollSettings.DEFAULT)

    init {
        // Async load (DataStore is async I/O) — backfill defaults once read completes.
        scope.launch {
            _global.value = store.loadGlobal()
            _profiles.value = store.loadProfiles()
            _perAppEnabled.value = store.loadPerAppEnabled()
            _stats.value = store.loadStats()

            // Start persistence collectors after initial load completes.
            // drop(1) filters out the loaded values, collecting subsequent mutations only.
            launch {
                _global.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.saveGlobal(it) }
            }
            launch {
                _profiles.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.saveAllProfiles(it) }
            }
            launch {
                _perAppEnabled.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.savePerAppEnabled(it) }
            }
            launch {
                _stats.drop(1).debounce(PERSIST_DEBOUNCE_MS).collect { store.saveStats(it) }
            }
        }
    }

    // ---- mutations ----
    suspend fun updateGlobal(settings: ScrollSettings) { _global.value = settings }
    suspend fun upsertProfile(packageName: String, settings: ScrollSettings) {
        _profiles.update { current ->
            current + (packageName to AppProfile(packageName, settings))
        }
    }
    suspend fun deleteProfile(packageName: String) {
        _profiles.update { current ->
            current - packageName
        }
    }
    fun setCurrentPackage(packageName: String?) { _currentPackage.value = packageName }
    fun setPerAppEnabled(enabled: Boolean) { _perAppEnabled.value = enabled }

    fun incrementStats(swipeDelta: Long = 1, elapsedDeltaMs: Long) {
        _stats.update { current ->
            current.copy(
                swipeCount = current.swipeCount + swipeDelta,
                elapsedMs = current.elapsedMs + elapsedDeltaMs
            )
        }
    }
    fun resetStats() { _stats.value = ScrollStats.ZERO }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}
```

> 注意：上面的 profiles 持久化调用了 `store.saveAllProfiles(map)`，但 `ProfileStore` 接口只有 `saveProfile`/`deleteProfile`。为支持整表节流落盘，需在 `ProfileStore` 增加一个方法。下一步处理。

- [ ] **Step 4：在 ProfileStore 增加整表保存方法并实现**

Edit `app/src/main/java/com/phantom/scroll/data/ProfileStore.kt`，在 `saveProfile` 行之后追加：

```kotlin
    suspend fun saveAllProfiles(profiles: Map<String, AppProfile>)
```

Edit `app/src/test/java/com/phantom/scroll/data/FakeProfileStore.kt`，在 `saveProfile` 之后追加：

```kotlin
    override suspend fun saveAllProfiles(profiles: Map<String, AppProfile>) {
        this.profiles.clear()
        this.profiles.putAll(profiles)
    }
```

- [ ] **Step 5：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.SettingsRepositoryTest"`
Expected: PASS（全部用例）

- [ ] **Step 6：验证全部单测通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 7：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt app/src/main/java/com/phantom/scroll/data/ProfileStore.kt app/src/test/java/com/phantom/scroll/data/FakeProfileStore.kt app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt
git commit -m "feat(data): add SettingsRepository single source of truth with tests"
```

---

## Task 8：DataStoreProfileStore（生产持久化实现）

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/data/DataStoreProfileStore.kt`

> 该实现把 DataStore 的异步读写接到 `ProfileStore` 接口，并用 `SharedPreferencesMigration` 把旧 key（`scroll_duration`/`scroll_interval`/`scroll_distance_ratio`）一次性迁移到新结构。迁移映射复用 Task 6 的纯函数 `MigrationMapper`。迁移幂等：DataStore 只在首次创建时跑迁移，二次启动不会重复覆盖（由 DataStore 自身保证）。

- [ ] **Step 1：实现 DataStoreProfileStore**

Create `app/src/main/java/com/phantom/scroll/data/DataStoreProfileStore.kt`:

```kotlin
package com.phantom.scroll.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * DataStore-backed [ProfileStore]. Migrates the legacy SharedPreferences file
 * ("phantom_scroll_prefs") on first access via [SharedPreferencesMigration],
 * delegating key mapping to the pure [MigrationMapper].
 */
class DataStoreProfileStore(private val context: Context) : ProfileStore {

    private val dataStore: DataStore<Preferences> = context.phantomDataStore

    private object Keys {
        // global
        val DURATION = longPreferencesKey("global.duration")
        val INTERVAL = longPreferencesKey("global.interval")
        val RATIO = floatPreferencesKey("global.distanceRatio")
        val DIRECTION = stringPreferencesKey("global.direction")
        // per-app
        val PERAPP_ENABLED = booleanPreferencesKey("perapp.enabled")
        // stats
        val STATS_SWIPE = longPreferencesKey("stats.swipe")
        val STATS_ELAPSED = longPreferencesKey("stats.elapsed")
    }

    override suspend fun loadGlobal(): ScrollSettings {
        val p = dataStore.data.first()
        val direction = p[Keys.DIRECTION]?.let { runCatching { ScrollDirection.valueOf(it) }.getOrNull() }
            ?: ScrollDirection.UP
        return ScrollSettings(
            duration = p[Keys.DURATION] ?: ScrollSettings.DEFAULT.duration,
            interval = p[Keys.INTERVAL] ?: ScrollSettings.DEFAULT.interval,
            distanceRatio = p[Keys.RATIO] ?: ScrollSettings.DEFAULT.distanceRatio,
            direction = direction
        )
    }

    override suspend fun loadProfiles(): Map<String, AppProfile> {
        val p = dataStore.data.first()
        // profiles stored under profile.<pkg>.<field>
        val pkgs = p.asMap().keys
            .mapNotNull { it.name }
            .filter { it.startsWith(PROFILE_PREFIX) }
            .map { it.substringAfter(PROFILE_PREFIX).substringBefore('.') }
            .toSet()
        return pkgs.associateWith { pkg -> AppProfile(pkg, loadProfileInternal(p, pkg)) }
    }

    override suspend fun loadPerAppEnabled(): Boolean =
        dataStore.data.first()[Keys.PERAPP_ENABLED] ?: false

    override suspend fun loadStats(): ScrollStats {
        val p = dataStore.data.first()
        return ScrollStats(
            swipeCount = p[Keys.STATS_SWIPE] ?: 0L,
            elapsedMs = p[Keys.STATS_ELAPSED] ?: 0L
        )
    }

    override suspend fun saveGlobal(settings: ScrollSettings) {
        dataStore.edit { it ->
            it[Keys.DURATION] = settings.duration
            it[Keys.INTERVAL] = settings.interval
            it[Keys.RATIO] = settings.distanceRatio
            it[Keys.DIRECTION] = settings.direction.name
        }
    }

    override suspend fun saveProfile(profile: AppProfile) {
        dataStore.edit { it -> writeProfile(it, profile.packageName, profile.settings) }
    }

    override suspend fun deleteProfile(packageName: String) {
        dataStore.edit { it ->
            listOf("duration", "interval", "distanceRatio", "direction").forEach { field ->
                it.remove(stringPreferencesKey("$PROFILE_PREFIX$packageName.$field"))
            }
        }
    }

    override suspend fun saveAllProfiles(profiles: Map<String, AppProfile>) {
        dataStore.edit { it ->
            // clear existing profile keys first
            it.asMap().keys.forEach { key ->
                if (key.name.startsWith(PROFILE_PREFIX)) it.remove(key)
            }
            profiles.values.forEach { (pkg, s) -> writeProfile(it, pkg, s) }
        }
    }

    override suspend fun savePerAppEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PERAPP_ENABLED] = enabled }
    }

    override suspend fun saveStats(stats: ScrollStats) {
        dataStore.edit { it ->
            it[Keys.STATS_SWIPE] = stats.swipeCount
            it[Keys.STATS_ELAPSED] = stats.elapsedMs
        }
    }

    private fun loadProfileInternal(p: Preferences, pkg: String): ScrollSettings = ScrollSettings(
        duration = p[longPreferencesKey("$PROFILE_PREFIX$pkg.duration")] ?: ScrollSettings.DEFAULT.duration,
        interval = p[longPreferencesKey("$PROFILE_PREFIX$pkg.interval")] ?: ScrollSettings.DEFAULT.interval,
        distanceRatio = p[floatPreferencesKey("$PROFILE_PREFIX$pkg.distanceRatio")] ?: ScrollSettings.DEFAULT.distanceRatio,
        direction = p[stringPreferencesKey("$PROFILE_PREFIX$pkg.direction")]?.let {
            runCatching { ScrollDirection.valueOf(it) }.getOrNull()
        } ?: ScrollDirection.UP
    )

    private fun writeProfile(
        it: androidx.datastore.preferences.core.MutablePreferences,
        pkg: String,
        s: ScrollSettings
    ) {
        it[longPreferencesKey("$PROFILE_PREFIX$pkg.duration")] = s.duration
        it[longPreferencesKey("$PROFILE_PREFIX$pkg.interval")] = s.interval
        it[floatPreferencesKey("$PROFILE_PREFIX$pkg.distanceRatio")] = s.distanceRatio
        it[stringPreferencesKey("$PROFILE_PREFIX$pkg.direction")] = s.direction.name
    }

    private companion object {
        const val PROFILE_PREFIX = "profile."
    }
}

// Top-level DataStore delegate (one instance per process). File name mirrors the legacy prefs
// conceptually; legacy data is migrated from "phantom_scroll_prefs" SharedPreferences.
private val Context.phantomDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "phantom_scroll_datastore",
    produceMigrations = { context ->
        listOf(
            SharedPreferencesMigration(
                context,
                sharedPreferencesName = "phantom_scroll_prefs",
                keysToMigrate = setOf("scroll_duration", "scroll_interval", "scroll_distance_ratio")
            ) { sharedPrefs, currentData ->
                // Map legacy keys into the new global.* keys via the pure mapper.
                val legacy = MigrationMapper.buildGlobalFromLegacy(
                    duration = sharedPrefs.getLong("scroll_duration", ScrollSettings.DEFAULT.duration)
                        .takeIf { sharedPrefs.contains("scroll_duration") },
                    interval = sharedPrefs.getLong("scroll_interval", ScrollSettings.DEFAULT.interval)
                        .takeIf { sharedPrefs.contains("scroll_interval") },
                    ratio = sharedPrefs.getFloat("scroll_distance_ratio", ScrollSettings.DEFAULT.distanceRatio)
                        .takeIf { sharedPrefs.contains("scroll_distance_ratio") }
                )
                currentData.toMutablePreferences().apply {
                    this[longPreferencesKey("global.duration")] = legacy.duration
                    this[longPreferencesKey("global.interval")] = legacy.interval
                    this[floatPreferencesKey("global.distanceRatio")] = legacy.distanceRatio
                }.toPreferences()
            }
        )
    }
)
```

- [ ] **Step 2：验证编译通过**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

> 说明：DataStore 迁移的端到端正确性（新装默认值 / 升级一致性 / 二次启动不重复迁移）属于集成行为，由 Task 13 的验收清单在真机/模拟器上验证，不在此写 JVM 单测（避免引入 Robolectric）。映射逻辑本身已被 Task 6 的 `MigrationMapperTest` 覆盖。

- [ ] **Step 3：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/DataStoreProfileStore.kt
git commit -m "feat(data): add DataStoreProfileStore with SharedPreferencesMigration"
```

---

## Task 9：重构 ScrollOrchestrator —— 读 repository + 委托 FailurePolicy

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt`

> 行为不变：手势生成与分发逻辑原样保留；只把状态读取从 `ScrollConfig` 换成 `SettingsRepository`，把三处重复的"失败计数 + 阈值 + Toast"收敛到 `FailurePolicy`。

- [ ] **Step 1：替换构造参数与状态读取，失败逻辑委托 FailurePolicy**

Replace the entire contents of `app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt` with:

```kotlin
package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.widget.Toast
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.gesture.GestureEngine
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

/**
 * Orchestrator that manages the automated scrolling loop.
 * Reads effective settings from [SettingsRepository] and delegates consecutive-failure
 * accounting to a [FailurePolicy].
 */
class ScrollOrchestrator(
    private val service: AccessibilityService,
    private val repository: SettingsRepository,
    private val scope: CoroutineScope
) {
    private val TAG = "ScrollOrchestrator"
    private val gestureEngine = GestureEngine()
    private val failurePolicy = FailurePolicy(threshold = 3)
    private var loopJob: Job? = null

    fun start() {
        loopJob = scope.launch {
            while (isActive) {
                try {
                    repository.isRunning.first { it }
                    if (!repository.isRunning.value) continue

                    val dm = service.resources.displayMetrics
                    val screenWidth = dm.widthPixels
                    val screenHeight = dm.heightPixels
                    val settings = repository.activeSettings.value

                    val gestureResult = withContext(Dispatchers.Default) {
                        gestureEngine.generateGesturePath(
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            distanceRatio = settings.distanceRatio,
                            durationMs = settings.duration
                        )
                    }

                    val gestureSucceeded = withContext(Dispatchers.Main.immediate) {
                        if (!isActive || !repository.isRunning.value) return@withContext false
                        val timeoutMs = gestureResult.duration + 2000L
                        withTimeoutOrNull(timeoutMs) {
                            suspendCancellableCoroutine<Boolean> { cont ->
                                val stroke = GestureDescription.StrokeDescription(
                                    gestureResult.path, 0L, gestureResult.duration
                                )
                                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                                val dispatched = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(g: GestureDescription?) {
                                        failurePolicy.recordSuccess()
                                        if (cont.isActive) cont.resume(true)
                                    }
                                    override fun onCancelled(g: GestureDescription?) {
                                        if (cont.isActive) cont.resume(false)
                                        handleFailure()
                                    }
                                }, null)

                                if (!dispatched) {
                                    if (cont.isActive) cont.resume(false)
                                    handleFailure()
                                }
                            }
                        } ?: run {
                            handleFailure()
                            false
                        }
                    }

                    if (!gestureSucceeded) {
                        delay(500)
                        continue
                    }

                    val noiseInterval = gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                        .toLong().coerceIn(400, 12000)
                    delay(noiseInterval)

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PhantomLog.e(TAG, "Error in scrolling loop: ${e.message}", e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * Centralized failure handling: delegates counting/decision to [failurePolicy] and performs
     * the auto-pause + user feedback when the threshold is reached.
     */
    private fun handleFailure() {
        when (failurePolicy.recordFailure(repository.isRunning.value)) {
            FailureDecision.Continue, FailureDecision.Ignored -> {
                if (failurePolicy.runsAfterLastSuccess() > 0) {
                    PhantomLog.w(TAG, "Gesture failed. Consecutive: ${failurePolicy.runsAfterLastSuccess()}")
                }
            }
            FailureDecision.AutoPause -> {
                PhantomLog.e(TAG, "Threshold reached → auto-pausing.")
                repository.stopRunning()
                Toast.makeText(service, "⚠️ 连续三次滑动失败，已自动暂停", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
    }
}
```

- [ ] **Step 2：给 SettingsRepository 与 FailurePolicy 补充 orchestrator 依赖的方法**

Step 1 的 orchestrator 引用了 `repository.stopRunning()` 与 `failurePolicy.runsAfterLastSuccess()`，本步补齐它们的定义。

Edit `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt`，在 `resetStats()` 之后追加：

```kotlin
    /** Stops autoscroll (runtime-only). Used by failure auto-pause and external stop. */
    fun stopRunning() { _isRunning.value = false }
    /** Starts autoscroll (runtime-only). */
    fun startRunning() { _isRunning.value = true }
    /** Toggles autoscroll (runtime-only). */
    fun toggleRunning() { _isRunning.update { !it } }
```

Edit `app/src/main/java/com/phantom/scroll/service/FailurePolicy.kt`，在 `recordSuccess()` 之后追加：

```kotlin
    /** Current consecutive failure count since the last success (read-only, for logging). */
    fun runsAfterLastSuccess(): Int = consecutive
```

- [ ] **Step 3：验证编译通过（此时尚未接线到 service，编译即可）**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

> 注：`FailurePolicyTest` 仍应通过（新增的 `runsAfterLastSuccess()` 不破坏现有断言）。

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.FailurePolicyTest"`
Expected: PASS

- [ ] **Step 4：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt app/src/main/java/com/phantom/scroll/service/FailurePolicy.kt
git commit -m "refactor(service): ScrollOrchestrator reads SettingsRepository, delegates failures to FailurePolicy"
```

---

## Task 10：重构 ServiceEventReceiver —— 读 repository + 委托 ScreenStateCoordinator

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/ServiceEventReceiver.kt`

- [ ] **Step 1：替换为基于 repository 与 ScreenStateCoordinator 的实现**

Replace the entire contents of `app/src/main/java/com/phantom/scroll/service/ServiceEventReceiver.kt` with:

```kotlin
package com.phantom.scroll.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.util.PhantomLog

/**
 * Event receiver coordinator for the service.
 * Handles system events (screen off/on) via [ScreenStateCoordinator] and notification
 * commands (toggle/stop). All receivers registered as [Context.RECEIVER_NOT_EXPORTED].
 */
class ServiceEventReceiver(
    private val context: Context,
    private val repository: SettingsRepository,
    private val onStopService: () -> Unit
) {
    private val TAG = "ServiceEventReceiver"
    private val screenState = ScreenStateCoordinator(repository.isRunningMutable)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    PhantomLog.d(TAG, "Screen off → pausing autoscroll.")
                    screenState.onScreenOff()
                }
                Intent.ACTION_USER_PRESENT -> {
                    PhantomLog.d(TAG, "User present (unlocked) → restoring autoscroll state.")
                    screenState.onUserPresent()
                }
            }
        }
    }

    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NotificationHelper.ACTION_TOGGLE -> {
                    repository.toggleRunning()
                    PhantomLog.d(TAG, "Notification toggle → isRunning: ${repository.isRunning.value}")
                }
                NotificationHelper.ACTION_STOP -> {
                    PhantomLog.d(TAG, "Notification stop → disabling service.")
                    repository.stopRunning()
                    onStopService()
                }
            }
        }
    }

    fun start() {
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(screenReceiver, screenFilter)
        }

        val notificationFilter = IntentFilter().apply {
            addAction(NotificationHelper.ACTION_TOGGLE)
            addAction(NotificationHelper.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(notificationActionReceiver, notificationFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(notificationActionReceiver, notificationFilter)
        }
    }

    fun stop() {
        safeUnregister(screenReceiver)
        safeUnregister(notificationActionReceiver)
    }

    private fun safeUnregister(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            PhantomLog.w(TAG, "Receiver already unregistered or not found: ${e.message}")
        }
    }
}
```

- [ ] **Step 2：给 SettingsRepository 暴露可写 isRunning（ScreenStateCoordinator 需要 MutableStateFlow）**

上一步用了 `repository.isRunningMutable`。Edit `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt`，把 `isRunning` 区块改为同时暴露内部可变引用：

将：

```kotlin
    // ---- runtime-only flags (not persisted) ----
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
```

替换为：

```kotlin
    // ---- runtime-only flags (not persisted) ----
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    /** Mutable handle for components that drive isRunning directly (ScreenStateCoordinator). */
    val isRunningMutable: MutableStateFlow<Boolean> get() = _isRunning
```

> 设计说明：`ScreenStateCoordinator` 操作的是同一个底层 `MutableStateFlow`，保证"单一真相源"不破坏。对外只读视图 `isRunning` 仍是 `StateFlow`。

- [ ] **Step 3：更新 ScreenStateCoordinatorTest 不受影响（仍用 MutableStateFlow 构造）**

无需改动；`ScreenStateCoordinator` 构造签名不变。运行确认：

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.ScreenStateCoordinatorTest"`
Expected: PASS

- [ ] **Step 4：验证编译通过**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/ServiceEventReceiver.kt app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt
git commit -m "refactor(service): ServiceEventReceiver reads SettingsRepository, delegates screen state to ScreenStateCoordinator"
```

---

## Task 11：PhantomScrollService 接线 —— 构造并持有 SettingsRepository

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt`

> 仍保留 `config`（Phase 1 桥接，供 Compose `FloatingPanel` 继续用，Task 12 实现桥接）。`isRunning` 通知观察改用 repository；屏幕尺寸写入 repository。

- [ ] **Step 1：替换 PhantomScrollService 内容**

Replace the entire contents of `app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt` with:

```kotlin
package com.phantom.scroll.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.widget.Toast
import com.phantom.scroll.config.ScrollConfig
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
 *
 * NOTE: [config] is a @Deprecated bridge kept only so the Compose FloatingPanel compiles
 * until Phase 2 replaces it with a native overlay. New code must use [repository].
 */
class PhantomScrollService : AccessibilityService() {

    private val TAG = "PhantomScrollService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val repository by lazy { SettingsRepository(DataStoreProfileStore(this), serviceScope) }

    @Deprecated("Bridge to repository; removed in Phase 2. Use repository instead.")
    val config by lazy { ScrollConfig(repository, serviceScope) }

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

        floatingWindowController = FloatingWindowController(this, config, serviceScope, panelStateFlow)
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
```

- [ ] **Step 2：验证编译（此时 ScrollConfig 桥接尚未实现，预期编译失败 —— 这是下一任务的工作）**

Run: `./gradlew assembleDebug`
Expected: FAIL（`ScrollConfig` 构造签名不匹配：当前是 `(context, scope)`，此处传 `(repository, scope)`）

> 这是预期的：Task 12 把 ScrollConfig 改为桥接适配器后即恢复编译。**先做 Task 12 再回来验证编译。**

- [ ] **Step 3：（暂不提交，与 Task 12 一起提交）**

---

## Task 12：ScrollConfig 降级为桥接适配器 + 迁移 ScrollConfigTest

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/config/ScrollConfig.kt`
- Modify: `app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt`

> 桥接职责：把 repository 的 `ScrollSettings` 拆成旧的三个 `MutableStateFlow<Long/Float>` 暴露给 Compose `FloatingPanel`，并双向同步（panel 写 → repository；repository 变 → panel 读）。利用 `StateFlow` 对相等值的去重天然避免回环。`screenWidth/Height/isRunning` 直接委托 repository。`snapshot()` 从 `activeSettings` 派生。

- [ ] **Step 1：重写 ScrollConfig 为桥接适配器**

Replace the entire contents of `app/src/main/java/com/phantom/scroll/config/ScrollConfig.kt` with:

```kotlin
@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package com.phantom.scroll.config

import com.phantom.scroll.data.ScrollDirection
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * @Deprecated bridge that exposes the legacy field-level [MutableStateFlow] API on top of
 * [SettingsRepository], so the Compose FloatingPanel compiles unchanged during Phase 1.
 *
 * REMOVED in Phase 2 when the overlay becomes a native View consuming [SettingsRepository] directly.
 *
 * @param repository the single source of truth.
 * @param scope used for repo<->bridge two-way sync.
 */
@Deprecated("Bridge to SettingsRepository; removed in Phase 2. Use SettingsRepository directly.")
class ScrollConfig(
    private val repository: SettingsRepository,
    private val scope: CoroutineScope
) {
    // Field-level backing flows, seeded from repository then kept in sync.
    private val _scrollDuration = MutableStateFlow(repository.global.value.duration)
    val scrollDuration: MutableStateFlow<Long> = _scrollDuration

    private val _scrollInterval = MutableStateFlow(repository.global.value.interval)
    val scrollInterval: MutableStateFlow<Long> = _scrollInterval

    private val _scrollDistanceRatio = MutableStateFlow(repository.global.value.distanceRatio)
    val scrollDistanceRatio: MutableStateFlow<Float> = _scrollDistanceRatio

    // Delegated directly to repository (same backing instance, no duplication).
    val screenWidth: MutableStateFlow<Int> get() = repository.screenWidthMutable
    val screenHeight: MutableStateFlow<Int> get() = repository.screenHeightMutable
    val isRunning: MutableStateFlow<Boolean> get() = repository.isRunningMutable

    init {
        // repository.global -> backing fields (so external global changes, e.g. presets in P3, reflect)
        repository.global
            .onEach { g ->
                _scrollDuration.value = g.duration
                _scrollInterval.value = g.interval
                _scrollDistanceRatio.value = g.distanceRatio
            }
            .launchIn(scope)

        // backing fields -> repository (debounced; drop first seed). StateFlow dedups equal
        // values, so no feedback loop when repository.global echoes the same numbers back.
        _scrollDuration.drop(1).debounce(500)
            .map { repository.global.value.copy(duration = it) }
            .onEach { scope.launch { repository.updateGlobal(it) } }
            .launchIn(scope)
        _scrollInterval.drop(1).debounce(500)
            .map { repository.global.value.copy(interval = it) }
            .onEach { scope.launch { repository.updateGlobal(it) } }
            .launchIn(scope)
        _scrollDistanceRatio.drop(1).debounce(500)
            .map { repository.global.value.copy(distanceRatio = it) }
            .onEach { scope.launch { repository.updateGlobal(it) } }
            .launchIn(scope)
    }

    /** Snapshot of the effective (active) settings for one swipe loop. */
    fun snapshot(): ConfigSnapshot {
        val a = repository.activeSettings.value
        return ConfigSnapshot(duration = a.duration, interval = a.interval, distanceRatio = a.distanceRatio)
    }
}

/**
 * Legacy snapshot shape retained for compatibility. [direction] lives on [ScrollSettings]
 * (Phase 3); the bridge snapshot stays field-compatible with the old API.
 */
data class ConfigSnapshot(
    val duration: Long,
    val interval: Long,
    val distanceRatio: Float
)
```

- [ ] **Step 2：给 SettingsRepository 暴露 screenWidth/Height 的可写句柄**

桥接里用了 `repository.screenWidthMutable` / `screenHeightMutable`。Edit `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt`，把屏幕尺寸区块改为：

将：

```kotlin
    // dynamic display dimensions (pixels)
    private val _screenWidth = MutableStateFlow(0)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()
    private val _screenHeight = MutableStateFlow(0)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()

    fun setScreenWidth(value: Int) { _screenWidth.value = value }
    fun setScreenHeight(value: Int) { _screenHeight.value = value }
```

替换为：

```kotlin
    // dynamic display dimensions (pixels)
    private val _screenWidth = MutableStateFlow(0)
    val screenWidth: StateFlow<Int> = _screenWidth.asStateFlow()
    val screenWidthMutable: MutableStateFlow<Int> get() = _screenWidth
    private val _screenHeight = MutableStateFlow(0)
    val screenHeight: StateFlow<Int> = _screenHeight.asStateFlow()
    val screenHeightMutable: MutableStateFlow<Int> get() = _screenHeight

    fun setScreenWidth(value: Int) { _screenWidth.value = value }
    fun setScreenHeight(value: Int) { _screenHeight.value = value }
```

- [ ] **Step 3：迁移 ScrollConfigTest —— 仅保留适配器逻辑断言**

现有 `ScrollConfigTest`（验证旧 SharedPreferences 读写 + snapshot）的接口已不存在。替换为仅验证桥接适配器行为的精简版（其余断言已在 `SettingsRepositoryTest` 覆盖）。

Replace the entire contents of `app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt` with:

```kotlin
package com.phantom.scroll.config

import com.phantom.scroll.data.FakeProfileStore
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScrollConfigTest {

    @Suppress("DEPRECATION")
    private fun makeConfig(global: ScrollSettings, scope: kotlinx.coroutines.test.TestScope): ScrollConfig {
        val store = FakeProfileStore().apply { this.global = global }
        val repo = SettingsRepository(store, scope)
        return ScrollConfig(repo, scope)
    }

    @Test
    fun bridge_seeds_field_flows_from_repository_global() = runTest {
        advanceUntilIdle()
        val config = makeConfig(ScrollSettings(duration = 650L, interval = 2500L, distanceRatio = 0.8f), this)
        advanceUntilIdle()
        assertEquals(650L, config.scrollDuration.value)
        assertEquals(2500L, config.scrollInterval.value)
        assertEquals(0.8f, config.scrollDistanceRatio.value)
    }

    @Test
    fun snapshot_reflects_active_settings() = runTest {
        advanceUntilIdle()
        val config = makeConfig(ScrollSettings(duration = 700L, interval = 3000L, distanceRatio = 0.55f), this)
        advanceUntilIdle()
        val snap = config.snapshot()
        assertEquals(700L, snap.duration)
        assertEquals(3000L, snap.interval)
        assertEquals(0.55f, snap.distanceRatio)
    }
}
```

> 说明：测试用 `runTest` 提供的 `TestScope` 作为 repository 与桥接的共用 scope，虚拟时间由 `advanceUntilIdle()` 推进；`@Suppress("DEPRECATION")` 抑制使用桥接 `ScrollConfig` 的弃用警告。

- [ ] **Step 4：验证全部单测通过**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（含新迁移的 ScrollConfigTest）

- [ ] **Step 5：验证整体编译（含 Task 11 的 PhantomScrollService）**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6：Commit（Task 11 + Task 12 一起）**

```bash
git add app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt app/src/main/java/com/phantom/scroll/config/ScrollConfig.kt app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt
git commit -m "refactor: wire SettingsRepository into service; demote ScrollConfig to deprecated bridge"
```

---

## Task 13：Phase 1 验收

**Files:** 无代码改动；记录验收结果。

- [ ] **Step 1：全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，且报告包含并全部通过：
- `ScrollSettingsTest`
- `MigrationMapperTest`
- `SettingsRepositoryTest`
- `FailurePolicyTest`
- `ScreenStateCoordinatorTest`
- `ScrollConfigTest`（迁移后）
- `GestureEngineTest`（既有）

- [ ] **Step 2：Release 构建（确认 R8/混淆仍通过）**

Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL`（确认 DataStore / 新 data 类不破坏混淆）

- [ ] **Step 3：行为一致性手工验收（真机/模拟器）**

逐项确认与 V1 行为完全一致：
- [ ] App 启动正常，权限页（Compose `MainScreen`）正常显示与刷新。
- [ ] 授予权限后，悬浮窗手柄出现，点击展开面板。
- [ ] 拖动"速度/间隔/距离"三个 Slider，滑动行为实时变化。
- [ ] 点击"开始/暂停"，自动滑动启停，通知栏状态同步。
- [ ] 熄屏自动暂停；亮屏解锁后按熄屏前状态恢复（熄屏前若已暂停，亮屏不自动恢复）。
- [ ] 连续三次手势失败 → 自动暂停 + Toast。
- [ ] 杀进程后重启服务，duration/interval/distanceRatio 三项配置保留。

- [ ] **Step 4：DataStore 迁移三场景验证**

- [ ] **新安装**：卸载后全新安装，进入面板确认三项参数为默认值（速度 500ms / 间隔 2.0s / 距离 75%）。
- [ ] **版本升级**：先安装 V1（带旧 SharedPreferences 数据）调过参数，再安装本 Phase 1 构建，确认三项参数与 V1 完全一致、无丢失。
- [ ] **二次启动**：升级并首次启动后，在面板修改任一参数 → 完全杀掉 App → 再次启动，确认：(a) 新值已持久化；(b) 未发生重复迁移覆盖（值是上次修改后的，而非被旧 SharedPrefs 重新覆盖）。

- [ ] **Step 5：把验收结果记入提交（可选 README 备注）并在分支上打 Phase 1 闸门标记**

```bash
git commit --allow-empty -m "chore: Phase 1 foundation gate passed (behavior parity, migration verified)"
```

---

## Phase 1 完成判据

1. `./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleRelease` 均 `BUILD SUCCESSFUL`。
2. 上述全部单测通过；核心循环（`FailurePolicy`）、锁屏状态机（`ScreenStateCoordinator`）、仓库（`SettingsRepository`）、迁移（`MigrationMapper`）均有纯逻辑单测覆盖。
3. 行为一致性手工验收全部通过（对外行为零变化）。
4. DataStore 三场景迁移验证通过。

完成后即可撰写 **Phase 2 计划**（悬浮窗原生 View 重写）。
