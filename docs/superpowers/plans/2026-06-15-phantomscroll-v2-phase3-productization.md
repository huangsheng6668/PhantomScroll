# PhantomScroll V2 · Phase 3 实施计划：产品化功能（预设 / 统计 / 方向 / 按 App 记忆）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Phase 1 仓库 + Phase 2 原生悬浮窗之上，上线四项产品化功能：① 场景预设（小说 / 漫画 / 自定义）② 运行统计（翻页次数 + 累计时长，可重置）③ 滚动方向切换（↑/↓）④ 按 App 记忆配置（开关可控、自动切换、可忘记）。全部建在已有 `SettingsRepository` 与 `FloatingOverlayView` 上，**不引入新依赖**，不改持久化 schema 的存储形态（direction 字段 Phase 1 已落库）。

**Architecture:** 新增三个纯逻辑单元并 TDD —— `gesture` 层把 `calculateGesturePoints` 加 `direction: ScrollDirection` 参数（纯数学，镜像 Y 逻辑）；`data` 层在 `SettingsRepository` 增 `applyPreset` / `updateActive` / `deleteActiveProfile`（= `deleteProfile(currentPackage)`）与 `selectedPreset` 派生 `StateFlow`，`Preset.kt` 填充内置常量；`service` 层新增 `PerAppDetector`（把"事件 → 当前包"的去重 / denylist / 防抖抽成纯函数），`PhantomScrollService.onAccessibilityEvent` 调用它驱动 `repository.setCurrentPackage`。UI 层在 `overlay_panel.xml` 追加预设 Chip 行 / 方向按钮 / 统计行 / per-app 开关 + 标签 / 重置入口，`FloatingOverlayView` 收集新 Flow 并命令式刷新；`ScrollOrchestrator` 增透传 `direction` 并在手势成功后调 `incrementStats`。

**Tech Stack:** Kotlin, kotlinx.coroutines (Flow/StateFlow), Android View 系统, Google Material Components（已有 `material:1.12.0`）, JUnit 4。

**Spec:** [docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md](../specs/2026-06-14-phantomscroll-v2-optimization-design.md) §Phase 3

> **本计划范围：仅 Phase 3。** Phase 4（性能收尾 / Baseline Profile）有独立计划，在其闸门通过后撰写。
>
> **提交约定：** 每个任务末尾的 `git commit` 均需在提交信息末尾追加一行 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`（下文各 commit 命令示例中不再重复写出该行）。
>
> **测试命令约定：** 跑单个测试类 `./gradlew :app:testDebugUnitTest --tests "<全限定类名>"`；全量单测 `./gradlew :app:testDebugUnitTest`；编译校验 `./gradlew assembleDebug`；混淆构建 `./gradlew assembleRelease`。所有命令预期最终输出 `BUILD SUCCESSFUL`。
>
> **与 TDD 的关系（沿用 Phase 1/2 策略）：** Phase 3 把所有"可纯 JVM 测试的逻辑"抽成纯函数/纯对象并 TDD（`calculateGesturePoints` 方向分支、`PerAppDetector`、`SettingsRepository` 新方法、`Preset` 派生）。View/布局/资源由 `assembleDebug`（编译）+ Task 11 的对齐验收清单（真机/模拟器手工）保证。
>
> **现状基线（Phase 2 闸门后）：** 单测 34 个（SettingsRepositoryTest 11、FailurePolicyTest 3、ScreenStateCoordinatorTest 4、GestureEngineTest 3、ScrollSettingsTest 1、MigrationMapperTest 3、ProfileKeyParsingTest 3、OverlayGeometryTest 6）。Phase 3 收尾后预期单测总数在下方各 Task 末尾给出。
>
> **关键现状说明（决定本计划写法）：**
> - `SettingsRepository` 已有 `global / profiles / perAppEnabled / currentPackage / activeSettings / stats / isRunning` 及写入方法（`updateGlobal / upsertProfile / deleteProfile / setCurrentPackage / setPerAppEnabled / incrementStats / resetStats / toggleRunning`）。Phase 3 **只增量加**方法，不改已有签名。
> - `ScrollSettings` 已含 `direction: ScrollDirection = ScrollDirection.UP` 字段，`DataStoreProfileStore` 已按 `global.direction` / `profile.<pkg>.direction` 落库 —— 方向切换**无需改持久化**。
> - `GestureEngine.calculateGesturePoints(...)` 是 `companion object` 纯函数，签名 `(screenWidth, screenHeight, distanceRatio, durationMs, random)`；实例方法 `generateGesturePath(...)` 是它的薄封装。本计划给两者都加 `direction` 参数。
> - `ScrollOrchestrator` 已读 `activeSettings.value` 生成手势并在 `onCompleted` 调 `failurePolicy.recordSuccess()`，但现状调用 `generateGesturePath` 时**未透传 `direction`**（走默认 UP）。本计划（Task 6）补传 `direction = settings.direction`，并在成功分支补一次 `incrementStats`。
> - `FloatingOverlayView` 已收集 `global / isRunning / screenWidth / screenHeight / panelState`；本计划补收集 `stats / perAppEnabled / currentPackage / selectedPreset`（后两者 Phase 3 新增的 repo 暴露）。
> - `PhantomScrollService.onAccessibilityEvent` 当前是空 no-op 注释；Phase 3 接入 per-app 检测。
> - 持久化 schema 不变：`DataStoreProfileStore` / `FakeProfileStore` / `ProfileStore` 接口无需改动。

---

## 文件结构（Phase 3 新增/修改）

**新增：**

| 文件 | 职责 |
|------|------|
| `service/PerAppDetector.kt` | 纯逻辑：事件 → 当前包（去重 / denylist / 自身包名过滤） |
| `data/PresetRegistry.kt` | 纯逻辑：内置预设常量 + `selectedPreset` 派生（global 命中哪个内置预设，否则 `自定义`） |
| 测试 `service/PerAppDetectorTest.kt` | denylist / 去重 / 自身包名过滤 |
| 测试 `data/PresetRegistryTest.kt` | 命中小说 / 命中漫画 / 回落自定义 |

**修改：**

| 文件 | 改动 |
|------|------|
| `gesture/GestureEngine.kt` | `calculateGesturePoints` + `generateGesturePath` 加 `direction` 参数，DOWN 镜像 Y |
| `data/Preset.kt` | 填充内置预设常量（小说 / 漫画），保留 `自定义` 为派生态（非固定 Preset） |
| `data/SettingsRepository.kt` | 增 `applyPreset(Preset)` / `updateActive(ScrollSettings)` / `forgetActiveProfile()` / 暴露 `selectedPreset: StateFlow<PresetSelection>` |
| `service/PhantomScrollService.kt` | `onAccessibilityEvent` 接 `PerAppDetector` → `setCurrentPackage` |
| `service/ScrollOrchestrator.kt` | `generateGesturePath` 透传 `direction = settings.direction`；手势 `onCompleted` 后调 `repository.incrementStats(1, noiseInterval)` |
| `ui/overlay/FloatingOverlayView.kt` | 收集新 Flow + 绑定预设 Chip / 方向按钮 / 统计行 / per-app 开关 + 标签 / 重置点击 |
| `res/layout/overlay_panel.xml` | 追加：预设 Chip 行（在标题下）、方向按钮（标题行右侧或单独行）、统计行、per-app 开关 + 标签行 |
| `res/values/strings.xml`（如不存在则新建） | 集中面板文案（可选，保持硬编码亦可；本计划保持硬编码以最小改动） |

**删除：** 无。

> **不改动：** `MainActivity` / `MainScreen`（Compose 权限页）、`NotificationHelper`（统计只在面板展示，不进通知）、`DataStoreProfileStore` / `ProfileStore` / `FakeProfileStore`（schema 不变）、`Theme` / `colors.xml` / 现有 drawable。

---

## Task 1：GestureEngine 方向参数（TDD）

**Files:**
- Modify: `app/src/test/java/com/phantom/scroll/gesture/GestureEngineTest.kt`
- Modify: `app/src/main/java/com/phantom/scroll/gesture/GestureEngine.kt`

> 现状 `calculateGesturePoints` 固定"从下往上"滑：`startY` 近 `safeBottom`、`endY = startY - distance`（在上）。DOWN 方向把 Y 逻辑镜像：`startY` 近 `safeTop`、`endY = startY + distance`（在下），控制点 Y 仍取 `startY/endY` 中点。纯 JVM，直接扩 `GestureEngineTest`。

- [ ] **Step 1：扩测试（先加方向断言）**

在 `GestureEngineTest.kt` 末尾追加两个测试方法（同文件、同 package，无需新 import 之外的改动 —— `ScrollDirection` 在 `com.phantom.scroll.data`，需补 import）：

```kotlin
import com.phantom.scroll.data.ScrollDirection
```

```kotlin
    @Test
    fun direction_up_swipes_from_bottom_to_top() {
        val screenWidth = 1080
        val screenHeight = 2400
        val random = Random(42)
        val points = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = 0.75f,
            durationMs = 500L,
            random = random,
            direction = ScrollDirection.UP
        )
        assertTrue("UP: endY (${points.endY}) must be above startY (${points.startY})", points.endY < points.startY)
    }

    @Test
    fun direction_down_swipes_from_top_to_bottom() {
        val screenWidth = 1080
        val screenHeight = 2400
        val random = Random(42)
        val points = GestureEngine.calculateGesturePoints(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            distanceRatio = 0.75f,
            durationMs = 500L,
            random = random,
            direction = ScrollDirection.DOWN
        )
        // DOWN mirrors UP: start near top, end below start
        assertTrue("DOWN: startY (${points.startY}) must be near top (safeTop=${screenHeight * 0.15f})",
            points.startY < screenHeight * 0.5f)
        assertTrue("DOWN: endY (${points.endY}) must be below startY (${points.startY})", points.endY > points.startY)
    }
```

> **注意（现有测试兼容）：** 现有 3 个测试调用的是不带 `direction` 的旧签名。为不破坏它们，本任务给 `direction` 设默认值 `ScrollDirection.UP`（见 Step 2），现有调用无需改动即可继续表达 UP 行为。

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.gesture.GestureEngineTest"`
Expected: 编译失败 —— `calculateGesturePoints` 无 `direction` 参数。

- [ ] **Step 3：实现方向分支**

修改 `app/src/main/java/com/phantom/scroll/gesture/GestureEngine.kt`：

(a) 顶部补 import：
```kotlin
import com.phantom.scroll.data.ScrollDirection
```

(b) `companion object` 内 `calculateGesturePoints` 签名加 `direction: ScrollDirection = ScrollDirection.UP` 参数（默认 UP 保持现有调用兼容），并把"3. Start/end Y"段替换为方向分支：

```kotlin
        fun calculateGesturePoints(
            screenWidth: Int,
            screenHeight: Int,
            distanceRatio: Float,
            durationMs: Long,
            random: Random,
            direction: ScrollDirection = ScrollDirection.UP
        ): GesturePoints {
            // 1. Define screen safe zone (avoid status bar + navigation bar)
            val safeTop = screenHeight * 0.15f
            val safeBottom = screenHeight * 0.85f
            val safeHeight = safeBottom - safeTop

            fun addNoise(base: Float, ratio: Float): Float {
                val factor = (random.nextGaussian().toFloat() * ratio).coerceIn(-ratio * 2, ratio * 2)
                return base * (1f + factor)
            }

            // 2. Scroll distance with Bio-Noise (±8%)
            val baselineDistance = safeHeight * distanceRatio
            val noisyDistance = addNoise(baselineDistance, 0.08f)
                .coerceIn(safeHeight * 0.2f, safeHeight * 0.95f)

            // 3. Start/end Y: direction-dependent.
            //    UP   (default): finger moves bottom→top, content scrolls up (next page).
            //    DOWN          : finger moves top→bottom, content scrolls down (previous page).
            val startY: Float
            val endY: Float
            if (direction == ScrollDirection.DOWN) {
                startY = safeTop + addNoise(screenHeight * 0.03f, 0.1f)
                endY = (startY + noisyDistance).coerceAtMost(safeBottom)
            } else {
                startY = safeBottom - addNoise(screenHeight * 0.03f, 0.1f)
                endY = (startY - noisyDistance).coerceAtLeast(safeTop)
            }

            // 4. X center with micro thumb-landing deviation
            val centerX = screenWidth * 0.5f
            val startX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)
            val endX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.02f)

            // 5. Quadratic Bezier control point P1
            val controlX = centerX + random.nextGaussian().toFloat() * (screenWidth * 0.05f)
            val controlY = (startY + endY) * 0.5f

            // 6. Duration with Bio-Noise (±7%)
            val actualDuration = addNoise(durationMs.toFloat(), 0.07f)
                .toLong().coerceIn(200, 1500)

            return GesturePoints(startX, startY, controlX, controlY, endX, endY, actualDuration)
        }
```

(c) 实例方法 `generateGesturePath` 也加 `direction` 参数并透传（默认 UP）：
```kotlin
    fun generateGesturePath(
        screenWidth: Int,
        screenHeight: Int,
        distanceRatio: Float,
        durationMs: Long,
        direction: ScrollDirection = ScrollDirection.UP
    ): GestureResult {
        val points = calculateGesturePoints(screenWidth, screenHeight, distanceRatio, durationMs, random, direction)
        reusablePath.reset()
        reusablePath.moveTo(points.startX, points.startY)
        reusablePath.quadTo(points.controlX, points.controlY, points.endX, points.endY)
        return GestureResult(path = reusablePath, duration = points.duration)
    }
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.gesture.GestureEngineTest"`
Expected: PASS（5 用例：原 3 + 方向 2）

- [ ] **Step 5：验证全量单测仍绿（ScrollOrchestrator 此时还未传 direction，靠默认 UP 保持行为）**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（34 + 2 = 36）

- [ ] **Step 6：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/gesture/GestureEngine.kt app/src/test/java/com/phantom/scroll/gesture/GestureEngineTest.kt
git commit -m "feat(gesture): add direction param to calculateGesturePoints (UP/DOWN mirror Y)"
```

---

## Task 2：Preset 内置常量 + PresetRegistry 派生（TDD）

**Files:**
- Create: `app/src/test/java/com/phantom/scroll/data/PresetRegistryTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/data/PresetRegistry.kt`
- Modify: `app/src/main/java/com/phantom/scroll/data/Preset.kt`

> spec §3.1：内置"小说 / 漫画"两个固定预设，"自定义"= global 命中内置预设则显示其名，否则"自定义"。把"global → 选中的预设名"做成纯函数 `PresetRegistry.selectionFor(settings)`，UI 与 repo 都可复用。

- [ ] **Step 1：写失败测试**

Create `app/src/test/java/com/phantom/scroll/data/PresetRegistryTest.kt`:
```kotlin
package com.phantom.scroll.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PresetRegistryTest {

    @Test
    fun selection_novel_when_global_matches_novel() {
        val sel = PresetRegistry.selectionFor(Preset.NOVEL.settings)
        assertEquals(PresetSelection.BuiltIn(Preset.NOVEL), sel)
    }

    @Test
    fun selection_comic_when_global_matches_comic() {
        val sel = PresetRegistry.selectionFor(Preset.COMIC.settings)
        assertEquals(PresetSelection.BuiltIn(Preset.COMIC), sel)
    }

    @Test
    fun selection_custom_when_global_matches_no_builtin() {
        val arbitrary = ScrollSettings(duration = 600L, interval = 2500L, distanceRatio = 0.7f)
        assertEquals(PresetSelection.Custom, PresetRegistry.selectionFor(arbitrary))
    }

    @Test
    fun builtin_presets_have_documented_values() {
        // spec §3.1: 小说 duration 700 / interval 4000 / ratio 0.55 / UP
        assertEquals(700L, Preset.NOVEL.settings.duration)
        assertEquals(4000L, Preset.NOVEL.settings.interval)
        assertEquals(0.55f, Preset.NOVEL.settings.distanceRatio)
        assertEquals(ScrollDirection.UP, Preset.NOVEL.settings.direction)
        // spec §3.1: 漫画 duration 500 / interval 3000 / ratio 0.85 / UP
        assertEquals(500L, Preset.COMIC.settings.duration)
        assertEquals(3000L, Preset.COMIC.settings.interval)
        assertEquals(0.85f, Preset.COMIC.settings.distanceRatio)
        assertEquals(ScrollDirection.UP, Preset.COMIC.settings.direction)
    }

    @Test
    fun custom_display_name_is_stable() {
        assertEquals("自定义", PresetSelection.Custom.displayName)
        assertEquals("小说", PresetSelection.BuiltIn(Preset.NOVEL).displayName)
        assertEquals("漫画", PresetSelection.BuiltIn(Preset.COMIC).displayName)
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.PresetRegistryTest"`
Expected: FAIL（`PresetRegistry` / `PresetSelection` 未解析，`Preset.NOVEL/COMIC` 未定义）

- [ ] **Step 3：填充 Preset 内置常量**

Replace the ENTIRE contents of `app/src/main/java/com/phantom/scroll/data/Preset.kt` with:
```kotlin
package com.phantom.scroll.data

/**
 * Named scene preset. [NOVEL] / [COMIC] are fixed built-ins (spec §3.1);
 * "自定义" is not a [Preset] but the derived [PresetSelection.Custom] state
 * (any global value that matches no built-in).
 */
data class Preset(
    val name: String,
    val settings: ScrollSettings
) {
    companion object {
        /** 小说：长间隔、短距离，适合文字连续阅读。 */
        val NOVEL = Preset(
            name = "小说",
            settings = ScrollSettings(duration = 700L, interval = 4000L, distanceRatio = 0.55f, direction = ScrollDirection.UP)
        )
        /** 漫画：中间隔、大距离，一屏一翻。 */
        val COMIC = Preset(
            name = "漫画",
            settings = ScrollSettings(duration = 500L, interval = 3000L, distanceRatio = 0.85f, direction = ScrollDirection.UP)
        )
        /** All fixed built-ins, in display order. */
        val BUILT_INS: List<Preset> = listOf(NOVEL, COMIC)
    }
}

/**
 * Which preset the current [ScrollSettings] corresponds to.
 * - [BuiltIn]: global exactly matches a built-in [Preset].
 * - [Custom]: global matches no built-in (user-tuned).
 */
sealed interface PresetSelection {
    val displayName: String

    data class BuiltIn(val preset: Preset) : PresetSelection {
        override val displayName: String get() = preset.name
    }

    data object Custom : PresetSelection {
        override val displayName: String get() = "自定义"
    }
}
```

- [ ] **Step 4：实现 PresetRegistry**

Create `app/src/main/java/com/phantom/scroll/data/PresetRegistry.kt`:
```kotlin
package com.phantom.scroll.data

/**
 * Pure: derives the [PresetSelection] for a given [ScrollSettings].
 * Returns [PresetSelection.BuiltIn] when [settings] exactly matches a built-in [Preset];
 * otherwise [PresetSelection.Custom]. No Android / coroutine dependencies → JVM-testable.
 */
object PresetRegistry {
    fun selectionFor(settings: ScrollSettings): PresetSelection =
        Preset.BUILT_INS.firstOrNull { it.settings == settings }?.let { PresetSelection.BuiltIn(it) }
            ?: PresetSelection.Custom
}
```

- [ ] **Step 5：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.PresetRegistryTest"`
Expected: PASS（5 用例）

- [ ] **Step 6：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/Preset.kt app/src/main/java/com/phantom/scroll/data/PresetRegistry.kt app/src/test/java/com/phantom/scroll/data/PresetRegistryTest.kt
git commit -m "feat(data): add built-in presets (novel/comic) + pure PresetRegistry selection"
```

---

## Task 3：SettingsRepository 增 applyPreset / updateActive / forgetActiveProfile / selectedPreset（TDD）

**Files:**
- Modify: `app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt`
- Modify: `app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt`

> 新增四个语义，全部建在已有 `_global` / `_profiles` / `_currentPackage` / `activeSettings` 之上，**不改持久化接口**：
> - `applyPreset(preset)`：应用预设到当前活动配置，调用 `updateActive(preset.settings)` 写入（spec §3.1）。
> - `updateActive(settings)`：per-app 开启且有 currentPackage 时写该包的 profile（动态创建），否则写 global。对应 spec §3.4"首次调整自动创建 profile"。
> - `forgetActiveProfile()`：per-app 开启且有 currentPackage 时删该包 profile（回落 global）。
> - `selectedPreset: StateFlow<PresetSelection>`：派生自 `activeSettings`（**注意：需派生自 activeSettings 而非 global** —— 预设选择态需反映当前面板上展示的实际活动值，如在 app 内修改配置，预设需自动更新为"自定义"或对应预设，避免界面与指示灯不一致）。

- [ ] **Step 1：扩测试**

在 `SettingsRepositoryTest.kt` 末尾追加（已 import `ScrollSettings` 等；需补 `import com.phantom.scroll.data.Preset` —— 同 package 无需 import）：

```kotlin
    @Test
    fun applyPreset_writes_preset_settings_to_activeSettings() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.applyPreset(Preset.NOVEL)
        advanceUntilIdle()
        assertEquals(Preset.NOVEL.settings, repo.activeSettings.value)
        assertEquals(Preset.NOVEL.settings, store.global)
    }

    @Test
    fun applyPreset_writes_to_profile_when_perApp_on_and_pkg_known() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        repo.applyPreset(Preset.NOVEL)
        advanceUntilIdle()
        assertEquals(Preset.NOVEL.settings, store.profiles["com.example.novel"]?.settings)
        // global untouched
        assertEquals(ScrollSettings.DEFAULT, repo.global.value)
    }

    @Test
    fun selectedPreset_tracks_activeSettings() = runTest {
        val repo = repoWith(FakeProfileStore())
        assertEquals(PresetSelection.Custom, repo.selectedPreset.value)
        repo.applyPreset(Preset.COMIC)
        advanceUntilIdle()
        assertEquals(PresetSelection.BuiltIn(Preset.COMIC), repo.selectedPreset.value)
    }

    @Test
    fun updateActive_writes_profile_when_perApp_on_and_pkg_known() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        val s = ScrollSettings(duration = 600L, interval = 2000L, distanceRatio = 0.6f)
        repo.updateActive(s)
        advanceUntilIdle()
        assertEquals(s, store.profiles["com.example.novel"]?.settings)
        // global untouched
        assertEquals(ScrollSettings.DEFAULT, repo.global.value)
    }

    @Test
    fun updateActive_writes_global_when_perApp_off() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        // perApp default off, no currentPackage
        val s = ScrollSettings(duration = 650L, interval = 2100L, distanceRatio = 0.66f)
        repo.updateActive(s)
        advanceUntilIdle()
        assertEquals(s, repo.global.value)
        assertEquals(s, store.global)
        assertTrue(store.profiles.isEmpty())
    }

    @Test
    fun forgetActiveProfile_removes_current_pkg_profile() = runTest {
        val store = FakeProfileStore().apply {
            profiles["com.example.novel"] = AppProfile("com.example.novel", ScrollSettings.DEFAULT)
        }
        val repo = repoWith(store)
        repo.setPerAppEnabled(true)
        repo.setCurrentPackage("com.example.novel")
        advanceUntilIdle()
        repo.forgetActiveProfile()
        advanceUntilIdle()
        assertNull(store.profiles["com.example.novel"])
    }

    @Test
    fun forgetActiveProfile_no_op_when_no_current_pkg() = runTest {
        val store = FakeProfileStore()
        val repo = repoWith(store)
        repo.forgetActiveProfile() // currentPackage null → no-op, no crash
        advanceUntilIdle()
        assertTrue(store.profiles.isEmpty())
    }
```

> 注意：`updateActive_writes_global_when_perApp_off` 用到 `assertTrue`，文件顶部已 import `org.junit.Assert.assertTrue`（见现有 `setPerAppEnabled_persists` 用例）。若未 import 则补。

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.SettingsRepositoryTest"`
Expected: 编译失败 —— `applyPreset` / `selectedPreset` / `updateActive` / `forgetActiveProfile` 未解析。

- [ ] **Step 3：实现新方法**

在 `SettingsRepository.kt`：

(a) `activeSettings` 定义之后、`init {` 之前，加 `selectedPreset` 派生（用 `scope`/`Eagerly`，与 `activeSettings` 同模式）：
```kotlin
    /** Which built-in preset (or Custom) the ACTIVE settings currently match. Derived from
     *  [activeSettings] (not [global]) so the highlight reflects what the panel is actually
     *  editing — e.g. when per-app is on and the current app has a profile, the chip reflects
     *  that profile, and editing it flips to "自定义" (spec §3.1/§3.4). */
    val selectedPreset: StateFlow<PresetSelection> =
        activeSettings.map { PresetRegistry.selectionFor(it) }
            .stateIn(scope, SharingStarted.Eagerly, PresetRegistry.selectionFor(activeSettings.value))
```
> 需补 import：`import kotlinx.coroutines.flow.map`、`import com.phantom.scroll.data.PresetSelection`（同 package 可省）。本文件已 import `stateIn`/`SharingStarted`。

(b) 在 `// ---- mutations ----` 区段，`updateGlobal` 附近追加：
```kotlin
    /** Applies a built-in preset to the ACTIVE target (spec §3.1): the current package's profile
     *  when per-app is on and a package is known, otherwise the global defaults. Delegates to
     *  [updateActive] so it shares the same per-app semantics as Slider edits. */
    suspend fun applyPreset(preset: Preset) { updateActive(preset.settings) }

    /**
     * Writes [settings] to the active target: the current package's profile when per-app is on
     * and a current package is known (dynamically creating the profile), otherwise the global
     * defaults (spec §3.4 "首次调整自动创建 profile"). Persistence is debounced via collectors.
     */
    suspend fun updateActive(settings: ScrollSettings) {
        val pkg = _currentPackage.value
        if (_perAppEnabled.value && pkg != null) {
            upsertProfile(pkg, settings)
        } else {
            _global.value = settings
        }
    }

    /** Forgets the current package's profile so activeSettings falls back to global. No-op if no
     *  current package. (spec §3.4 "忘记当前 App 配置") */
    suspend fun forgetActiveProfile() {
        val pkg = _currentPackage.value ?: return
        deleteProfile(pkg)
    }
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.data.SettingsRepositoryTest"`
Expected: PASS（11 原 + 7 新 = 18）

- [ ] **Step 5：验证全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（36 + PresetRegistry 5 + SettingsRepository 新 7 = 48）

- [ ] **Step 6：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt app/src/test/java/com/phantom/scroll/data/SettingsRepositoryTest.kt
git commit -m "feat(data): SettingsRepository.applyPreset/updateActive/forgetActiveProfile + selectedPreset"
```

---

## Task 4：PerAppDetector 纯逻辑（TDD）

**Files:**
- Create: `app/src/test/java/com/phantom/scroll/service/PerAppDetectorTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/service/PerAppDetector.kt`

> spec §3.4：把 `onAccessibilityEvent` 里的过滤逻辑抽成纯函数 —— 关闭时零开销早退、去重（与 currentPackage 相同则忽略）、denylist（SystemUI / 输入法 / Launcher / 自身包名）、300ms 防抖。返回 `ShouldHandle(pkg) | Skip`。无 Android 依赖（包名是 String，时间是 Long），纯 JVM 可测。

- [ ] **Step 1：写失败测试**

Create `app/src/test/java/com/phantom/scroll/service/PerAppDetectorTest.kt`:
```kotlin
package com.phantom.scroll.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppDetectorTest {

    private val ownPackage = "com.phantom.scroll"
    private val denylist = setOf("com.android.systemui", "com.example.launcher", "com.example.ime")
    private val detector = PerAppDetector(ownPackage = ownPackage, denylist = denylist)

    @Test
    fun skips_when_perApp_disabled() {
        // even a fresh app is ignored when the feature is off (zero-overhead path)
        val r = detector.evaluate(
            eventPackage = "com.example.novel",
            currentPackage = null,
            perAppEnabled = false,
            nowMs = 1000L
        )
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun skips_when_package_unchanged() {
        val r = detector.evaluate(
            eventPackage = "com.example.novel",
            currentPackage = "com.example.novel",
            perAppEnabled = true,
            nowMs = 1000L
        )
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun skips_system_ui_and_launcher_and_ime() {
        denylist.forEach { pkg ->
            val r = detector.evaluate(pkg, currentPackage = null, perAppEnabled = true, nowMs = 1000L)
            assertTrue("denylisted pkg $pkg should be skipped", r is PerAppDecision.Skip)
        }
    }

    @Test
    fun skips_own_package() {
        val r = detector.evaluate(ownPackage, currentPackage = null, perAppEnabled = true, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Skip)
    }

    @Test
    fun handles_new_user_app() {
        val r = detector.evaluate("com.example.novel", currentPackage = null, perAppEnabled = true, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Handle)
        assertEquals("com.example.novel", (r as PerAppDecision.Handle).packageToSet)
    }

    @Test
    fun debounces_rapid_switches_within_window() {
        // first switch at t=1000 is handled
        val first = detector.evaluate("com.example.a", currentPackage = "com.example.start", perAppEnabled = true, nowMs = 1000L)
        assertTrue(first is PerAppDecision.Handle)
        // another switch 100ms later (within 300ms window) is skipped
        val second = detector.evaluate("com.example.b", currentPackage = "com.example.a", perAppEnabled = true, nowMs = 1100L)
        assertTrue("within debounce window should skip", second is PerAppDecision.Skip)
    }

    @Test
    fun handles_switch_after_debounce_window_elapsed() {
        detector.evaluate("com.example.a", currentPackage = "com.example.start", perAppEnabled = true, nowMs = 1000L)
        val later = detector.evaluate("com.example.b", currentPackage = "com.example.a", perAppEnabled = true, nowMs = 1400L)
        assertTrue("after 300ms window should handle", later is PerAppDecision.Handle)
    }

    @Test
    fun handles_null_or_blank_event_package_safely() {
        val r = detector.evaluate("", currentPackage = null, perAppEnabled = true, nowMs = 1000L)
        assertTrue(r is PerAppDecision.Skip)
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.PerAppDetectorTest"`
Expected: FAIL（`PerAppDetector` / `PerAppDecision` 未解析）

- [ ] **Step 3：实现 PerAppDetector**

Create `app/src/main/java/com/phantom/scroll/service/PerAppDetector.kt`:
```kotlin
package com.phantom.scroll.service

/** Outcome of [PerAppDetector.evaluate]. */
sealed interface PerAppDecision {
    /** Update currentPackage to [packageToSet]. */
    data class Handle(val packageToSet: String) : PerAppDecision
    /** Ignore this event (feature off / unchanged / denylisted / debounced / blank). */
    data object Skip : PerAppDecision
}

/**
 * Pure logic that decides whether an accessibility event's package name should update the
 * repository's current package (spec §3.4). Extracted from PhantomScrollService.onAccessibilityEvent
 * so the filtering (denylist / dedup / debounce) is unit-testable without Android.
 *
 * Stateful ONLY for the debounce window timestamp; reset by recreating the instance
 * (the service owns one instance for its lifetime).
 *
 * @param ownPackage the app's own package name (always filtered).
 * @param denylist package names to always ignore (SystemUI / Launcher / IME …).
 * @param debounceMs minimum interval between two accepted switches. Default 300ms (spec §3.4).
 */
class PerAppDetector(
    private val ownPackage: String,
    private val denylist: Set<String>,
    private val debounceMs: Long = 300L
) {
    private var lastHandledAtMs: Long = 0L

    fun evaluate(
        eventPackage: String?,
        currentPackage: String?,
        perAppEnabled: Boolean,
        nowMs: Long
    ): PerAppDecision {
        if (!perAppEnabled) return PerAppDecision.Skip
        val pkg = eventPackage?.takeIf { it.isNotBlank() } ?: return PerAppDecision.Skip
        if (pkg == currentPackage) return PerAppDecision.Skip
        if (pkg == ownPackage || pkg in denylist) return PerAppDecision.Skip
        if (nowMs - lastHandledAtMs < debounceMs) return PerAppDecision.Skip
        lastHandledAtMs = nowMs
        return PerAppDecision.Handle(pkg)
    }
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.service.PerAppDetectorTest"`
Expected: PASS（8 用例）

- [ ] **Step 5：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/PerAppDetector.kt app/src/test/java/com/phantom/scroll/service/PerAppDetectorTest.kt
git commit -m "feat(service): add pure PerAppDetector (denylist/dedup/debounce for per-app events)"
```

---

## Task 5：PhantomScrollService 接入 per-app 检测

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt`

> 现状 `onAccessibilityEvent` 是空 no-op（注释"Per-app detection lands in Phase 3"）。现在接 `PerAppDetector`：服务持有单例 detector，事件来时取 `event.packageName` + `System.currentTimeMillis()` 调 `evaluate`，`Handle` 则 `setCurrentPackage`。denylist 至少含 SystemUI + 自身包名（Launcher/IME 在真机上手测时按需补，本期代码留 `SYSTEM_PACKAGE_DENYLIST` 常量便于扩展）。

- [ ] **Step 1：注入 detector 并实现 onAccessibilityEvent**

编辑 `PhantomScrollService.kt`：

(a) 补 import：
```kotlin
import android.view.accessibility.AccessibilityEvent
```
（`onAccessibilityEvent` 参数类型已是 `android.view.accessibility.AccessibilityEvent?`，现有文件用了 FQN，可不动；若想统一可用 import。下面 Step 用 FQN 写法以最小改动。）

(b) 在类成员区（`eventReceiver` 声明附近）加 detector 与 denylist：
```kotlin
    /**
     * Packages that must NEVER become currentPackage: SystemUI (status bar / recents), the app's
     * own package, and (extensible) Launchers / IMEs. Kept as a const so real-device tweaks are
     * localized. spec §3.4.
     */
    private val systemPackageDenylist: Set<String> = setOf(
        "com.android.systemui",
        packageName // own package
    )

    private val perAppDetector by lazy { PerAppDetector(ownPackage = packageName, denylist = systemPackageDenylist) }
```

(c) 替换 `onAccessibilityEvent`：
```kotlin
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
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
```

> **零开销保证：** `evaluate` 第一行 `if (!perAppEnabled) return Skip` 在 per-app 关闭时立即返回，不触发任何后续处理（spec §3.4"关闭时零开销"）。`event?.packageName?.toString()` 仅在读到事件时执行；per-app 关闭即等于现状 no-op。

- [ ] **Step 2：验证编译 + 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（**56**，本任务无新增测试；PerAppDetectorTest 的 8 个已在 Task 4 计入。服务改动靠编译 + Task 11 手工）

- [ ] **Step 3：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt
git commit -m "feat(service): wire onAccessibilityEvent to PerAppDetector → setCurrentPackage"
```

---

## Task 6：ScrollOrchestrator 累计统计

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt`

> spec §3.2：每次成功手势 `repo.incrementStats(swipeDelta=1, elapsedDeltaMs=noiseInterval)`。现状 `onCompleted` 只调 `failurePolicy.recordSuccess()`。`noiseInterval` 现已在 `delay(noiseInterval)` 前算出 —— 为复用，把它提升到 `onCompleted` 可见的作用域（或直接在成功分支里再算一次相同表达式）。最稳妥：把 `noiseInterval` 的计算移到手势成功判定之后、`delay` 之前，先 `incrementStats` 再 `delay`。

- [ ] **Step 1：在成功分支累计统计并接入方向参数**

编辑 `ScrollOrchestrator.kt`：

(a) 修改手势路径生成调用，透传 `direction` 参数：
```kotlin
                    val gestureResult = withContext(Dispatchers.Default) {
                        gestureEngine.generateGesturePath(
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            distanceRatio = settings.distanceRatio,
                            durationMs = settings.duration,
                            direction = settings.direction
                        )
                    }
```

(b) 在成功分支累加统计，把
```kotlin
                    val noiseInterval = gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                        .toLong().coerceIn(400, 12000)
                    delay(noiseInterval)
```
改为
```kotlin
                    val noiseInterval = gestureEngine.addBioNoise(settings.interval.toFloat(), 0.08f)
                        .toLong().coerceIn(400, 12000)
                    // spec §3.2: count one successful swipe + accumulate the wait as elapsed time.
                    repository.incrementStats(swipeDelta = 1, elapsedDeltaMs = noiseInterval)
                    delay(noiseInterval)
```

> 仅一次写入，紧跟成功手势之后；失败/取消路径不计。`incrementStats` 是非 suspend、线程安全（Phase 1 设计），在 `Dispatchers.Main.immediate` 上下文调用无碍。

- [ ] **Step 2：验证编译 + 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（56）

- [ ] **Step 3：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt
git commit -m "feat(service): increment stats (swipe + elapsed) after each successful gesture"
```

---

## Task 7：面板布局扩展（预设 Chip / 方向按钮 / 统计行 / per-app 开关 + 标签 / 重置）

**Files:**
- Modify: `app/src/main/res/layout/overlay_panel.xml`
- Create: `app/src/main/res/drawable/overlay_chip_bg.xml`
- Create: `app/src/main/res/drawable/overlay_chip_bg_selected.xml`

> 面板宽度仍 130dp。新增行**自上而下**插入顺序：① 标题行（已有，右侧加方向按钮替换/并列 fold）② **预设 Chip 行**（小说｜漫画｜自定义，三选一高亮）③ 速度/间隔/距离 Slider（已有）④ **方向按钮行**（↑/↓ 切换）⑤ **统计行**（已翻 N 次 · 约 M 分钟，点击重置）⑥ **per-app 行**（开关 + `📖 当前：<App>`）⑦ 播放按钮（已有）。
>
> 实现取舍：用最小改动 —— Chip 用 `TextView` + 背景 drawable 切换 selected 态（避免引 Chip 组依赖复杂状态）；方向按钮用 `MaterialButton` icon-style（text 用 ↑/↓ Unicode）；统计行用可点击 `LinearLayout`；per-app 开关用 `com.google.android.material.materialswitch.MaterialSwitch`（material:1.12.0 自带）。若 MaterialSwitch 在 130dp 内太挤，降级为 `CheckBox`（Task 8 绑定时二选一）。

- [ ] **Step 1：chip 背景 drawable**

Create `app/src/main/res/drawable/overlay_chip_bg.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/white_10" />
    <corners android:radius="8dp" />
</shape>
```

Create `app/src/main/res/drawable/overlay_chip_bg_selected.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/phantom_blue_50" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 2：扩展 overlay_panel.xml**

在 `overlay_panel.xml` 中：

(a) 标题行之后、`速度` Slider 区段之前，插入**预设 Chip 行**：
```xml
    <!-- Preset row: 小说 | 漫画 | 自定义 (single-select highlight) -->
    <LinearLayout
        android:id="@+id/preset_row"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center">
        <TextView
            android:id="@+id/chip_novel"
            android:layout_width="0dp"
            android:layout_height="22dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="小说"
            android:textColor="@color/text_primary"
            android:textSize="9sp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true" />
        <Space android:layout_width="4dp" android:layout_height="match_parent" />
        <TextView
            android:id="@+id/chip_comic"
            android:layout_width="0dp"
            android:layout_height="22dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="漫画"
            android:textColor="@color/text_primary"
            android:textSize="9sp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true" />
        <Space android:layout_width="4dp" android:layout_height="match_parent" />
        <TextView
            android:id="@+id/chip_custom"
            android:layout_width="0dp"
            android:layout_height="22dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="自定义"
            android:textColor="@color/text_primary"
            android:textSize="9sp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true" />
    </LinearLayout>

    <Space android:layout_width="match_parent" android:layout_height="4dp" />
```

(b) 在 `距离` Slider 区段之后、播放按钮之前，插入**方向按钮行 + 统计行 + per-app 行**：
```xml
    <!-- Direction toggle: ↑ (UP, next page) / ↓ (DOWN, previous page) -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">
        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="方向"
            android:textColor="@color/text_secondary"
            android:textSize="9sp" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/direction_button"
            android:layout_width="44dp"
            android:layout_height="24dp"
            android:minWidth="0dp"
            android:minHeight="0dp"
            android:padding="0dp"
            android:insetTop="0dp"
            android:insetBottom="0dp"
            android:text="↑"
            android:textColor="@color/text_primary"
            android:textSize="12sp"
            android:textStyle="bold"
            app:cornerRadius="12dp"
            app:backgroundTint="@color/phantom_blue_50" />
    </LinearLayout>

    <Space android:layout_width="match_parent" android:layout_height="4dp" />

    <!-- Stats row: 已翻 N 次 · 约 M 分钟 (click to reset) -->
    <LinearLayout
        android:id="@+id/stats_row"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:clickable="true"
        android:focusable="true"
        android:background="@drawable/overlay_chip_bg"
        android:padding="3dp">
        <TextView
            android:id="@+id/stats_text"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="center"
            android:text="已翻 0 次 · 约 0 分钟"
            android:textColor="@color/text_secondary"
            android:textSize="8sp" />
    </LinearLayout>

    <Space android:layout_width="match_parent" android:layout_height="4dp" />

    <!-- Per-app row: switch + current app label -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">
        <com.google.android.material.materialswitch.MaterialSwitch
            android:id="@+id/perapp_switch"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="按App"
            android:textColor="@color/text_secondary"
            android:textSize="9sp" />
    </LinearLayout>
    <TextView
        android:id="@+id/perapp_label"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:text=""
        android:textColor="@color/phantom_cyan"
        android:textSize="8sp"
        android:visibility="gone" />
```

- [ ] **Step 3：验证资源 + 编译**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（若 `MaterialSwitch` 在该 minSdk/material 版本解析失败，回退：把 `MaterialSwitch` 换成 `CheckBox android:id="@+id/perapp_switch"`，Task 8 绑定相应改 `setOnCheckedChangeListener`。本计划默认 MaterialSwitch 可用，1.12.0 支持 API 26+。）

- [ ] **Step 4：Commit**

```bash
git add app/src/main/res/layout/overlay_panel.xml app/src/main/res/drawable/overlay_chip_bg.xml app/src/main/res/drawable/overlay_chip_bg_selected.xml
git commit -m "feat(res): overlay panel rows for presets / direction / stats / per-app"
```

---

## Task 8：FloatingOverlayView 绑定新交互（预设 / 方向 / 统计 / per-app）

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`

> 视图侧只做"收集 Flow → 刷新 UI"与"用户操作 → 写 repo"。所有写都走 `updateActive`（per-app 开启时写 profile，否则写 global），保证"在某个 App 里拖 Slider 就记忆到该 App"的语义（spec §3.4）。预设 Chip 的选中态由 `selectedPreset` 派生驱动；用户手动拖 Slider 后 `selectedPreset` 自动变 `Custom`（因为 global 不再命中内置），Chip 自动切到"自定义"——无需视图额外逻辑。

- [ ] **Step 1：findViewById 新控件 + 声明字段**

在 `FloatingOverlayView` 的 `init { inflate(...); findViewById(...) }` 区块补：
```kotlin
        // Phase 3 controls
        chipNovel = findViewById(R.id.chip_novel)
        chipComic = findViewById(R.id.chip_comic)
        chipCustom = findViewById(R.id.chip_custom)
        directionButton = findViewById(R.id.direction_button)
        statsRow = findViewById(R.id.stats_row)
        statsText = findViewById(R.id.stats_text)
        perAppSwitch = findViewById(R.id.perapp_switch)
        perAppLabel = findViewById(R.id.perapp_label)
```
对应字段声明（与现有 `playButton` 等放一起，类型用 `View` / `MaterialButton` / `TextView` / `MaterialSwitch`）：
```kotlin
    private val chipNovel: TextView
    private val chipComic: TextView
    private val chipCustom: TextView
    private val directionButton: com.google.android.material.button.MaterialButton
    private val statsRow: View
    private val statsText: TextView
    private val perAppSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private val perAppLabel: TextView
```
> 用 FQN 避免顶部一堆 import；或加 import 等价。

- [ ] **Step 2：用户操作 → repo（预设 / 方向 / 统计重置 / per-app 开关 / 忘记配置）**

在 `init { ... }` 的点击监听区（现有 `handleRoot.setOnClickListener` 附近）补：
```kotlin
        chipNovel.setOnClickListener { scope.launch { repository?.applyPreset(Preset.NOVEL) } }
        chipComic.setOnClickListener { scope.launch { repository?.applyPreset(Preset.COMIC) } }
        // 自定义 Chip 不可点（它是"未命中预设"的派生态）；点击无效即可。

        directionButton.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            val active = repo.activeSettings.value
            val next = if (active.direction == ScrollDirection.UP) ScrollDirection.DOWN else ScrollDirection.UP
            scope.launch { repo.updateActive(active.copy(direction = next)) }
        }

        statsRow.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            scope.launch {
                repo.resetStats()
                PhantomToast.show(context, "统计已重置")   // 见 Step 6 的轻量 Toast 封装
            }
        }

        perAppSwitch.setOnCheckedChangeListener { _, checked ->
            // 避免程序化 setChecked 触发写：用 applyingFromFlow 守卫（复用现有标志）
            if (applyingFromFlow) return@setOnCheckedChangeListener
            repository?.setPerAppEnabled(checked)
        }

        perAppLabel.setOnLongClickListener {
            val repo = repository ?: return@setOnLongClickListener false
            scope.launch {
                repo.forgetActiveProfile()
                PhantomToast.show(context, "已忘记当前 App 配置")
            }
            true
        }
```

> 需补 import：`com.phantom.scroll.data.Preset`、`com.phantom.scroll.data.ScrollDirection`、`kotlinx.coroutines.launch`。

- [ ] **Step 3：Slider 写入改走 updateActive（per-app 语义）**

把现有三个 Slider 的 `updateSetting { it.copy(...) }` 改为 `updateActive`：
```kotlin
        durationSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateActive { it.copy(duration = value.toLong()) }
        }
        intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateActive { it.copy(interval = value.toLong()) }
        }
        distanceSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateActive { it.copy(distanceRatio = value) }
        }
```
并把私有方法 `updateSetting` 重命名/替换为 `updateActive`（读 `activeSettings` 当前值，写 `updateActive`）：
```kotlin
    private fun updateActive(transform: (ScrollSettings) -> ScrollSettings) {
        val repo = repository ?: return
        scope.launch { repo.updateActive(transform(repo.activeSettings.value)) }
    }
```
> **语义关键**：读 `activeSettings.value`（per-app 开启时是 profile 值，否则 global 值），写 `updateActive`（自动落到 profile 或 global）。这样"在 App A 里拖 Slider"改的是 App A 的 profile，不影响 global；关 per-app 时则改 global。

- [ ] **Step 4：applySettings 读 activeSettings（而非 global）**

现有 `applySettings(s: ScrollSettings)` 由 `repo.global.collect` 驱动。per-app 开启时面板应显示**当前生效值**（profile），故把收集源从 `repo.global` 换成 `repo.activeSettings`：
```kotlin
            launch { repo.activeSettings.collect { applySettings(it) } }
```
`applySettings` 函数体不变。`applyingFromFlow` 守卫依旧防止回流写。

- [ ] **Step 5：收集新 Flow 并命令式刷新**

在 `startCollecting()` 的 `collectJob = scope.launch { ... }` 内追加四个收集：
```kotlin
            launch { repo.selectedPreset.collect { applyPresetSelection(it) } }
            launch { repo.stats.collect { applyStats(it) } }
            launch { repo.perAppEnabled.collect { applyPerAppEnabled(it) } }
            launch { repo.currentPackage.collect { applyCurrentPackage(it) } }
```
> `repo.activeSettings`（Step 4 替换的）也算其一。

新增私有刷新方法与显示更新逻辑，解决 per-app 标签可见性与状态同步的延迟问题：
```kotlin
    private fun applyPresetSelection(sel: PresetSelection) {
        // single-select highlight: selected chip uses the selected bg + cyan text
        val selectedBg = ResourcesCompat.getDrawable(resources, R.drawable.overlay_chip_bg_selected, null)
        val plainBg = ResourcesCompat.getDrawable(resources, R.drawable.overlay_chip_bg, null)
        listOf(chipNovel to (sel is PresetSelection.BuiltIn && sel.preset == Preset.NOVEL),
               chipComic to (sel is PresetSelection.BuiltIn && sel.preset == Preset.COMIC),
               chipCustom to (sel is PresetSelection.Custom)).forEach { (chip, on) ->
            chip.background = if (on) selectedBg else plainBg
            chip.setTextColor(if (on) ResourcesCompat.getColor(resources, R.color.phantom_cyan, null)
                              else ResourcesCompat.getColor(resources, R.color.text_primary, null))
        }
    }

    private fun applyStats(stats: ScrollStats) {
        // spec §3.2: elapsed shown in minutes, Math.round(elapsedMs / 60000.0)
        val minutes = Math.round(stats.elapsedMs / 60000.0)
        statsText.text = "已翻 ${stats.swipeCount} 次 · 约 $minutes 分钟"
    }

    private fun applyPerAppEnabled(enabled: Boolean) {
        applyingFromFlow = true
        perAppSwitch.isChecked = enabled
        applyingFromFlow = false
        updatePerAppLabelVisibility()
    }

    private fun applyCurrentPackage(pkg: String?) {
        updatePerAppLabelVisibility()
    }

    private fun updatePerAppLabelVisibility() {
        val repo = repository ?: return
        val pkg = repo.currentPackage.value
        val enabled = repo.perAppEnabled.value
        perAppLabel.visibility = if (pkg != null && enabled) VISIBLE else GONE
        perAppLabel.text = if (pkg != null) "📖 当前：$pkg" else ""
        // 用包名做展示名（避免引 PackageManager 解析 label 的开销与权限故事）；
        // 若需友好名，Phase 4 再加 PackageManager 缓存。
    }
```
> `applyPerAppEnabled` 用 `applyingFromFlow` 守卫，防止 `perAppSwitch.isChecked = enabled` 触发 `setOnCheckedChangeListener` 回写 repo（避免循环）。

补 import：`com.phantom.scroll.data.PresetSelection`、`com.phantom.scroll.data.ScrollStats`、`androidx.core.content.res.ResourcesCompat`（已有）。

- [ ] **Step 6：轻量 Toast 封装（可选，避免散落）**

在 `FloatingOverlayView` 内加私有 object 或顶层 helper：
```kotlin
    private object PhantomToast {
        fun show(ctx: android.content.Context, msg: String) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
```
（Step 2 的 `statsRow` 点击已引用。若嫌啰嗦可直接内联 `Toast.makeText`。）

- [ ] **Step 7：验证编译**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（视图未引新依赖；`MaterialSwitch`/`MaterialButton` 来自已有 material:1.12.0）

- [ ] **Step 8：验证全量单测（视图改动不应影响纯逻辑测试）**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（56）

- [ ] **Step 9：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt
git commit -m "feat(ui): overlay binds presets/direction/stats/per-app to repository"
```

---

## Task 9：方向按钮的"显示态"同步（小修补）

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`

> Task 8 的 `directionButton.setOnClickListener` 切换了方向，但按钮文字（↑/↓）需要随 `activeSettings.direction` 刷新。`applySettings` 已在 Step 4 由 `activeSettings.collect` 驱动 —— 在它里面同步按钮文字即可。

- [ ] **Step 1：applySettings 末尾补方向按钮刷新**

在 `applySettings(s: ScrollSettings)` 函数末尾追加：
```kotlin
        directionButton.text = if (s.direction == ScrollDirection.DOWN) "↓" else "↑"
```

- [ ] **Step 2：验证编译 + 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（56）

- [ ] **Step 3：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt
git commit -m "feat(ui): direction button glyph tracks activeSettings.direction"
```

---

## Task 10：Release 构建 + proguard 复核

**Files:**
- Modify (if needed): `app/proguard-rules.pro`

- [ ] **Step 1：Release 构建**

Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL`（R8/minify + 资源缩减通过）。Phase 3 未引新依赖，`MaterialSwitch` / `MaterialButton` 已含在 material:1.12.0 的 consumer 规则里，预期无需额外 keep。

- [ ] **Step 2：（仅当 Step 1 报混淆错误）补 keep**

若 release 构建报 `MaterialSwitch` / `MaterialButton` / `PerAppDetector` / `PresetRegistry` 相关错误，按错误信息在 `app/proguard-rules.pro` 追加（示例）：
```proguard
# Material Components (overlay Slider/Switch/Button) — normally covered by consumer rules
-keep class com.google.android.material.** { *; }
```
> `PerAppDetector`/`PresetRegistry` 是 Kotlin object，被服务/视图直接引用，R8 不会误删，通常无需 keep。

- [ ] **Step 3：Commit（仅当 Step 2 改了 proguard）**

```bash
git add app/proguard-rules.pro
git commit -m "build: keep Material overlay widgets in R8 release build"
```

---

## Task 11：Phase 3 对齐验收

**Files:** 无代码改动；记录验收结果。

- [ ] **Step 1：全量单测 + Release 构建绿**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，全部通过（**56** 个：Phase 2 收尾的 34 + GestureEngine 方向 2 + PresetRegistry 5 + SettingsRepository 新 7 + PerAppDetector 8 = **56**。见下方对账说明）。
Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL`。

> **测试数对账（务必核对实际报告）：**
> - Phase 2 闸门基线：34（SettingsRepositoryTest 11 + FailurePolicyTest 3 + ScreenStateCoordinatorTest 4 + GestureEngineTest 3 + ScrollSettingsTest 1 + MigrationMapperTest 3 + ProfileKeyParsingTest 3 + OverlayGeometryTest 6 = 34）。
> - Task 1：GestureEngineTest +2（方向 UP/DOWN）→ 36
> - Task 2：PresetRegistryTest +5 → 41
> - Task 3：SettingsRepositoryTest +7（applyPreset/selectedPreset 各测试与 per-app 组合）→ 48
> - Task 4：PerAppDetectorTest +8 → **56**
> - 预期 Phase 3 收尾单测总数 = **56**。报告实际数字并对账。

- [ ] **Step 2：真机/模拟器对齐验收清单（逐项过）**

**场景预设：**
- [ ] 点"小说"Chip → 三个 Slider 跳到 700/4000/0.55，"小说"Chip 高亮（青色字 + 蓝底）。
- [ ] 点"漫画"Chip → Slider 跳到 500/3000/0.85，"漫画"高亮。
- [ ] 手动拖任一 Slider → "自定义"Chip 自动高亮，小说/漫画失活（`selectedPreset` 派生）。
- [ ] 杀进程重启 → 预设选择态随 global 恢复（命中则高亮对应，否则自定义）。

**运行统计：**
- [ ] 启动自动滑动跑若干次 → 统计行"已翻 N 次 · 约 M 分钟"递增（M 按 `Math.round(elapsedMs/60000)` 四舍五入）。
- [ ] 点击统计行 → Toast"统计已重置"，计数归零。
- [ ] 杀进程重启 → 统计保留（DataStore 持久化）。

**滚动方向：**
- [ ] 点方向按钮 → 文字在 ↑/↓ 切换，**滑动方向随之翻转**（↑=内容上滚翻下一页，↓=内容下滚翻上一页）。
- [ ] 切到"漫画"预设 → 方向重置为 ↑（预设内含 direction=UP）。
- [ ] per-app 开启时在 App A 切 ↓ → 切到 App B 仍是各自记忆的方向（A 记 ↓，B 未自定义则 global）。

**按 App 记忆：**
- [ ] 打开"按App"开关 → 切到某阅读 App，标签显示 `📖 当前：<包名>`。
- [ ] 在该 App 拖 Slider → 仅该 App 的 profile 变化（切走再切回值保留），**global 不变**（关 per-app 后看到的仍是原 global）。
- [ ] 切到未自定义的 App → 标签显示但 Slider 显示 global 值（回落）。
- [ ] SystemUI / Launcher / 本应用前台 → 标签**不**显示这些包（denylist 生效），且 per-app 关闭时切换零开销（无日志刷屏）。
- [ ] 快速来回切两个 App（<300ms）→ 仅最后一次切换生效（防抖）。
- [ ] 提供"忘记当前 App 配置"入口（本期通过长按悬浮窗中的当前包名标签 `perapp_label` 触发 `forgetActiveProfile` 并弹出 Toast 确认）→ 删除后该 App 回落 global。

**回归（Phase 2 行为不破）：**
- [ ] 手柄/面板展开折叠、拖拽吸附、外部点击折叠、Slider 连续值、播放/通知联动、熄屏暂停/亮屏恢复、旋屏重定位 —— 全部如 Phase 2。

- [ ] **Step 3：把验收结果记入闸门标记提交**

```bash
git commit --allow-empty -m "chore: Phase 3 productization gate passed (presets/stats/direction/per-app verified)"
```

---

## Phase 3 完成判据

1. `./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleRelease` 均 `BUILD SUCCESSFUL`；单测 **56**（对账实际报告）。
2. 四项功能可用：场景预设切换（小说/漫画/自定义 + 手动拖回退自定义）、运行统计（递增/分钟折算/重置/持久化）、方向切换（↑/↓ 生效 + 按钮同步）、按 App 记忆（开关可控、自动创建 profile、denylist、防抖、可忘记）。
3. 对齐验收清单全部通过；Phase 2 行为零回归。
4. 无新依赖、无持久化 schema 变更（direction 字段 Phase 1 已落库）。
5. per-app 关闭时 `onAccessibilityEvent` 零开销（早退，spec §3.4）。

完成后即可撰写 **Phase 4 计划**（性能收尾：Baseline Profile、Compose 稳定性配置、热路径零分配复核、测量留痕）。Phase 4 的 `:baselineprofile` 模块会复用 Phase 3 的运行统计作为宏基准的"操作悬浮窗"脚本依据。

---

## 回退策略（Phase 3）

- 每个任务独立 commit，可单独 revert。
- **功能级回退**（spec 跨阶段策略）：per-app 默认关（功能即等效隐藏）；预设/统计行/方向按钮即使保留在面板上，不操作即不影响核心滑动；可通过 Task 7 的布局改动 revert 隐藏全部新行（回到 Phase 2 面板）。
- **数据回退**：Phase 3 不改 schema，direction/profiles/stats 在 Phase 1 已建好存储；revert 代码不影响已落库数据。
