# PhantomScroll V2 · Phase 2 实施计划：悬浮窗原生 View 重写

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把始终悬浮的控制面板从 Compose (`ComposeView`) 重写为原生 View，移除阅读时常驻的 Compose 运行时，功能与现状逐项对齐；顺带删除 `OverlayLifecycleOwner` 和 Phase 1 的 `ScrollConfig` 桥接（新悬浮窗直接消费 `SettingsRepository`）。

**Architecture:** 新增自定义视图 `FloatingOverlayView`（`FrameLayout`，内部按 `PanelState` 切换"手柄/面板"可见性，持有专属协程 `CoroutineScope` 在 `onAttachedToWindow/onDetachedFromWindow` 收集 `SettingsRepository` 的 `StateFlow` 并命令式刷新 UI）；纯几何逻辑（吸附目标、拖拽边界）抽到 `OverlayGeometry` 做 JVM 单测；`FloatingWindowController` 改为 inflate 原生视图（用 `ContextThemeWrapper` 包 Material3 主题，因 `Slider` 需要 Material 主题上下文）；资源层用 `res/values/colors.xml` + `res/drawable` shape 复刻幽灵主题渐变。

**Tech Stack:** Kotlin, kotlinx.coroutines (Flow/StateFlow), Android View 系统, Google Material Components (`com.google.android.material:material` 的 `Slider`), ValueAnimator, JUnit 4。

**Spec:** [docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md](../specs/2026-06-14-phantomscroll-v2-optimization-design.md) §Phase 2

> **本计划范围：仅 Phase 2。** Phase 3（产品功能）/ Phase 4（性能收尾）各有独立计划，在各自闸门通过后撰写。
>
> **提交约定：** 每个任务末尾的 `git commit` 均需在提交信息末尾追加一行 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`（下文各 commit 命令示例中不再重复写出该行）。
>
> **测试命令约定：** 跑单个测试类 `./gradlew :app:testDebugUnitTest --tests "<全限定类名>"`；全量单测 `./gradlew :app:testDebugUnitTest`；编译校验 `./gradlew assembleDebug`；混淆构建 `./gradlew assembleRelease`。所有命令预期最终输出 `BUILD SUCCESSFUL`。
>
> **关于 TDD 的诚实说明：** Phase 2 主体是 Android View（渲染/拖拽/动画），无法纯 JVM 单测（需 Robolectric/仪器测试，超出本项目测试策略）。因此仅把**可纯 JVM 测试的几何逻辑**抽到 `OverlayGeometry` 并 TDD；View/Controller/资源由 `assembleDebug`（编译）+ Task 11 的对齐验收清单（真机/模拟器手工）保证。这与 Phase 1 的策略一致（纯逻辑 JVM 测，Android 触碰层靠构建+手工）。
>
> **Material 主题前提（关键）：** `res/values/themes.xml` 的 `Theme.PhantomScroll` 继承 `Theme.AppCompat.NoActionBar`，**不是** Material 主题。`com.google.android.material.slider.Slider` 在非 Material 主题下会崩溃。因此本计划新增 `Theme.PhantomScroll.Overlay`（继承 `Theme.Material3.Dark.NoActionBar`），并要求 `FloatingWindowController` 用 `ContextThemeWrapper(context, R.style.Theme_PhantomScroll_Overlay)` 包裹后再构造 `FloatingOverlayView`。

---

## 文件结构（Phase 2 新增/修改/删除）

**新增：**

| 文件 | 职责 |
|------|------|
| `res/values/colors.xml` | 从 `Color.kt` 同步色值（供 drawable/layout 引用） |
| `res/drawable/overlay_card_border.xml` | 面板卡片：青蓝→紫渐变 1dp 边框 + 暗色半透圆角（layer-list） |
| `res/drawable/overlay_handle_left.xml` | 左侧手柄渐变（青→蓝）+ 右侧圆角 |
| `res/drawable/overlay_handle_right.xml` | 右侧手柄渐变（蓝→青）+ 左侧圆角 |
| `res/layout/overlay_panel.xml` | 展开面板布局（标题行/3 Slider/播放按钮） |
| `res/layout/overlay_handle.xml` | 折叠手柄布局（触控区 + 手柄视觉 + 指示条） |
| `ui/overlay/PanelState.kt` | `PanelState` 枚举独立成文件（从 `FloatingPanel.kt` 迁出） |
| `ui/overlay/OverlayGeometry.kt` | 纯几何：吸附目标 / 边缘 X / 拖拽 clamp |
| `ui/overlay/FloatingOverlayView.kt` | 原生自定义视图（渲染+交互+Flow 收集，无业务逻辑） |
| `res/values/themes.xml`（追加） | `Theme.PhantomScroll.Overlay`（Material3） |
| 测试 `ui/overlay/OverlayGeometryTest.kt` | 几何逻辑单测 |

**修改：**

| 文件 | 改动 |
|------|------|
| `gradle/libs.versions.toml` + `app/build.gradle.kts` | 加 Material Components 依赖 |
| `service/FloatingWindowController.kt` | 改为构造 `FloatingOverlayView`（原生），移除 ComposeView/OverlayLifecycleOwner |
| `service/PhantomScrollService.kt` | 移除 `config` 桥接字段；`FloatingWindowController` 改传 `repository` |
| `app/proguard-rules.pro` | 审查并补 Material `Slider` 等 keep 规则 |

**删除：**

| 文件 | 原因 |
|------|------|
| `ui/overlay/FloatingPanel.kt` | Compose 面板，已被原生视图取代，无引用 |
| `service/OverlayLifecycleOwner.kt` | 仅供 ComposeView 的 ViewTree 生命周期使用，原生视图不需要 |
| `config/ScrollConfig.kt` | Phase 1 桥接，服务不再构造它后无消费者 |
| `app/src/test/.../config/ScrollConfigTest.kt` | 测试已删除的 `ScrollConfig` 桥接 |

> **不改动：** `ui/theme/Color.kt`、`ui/theme/Theme.kt`（`OverlayTheme`，Compose 主界面仍可能引用；未引用时由 R8 剥离）、`MainActivity.kt`/`MainScreen.kt`（Compose 权限页保持不变）。

---

## Task 1：Material 依赖 + colors.xml + Overlay 主题

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`

- [ ] **Step 1：版本目录新增 Material 库声明**

在 `gradle/libs.versions.toml` 的 `[versions]` 段追加（`appcompat = "1.7.0"` 行之后、`junit` 行之前）：
```toml
material = "1.12.0"
```
在 `[libraries]` 段追加（`androidx-appcompat` 声明之后、`# Testing dependencies` 之前）：
```toml
# Material Components (native Slider for the overlay)
google-material = { group = "com.google.android.material", name = "material", version.ref = "material" }
```

同时更新 `# SavedState` 注释（第 45 行），因 Phase 2 删除 `OverlayLifecycleOwner` 后该注释过时：
```toml
# SavedState (required by Compose internals)
```

- [ ] **Step 2：app 模块引用 Material 依赖**

编辑 `app/build.gradle.kts`，在 `dependencies { }` 内 `implementation(libs.androidx.appcompat)` 行之后追加：
```kotlin
    // Material Components (native overlay Slider)
    implementation(libs.google.material)
```

- [ ] **Step 3：创建 colors.xml（从 Color.kt 同步色值）**

Create `app/src/main/res/values/colors.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="dark_background">#FF0F0F12</color>
    <color name="dark_surface">#FF1E1E24</color>
    <color name="dark_surface_translucent">#CC121216</color>
    <color name="phantom_cyan">#FF00E5FF</color>
    <color name="phantom_blue">#FF2979FF</color>
    <color name="phantom_purple">#FFD500F9</color>
    <color name="text_primary">#FFF5F5F7</color>
    <color name="text_secondary">#FF8E8E93</color>
    <color name="text_tertiary">#FF48484A</color>
    <color name="success_green">#FF30D158</color>
    <color name="warning_orange">#FFFF9F0A</color>
    <color name="error_red">#FFFF453A</color>
    <!-- derived alphas used by handle gradient + fold button (parity with Compose) -->
    <color name="phantom_cyan_80">#CC00E5FF</color>
    <color name="phantom_blue_50">#802979FF</color>
    <color name="white_70">#B3FFFFFF</color>
    <color name="white_10">#1AFFFFFF</color>
</resources>
```

- [ ] **Step 4：themes.xml 追加 Material3 Overlay 主题**

编辑 `app/src/main/res/values/themes.xml`，在 `<resources>` 内现有 `Theme.PhantomScroll` style 之后追加：
```xml
    <!-- Material3 theme used ONLY to inflate the native overlay (Slider requires a Material theme).
         The app's main theme stays Theme.AppCompat.NoActionBar. -->
    <style name="Theme.PhantomScroll.Overlay" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">@color/phantom_blue</item>
        <item name="colorOnPrimary">@color/text_primary</item>
        <item name="colorPrimaryContainer">@color/dark_surface</item>
    </style>
```

- [ ] **Step 5：验证构建通过**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（Material3 主题随 `material:1.12.0` 可用）

- [ ] **Step 6：Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/res/values/colors.xml app/src/main/res/values/themes.xml
git commit -m "build(res): add Material Components dep, overlay colors, Material3 overlay theme"
```

---

## Task 2：OverlayGeometry 纯几何逻辑（TDD）

**Files:**
- Test: `app/src/test/java/com/phantom/scroll/ui/overlay/OverlayGeometryTest.kt`
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/OverlayGeometry.kt`

- [ ] **Step 1：写失败测试**

Create `app/src/test/java/com/phantom/scroll/ui/overlay/OverlayGeometryTest.kt`:
```kotlin
package com.phantom.scroll.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {

    @Test
    fun snapTarget_left_when_center_left_of_middle() {
        // panelCenterX 400 < middle 540 → snap to left edge
        val t = OverlayGeometry.snapTarget(panelCenterX = 400, screenWidth = 1080, panelWidthPx = 200)
        assertEquals(0, t.x)
        assertEquals(true, t.isLeftEdge)
    }

    @Test
    fun snapTarget_right_when_center_right_of_middle() {
        // panelCenterX 700 > middle 540 → snap to right edge
        val t = OverlayGeometry.snapTarget(panelCenterX = 700, screenWidth = 1080, panelWidthPx = 200)
        assertEquals(1080 - 200, t.x)
        assertEquals(false, t.isLeftEdge)
    }

    @Test
    fun edgeX_is_zero_for_left_else_screen_minus_width() {
        assertEquals(0, OverlayGeometry.edgeX(isLeftEdge = true, screenWidth = 1080, widthPx = 200))
        assertEquals(880, OverlayGeometry.edgeX(isLeftEdge = false, screenWidth = 1080, widthPx = 200))
    }

    @Test
    fun clamp_keeps_value_within_bounds() {
        assertEquals(0, OverlayGeometry.clamp(-5, 0, 100))
        assertEquals(100, OverlayGeometry.clamp(150, 0, 100))
        assertEquals(42, OverlayGeometry.clamp(42, 0, 100))
    }

    @Test
    fun clamp_handles_zero_range() {
        // defensive: if max == min, coerceIn returns that single valid value
        assertEquals(0, OverlayGeometry.clamp(50, 0, 0))
    }

    @Test
    fun clamp_handles_inverted_bounds_gracefully() {
        // defensive: if max < min (e.g. very small screen), clamp should not crash
        // and should return min as a safe default
        assertEquals(0, OverlayGeometry.clamp(50, 0, -10))
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.ui.overlay.OverlayGeometryTest"`
Expected: FAIL（`OverlayGeometry` 未解析）

- [ ] **Step 3：实现 OverlayGeometry**

Create `app/src/main/java/com/phantom/scroll/ui/overlay/OverlayGeometry.kt`:
```kotlin
package com.phantom.scroll.ui.overlay

/**
 * Pure geometry helpers for the floating overlay. Side-effect-free, no Android View
 * dependencies beyond primitives, so they are JVM-unit-testable.
 */
object OverlayGeometry {

    /** Result of computing the snap target after a drag ends. */
    data class SnapTarget(val x: Int, val isLeftEdge: Boolean)

    /**
     * Given the panel's center X, decides which screen edge to snap to.
     * Center left of screen middle → left edge (x=0); otherwise right edge.
     */
    fun snapTarget(panelCenterX: Int, screenWidth: Int, panelWidthPx: Int): SnapTarget {
        val middle = screenWidth / 2
        return if (panelCenterX > middle) {
            SnapTarget(x = screenWidth - panelWidthPx, isLeftEdge = false)
        } else {
            SnapTarget(x = 0, isLeftEdge = true)
        }
    }

    /** The resting X for a given edge (0 for left, screenWidth - widthPx for right). */
    fun edgeX(isLeftEdge: Boolean, screenWidth: Int, widthPx: Int): Int =
        if (isLeftEdge) 0 else screenWidth - widthPx

    /**
     * Clamps [value] into [min]..[max]. If [max] < [min] (defensive: very small screen),
     * returns [min] to avoid [IllegalArgumentException] from [coerceIn].
     */
    fun clamp(value: Int, min: Int, max: Int): Int =
        value.coerceIn(min, max.coerceAtLeast(min))
}
```

- [ ] **Step 4：运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.phantom.scroll.ui.overlay.OverlayGeometryTest"`
Expected: PASS（6 用例）

- [ ] **Step 5：验证全量单测仍绿**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（Phase 1 的 30 个 + 本任务 6 个 = 36）

- [ ] **Step 6：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/OverlayGeometry.kt app/src/test/java/com/phantom/scroll/ui/overlay/OverlayGeometryTest.kt
git commit -m "feat(ui): add pure OverlayGeometry (snap target / edge / clamp) with tests"
```

---

## Task 3：PanelState 枚举独立成文件

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/PanelState.kt`
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingPanel.kt`（移除枚举定义）

- [ ] **Step 1：创建独立 PanelState.kt**

Create `app/src/main/java/com/phantom/scroll/ui/overlay/PanelState.kt`:
```kotlin
package com.phantom.scroll.ui.overlay

/** Visibility states of the floating overlay. */
enum class PanelState {
    Expanded,
    Snapping,
    Collapsed
}
```

- [ ] **Step 2：从 FloatingPanel.kt 移除枚举定义**

编辑 `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingPanel.kt`，删除这段（位于文件顶部、`FloatingPanel` 函数之前）：
```kotlin
enum class PanelState {
    Expanded,
    Snapping,
    Collapsed
}
```
（`FloatingPanel.kt` 仍在同一 `com.phantom.scroll.ui.overlay` 包内，无需 import 即可引用迁移后的 `PanelState`，故 Compose 面板继续编译，直到 Task 9 删除它。）

- [ ] **Step 3：验证编译 + 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（PanelState 仅换了文件，FQN 不变；FloatingWindowController/Service 仍引用 `com.phantom.scroll.ui.overlay.PanelState`）

- [ ] **Step 4：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/PanelState.kt app/src/main/java/com/phantom/scroll/ui/overlay/FloatingPanel.kt
git commit -m "refactor(ui): move PanelState enum to its own file"
```

---

## Task 4：原生 drawable（复刻幽灵主题渐变）

**Files:**
- Create: `app/src/main/res/drawable/overlay_card_border.xml`
- Create: `app/src/main/res/drawable/overlay_handle_left.xml`
- Create: `app/src/main/res/drawable/overlay_handle_right.xml`

> 复刻 Compose 面板的视觉：卡片 = 暗色半透 + 16dp 圆角 + 1dp 青蓝→紫渐变边框；手柄 = 8x36dp，左/右两种渐变 + 单侧圆角。原生 shape 不支持渐变描边，故卡片边框用 layer-list（底层渐变 + 内缩 1dp 的暗色前景实现 1dp 渐变边）。

- [ ] **Step 1：卡片边框 layer-list**

Create `app/src/main/res/drawable/overlay_card_border.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- gradient border: cyan -> purple, 16dp rounded -->
    <item>
        <shape android:shape="rectangle">
            <gradient
                android:startColor="@color/phantom_cyan"
                android:endColor="@color/phantom_purple"
                android:angle="45" />
            <corners android:radius="16dp" />
        </shape>
    </item>
    <!-- inner dark-translucent card, inset 1dp to reveal the gradient border -->
    <item
        android:left="1dp"
        android:top="1dp"
        android:right="1dp"
        android:bottom="1dp">
        <shape android:shape="rectangle">
            <solid android:color="@color/dark_surface_translucent" />
            <corners android:radius="15dp" />
        </shape>
    </item>
</layer-list>
```

- [ ] **Step 2：左侧手柄渐变**

Create `app/src/main/res/drawable/overlay_handle_left.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:orientation="left_right"
        android:startColor="@color/phantom_cyan_80"
        android:endColor="@color/phantom_blue_50" />
    <corners
        android:topRightRadius="8dp"
        android:bottomRightRadius="8dp" />
</shape>
```

- [ ] **Step 3：右侧手柄渐变**

Create `app/src/main/res/drawable/overlay_handle_right.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:orientation="left_right"
        android:startColor="@color/phantom_blue_50"
        android:endColor="@color/phantom_cyan_80" />
    <corners
        android:topLeftRadius="8dp"
        android:bottomLeftRadius="8dp" />
</shape>
```

- [ ] **Step 4：验证资源编译**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（drawable 合法）

- [ ] **Step 5：Commit**

```bash
git add app/src/main/res/drawable/overlay_card_border.xml app/src/main/res/drawable/overlay_handle_left.xml app/src/main/res/drawable/overlay_handle_right.xml
git commit -m "feat(res): add overlay drawables (gradient card border + handle gradients)"
```

---

## Task 5：悬浮窗布局（面板 + 手柄）

**Files:**
- Create: `app/src/main/res/layout/overlay_panel.xml`
- Create: `app/src/main/res/layout/overlay_handle.xml`

> 面板宽度 130dp（对齐 Compose `panelWidth = 130.dp`）。Slider 用 Material `com.google.android.material.slider.Slider`，`labelBehavior="gone"` 隐藏气泡（极简），`stepSize` 默认 0（连续）。注意：布局会被 inflate 进已用 `ContextThemeWrapper(Material3)` 包裹的 `FloatingOverlayView`，故 Slider 主题可用。

- [ ] **Step 1：展开面板布局**

Create `app/src/main/res/layout/overlay_panel.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/panel_root"
    android:layout_width="130dp"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@drawable/overlay_card_border"
    android:padding="6dp">

    <!-- Title row: app name + quick-fold -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical">
        <TextView
            android:id="@+id/title_text"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="☽ Phantom"
            android:textColor="@color/phantom_cyan"
            android:textSize="11sp"
            android:textStyle="bold" />
        <FrameLayout
            android:id="@+id/fold_button"
            android:layout_width="16dp"
            android:layout_height="16dp"
            android:background="@drawable/overlay_fold_bg"
            android:clickable="true"
            android:focusable="true">
            <TextView
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="—"
                android:textColor="@color/text_primary"
                android:textSize="9sp"
                android:textStyle="bold" />
        </FrameLayout>
    </LinearLayout>

    <Space
        android:layout_width="match_parent"
        android:layout_height="4dp" />

    <!-- 速度 (duration) -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">
            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="速度"
                android:textColor="@color/text_secondary"
                android:textSize="9sp" />
            <TextView
                android:id="@+id/value_duration"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/phantom_cyan"
                android:textSize="10sp"
                android:textStyle="bold" />
        </LinearLayout>
        <com.google.android.material.slider.Slider
            android:id="@+id/slider_duration"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:valueFrom="200.0"
            android:valueTo="1500.0"
            android:value="500.0"
            app:labelBehavior="gone"
            app:thumbColor="@color/phantom_cyan"
            app:trackColorActive="@color/phantom_cyan"
            app:trackColorInactive="@color/text_tertiary" />
    </LinearLayout>

    <!-- 间隔 (interval) -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">
            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="间隔"
                android:textColor="@color/text_secondary"
                android:textSize="9sp" />
            <TextView
                android:id="@+id/value_interval"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/phantom_cyan"
                android:textSize="10sp"
                android:textStyle="bold" />
        </LinearLayout>
        <com.google.android.material.slider.Slider
            android:id="@+id/slider_interval"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:valueFrom="500.0"
            android:valueTo="10000.0"
            android:value="2000.0"
            app:labelBehavior="gone"
            app:thumbColor="@color/phantom_cyan"
            app:trackColorActive="@color/phantom_cyan"
            app:trackColorInactive="@color/text_tertiary" />
    </LinearLayout>

    <!-- 距离 (distanceRatio) -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical">
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal">
            <TextView
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:text="距离"
                android:textColor="@color/text_secondary"
                android:textSize="9sp" />
            <TextView
                android:id="@+id/value_distance"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="@color/phantom_cyan"
                android:textSize="10sp"
                android:textStyle="bold" />
        </LinearLayout>
        <com.google.android.material.slider.Slider
            android:id="@+id/slider_distance"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:valueFrom="0.30"
            android:valueTo="0.95"
            android:value="0.75"
            app:labelBehavior="gone"
            app:thumbColor="@color/phantom_cyan"
            app:trackColorActive="@color/phantom_cyan"
            app:trackColorInactive="@color/text_tertiary" />
    </LinearLayout>

    <Space
        android:layout_width="match_parent"
        android:layout_height="4dp" />

    <!-- Play / Pause: MaterialButton handles custom backgroundTint and corner radius natively.
         insetTop/insetBottom=0dp removes MaterialButton's default 6dp vertical insets
         so android:layout_height="26dp" yields an actual 26dp button. -->
    <com.google.android.material.button.MaterialButton
        android:id="@+id/play_button"
        android:layout_width="match_parent"
        android:layout_height="26dp"
        android:minWidth="0dp"
        android:minHeight="0dp"
        android:padding="0dp"
        android:insetTop="0dp"
        android:insetBottom="0dp"
        android:text="▶ 开始"
        android:textColor="@color/text_primary"
        android:textSize="10sp"
        android:textStyle="bold"
        app:cornerRadius="13dp"
        app:backgroundTint="@color/success_green" />
</LinearLayout>
```

- [ ] **Step 2：折叠手柄布局**

Create `app/src/main/res/layout/overlay_handle.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Outer 32x64dp touch target (parity: touchTargetWidth/Height). -->
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/handle_root"
    android:layout_width="32dp"
    android:layout_height="64dp"
    android:visibility="gone"
    android:clickable="true"
    android:focusable="true">
    <!-- Inner 8x36dp handle visual, anchored start by default (left edge). -->
    <FrameLayout
        android:id="@+id/handle_visual_wrap"
        android:layout_width="8dp"
        android:layout_height="36dp"
        android:layout_gravity="start|center_vertical">
        <View
            android:id="@+id/handle_visual"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:background="@drawable/overlay_handle_left" />
        <!-- small white vertical indicator bar (1.5x12dp, 70% alpha) -->
        <View
            android:layout_width="1.5dp"
            android:layout_height="12dp"
            android:layout_gravity="center"
            android:background="@color/white_70" />
    </FrameLayout>
</FrameLayout>
```

- [ ] **Step 3：补折叠按钮被引用的 drawable（折叠按钮圆形底）**

布局里折叠按钮（`fold_button`）引用 `@drawable/overlay_fold_bg`。播放按钮直接使用 `MaterialButton` 的属性来声明圆角与背景色，无需额外的 background drawable 资源。

Create `app/src/main/res/drawable/overlay_fold_bg.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/white_10" />
</shape>
```

- [ ] **Step 4：验证资源 + 编译**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5：Commit**

```bash
git add app/src/main/res/layout/overlay_panel.xml app/src/main/res/layout/overlay_handle.xml app/src/main/res/drawable/overlay_fold_bg.xml
git commit -m "feat(res): add overlay layouts (panel with Material Sliders + handle)"
```

---

## Task 6：FloatingOverlayView 原生自定义视图

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`

> 这是 Phase 2 的核心。`FrameLayout` 子类：构造时 inflate `overlay_panel` + `overlay_handle` 进自身；按 `PanelState` 切可见性；持专属 `CoroutineScope`，`onAttachedToWindow` 启动收集 `repository.global/isRunning/screenWidth/screenHeight` 与 `panelStateFlow`，`onDetachedFromWindow` 取消；滑块/按钮/手柄/折叠的点击与拖拽。为确保稳健性，`onInterceptTouchEvent` 过滤了交互式子 View 的触摸以防误触取消点击，且在 `ACTION_DOWN` 时取消正在运行的 `snapAnimator` 以防 Grab 冲突；`onTouchEvent` 显式消费 `ACTION_DOWN` 以解决面板背景拖拽失效问题；`applySettings` 采用防御性 `coerceIn` 边界截断。拖拽结束用 `ValueAnimator` 250ms 吸附。

- [ ] **Step 1：实现 FloatingOverlayView**

Create `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`:
```kotlin
package com.phantom.scroll.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.slider.Slider
import com.phantom.scroll.R
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.SettingsRepository
import com.phantom.scroll.util.PhantomLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Native floating overlay view. Renders the collapsed handle or the expanded panel based on
 * [PanelState], collects [SettingsRepository] flows to refresh itself imperatively, and emits
 * user interactions (slider/button/drag) back into the repository / panel-state flow / position
 * callback. Holds NO business logic beyond view↔state binding.
 *
 * MUST be constructed with a Material3-themed context (the overlay Slider requires it); the
 * controller wraps the service context in [R.style.Theme_PhantomScroll_Overlay].
 */
class FloatingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val logTag = "FloatingOverlayView"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var repository: SettingsRepository? = null
    private var panelStateFlow: MutableStateFlow<PanelState>? = null
    private var onUpdatePosition: ((x: Int, y: Int) -> Unit)? = null
    private var bound = false

    // panel child views
    private val panelRoot: View
    private val titleText: TextView
    private val foldButton: View
    private val durationSlider: Slider
    private val intervalSlider: Slider
    private val distanceSlider: Slider
    private val durationValue: TextView
    private val intervalValue: TextView
    private val distanceValue: TextView
    private val playButton: Button
    // handle child views
    private val handleRoot: View
    private val handleVisualWrap: View
    private val handleVisual: View

    // position / edge state
    private var currentX = 0
    private var currentY = 200
    private var isLeftEdge = true

    // drag / animation tracking
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var snapAnimator: ValueAnimator? = null

    // guard: programmatic slider setValue triggers the change listener; skip repo write then.
    private var applyingFromFlow = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_panel, this, true)
        LayoutInflater.from(context).inflate(R.layout.overlay_handle, this, true)

        panelRoot = findViewById(R.id.panel_root)
        titleText = findViewById(R.id.title_text)
        foldButton = findViewById(R.id.fold_button)
        durationSlider = findViewById(R.id.slider_duration)
        intervalSlider = findViewById(R.id.slider_interval)
        distanceSlider = findViewById(R.id.slider_distance)
        durationValue = findViewById(R.id.value_duration)
        intervalValue = findViewById(R.id.value_interval)
        distanceValue = findViewById(R.id.value_distance)
        playButton = findViewById(R.id.play_button)

        handleRoot = findViewById(R.id.handle_root)
        handleVisualWrap = findViewById(R.id.handle_visual_wrap)
        handleVisual = findViewById(R.id.handle_visual)

        // user → repository (only for genuine user changes)
        durationSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateSetting { it.copy(duration = value.toLong()) }
        }
        intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateSetting { it.copy(interval = value.toLong()) }
        }
        distanceSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !applyingFromFlow) updateSetting { it.copy(distanceRatio = value) }
        }

        handleRoot.setOnClickListener { panelStateFlow?.value = PanelState.Expanded }
        foldButton.setOnClickListener { panelStateFlow?.value = PanelState.Collapsed }
        playButton.setOnClickListener { repository?.toggleRunning() }

        applyState(PanelState.Expanded) // default: panel visible, handle hidden
        applyRunning(false)
    }

    /**
     * Wires dependencies. MUST be called after construction and before the view is added to the
     * WindowManager (so onAttachedToWindow can start collecting immediately).
     */
    fun bind(
        repository: SettingsRepository,
        panelStateFlow: MutableStateFlow<PanelState>,
        initialX: Int,
        initialY: Int,
        onUpdatePosition: (x: Int, y: Int) -> Unit
    ) {
        this.repository = repository
        this.panelStateFlow = panelStateFlow
        this.onUpdatePosition = onUpdatePosition
        this.currentX = initialX
        this.currentY = initialY
        this.bound = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bound) startCollecting()
    }

    override fun onDetachedFromWindow() {
        collectJob?.cancel()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun startCollecting() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        collectJob = scope.launch {
            launch { flow.collect { applyState(it) } }
            launch { repo.global.collect { applySettings(it) } }
            launch { repo.isRunning.collect { applyRunning(it) } }
            launch {
                combine(repo.screenWidth, repo.screenHeight) { w, h -> w to h }
                    .collect { repositionToBounds(it.first, it.second) }
            }
        }
    }

    private fun applyState(state: PanelState) {
        when (state) {
            PanelState.Collapsed -> {
                handleRoot.visibility = VISIBLE
                panelRoot.visibility = GONE
            }
            PanelState.Expanded, PanelState.Snapping -> {
                panelRoot.visibility = VISIBLE
                handleRoot.visibility = GONE
            }
        }
    }

    private fun applySettings(s: ScrollSettings) {
        applyingFromFlow = true
        // Defensive: clamp values to Slider boundaries in case stored data is out-of-bounds.
        durationSlider.value = s.duration.toFloat().coerceIn(durationSlider.valueFrom, durationSlider.valueTo)
        intervalSlider.value = s.interval.toFloat().coerceIn(intervalSlider.valueFrom, intervalSlider.valueTo)
        distanceSlider.value = s.distanceRatio.coerceIn(distanceSlider.valueFrom, distanceSlider.valueTo)
        applyingFromFlow = false
        durationValue.text = "${s.duration}ms"
        intervalValue.text = String.format("%.1fs", s.interval / 1000f)
        distanceValue.text = "${(s.distanceRatio * 100).toInt()}%"
    }

    private fun applyRunning(running: Boolean) {
        playButton.text = if (running) "⏸ 暂停" else "▶ 开始"
        val color = ResourcesCompat.getColor(
            resources,
            if (running) R.color.error_red else R.color.success_green,
            null
        )
        playButton.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    private fun updateSetting(transform: (ScrollSettings) -> ScrollSettings) {
        val repo = repository ?: return
        scope.launch { repo.updateGlobal(transform(repo.global.value)) }
    }

    private fun repositionToBounds(screenWidth: Int, screenHeight: Int) {
        // Use actual measured view dimensions; fallback to 130dp / WRAP height estimates.
        val panelW = panelRoot.width.takeIf { it > 0 }
            ?: (130f * resources.displayMetrics.density).toInt()
        val panelH = panelRoot.height.takeIf { it > 0 }
            ?: (200f * resources.displayMetrics.density).toInt()
        val nx = OverlayGeometry.clamp(currentX, 0, (screenWidth - panelW).coerceAtLeast(0))
        val ny = OverlayGeometry.clamp(currentY, 0, (screenHeight - panelH).coerceAtLeast(0))
        if (nx != currentX || ny != currentY) {
            currentX = nx
            currentY = ny
            onUpdatePosition?.invoke(nx, ny)
        }
    }

    private fun isTouchInsideView(ev: MotionEvent, view: View): Boolean {
        if (view.visibility != VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width &&
               ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
    }

    /**
     * Whole-panel drag: intercept once movement exceeds touch slop. Material Slider calls
     * requestDisallowInterceptTouchEvent while its thumb is dragged, so it still works; button
     * taps don't move so they aren't stolen.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                downRawX = ev.rawX; downRawY = ev.rawY
                lastRawX = ev.rawX; lastRawY = ev.rawY
                dragging = false

                // Do not intercept if user touches interactive children to avoid click cancellation or slider lag.
                val inInteractive = isTouchInsideView(ev, durationSlider) ||
                        isTouchInsideView(ev, intervalSlider) ||
                        isTouchInsideView(ev, distanceSlider) ||
                        isTouchInsideView(ev, playButton) ||
                        isTouchInsideView(ev, foldButton)
                if (inInteractive) return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                    dragging = true
                }
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Outside touch (FLAG_WATCH_OUTSIDE_TOUCH in Expanded state) → collapse.
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val flow = panelStateFlow
            if (flow != null && flow.value == PanelState.Expanded) {
                flow.value = PanelState.Collapsed
            }
            return true
        }
        // Consume ACTION_DOWN to ensure subsequent ACTION_MOVE/UP events are delivered to this window.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            return true
        }
        if (!dragging) return false
        val repo = repository ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val w = repo.screenWidth.value
                val h = repo.screenHeight.value
                val dx = (ev.rawX - lastRawX).toInt()
                val dy = (ev.rawY - lastRawY).toInt()
                // 130dp panel fallback in px; matches repositionToBounds logic.
                val panelW = panelRoot.width.takeIf { it > 0 }
                    ?: (130f * resources.displayMetrics.density).toInt()
                val panelH = panelRoot.height.takeIf { it > 0 }
                    ?: (200f * resources.displayMetrics.density).toInt()
                currentX = OverlayGeometry.clamp(currentX + dx, 0, w - panelW)
                currentY = OverlayGeometry.clamp(currentY + dy, 0, h - panelH)
                lastRawX = ev.rawX; lastRawY = ev.rawY
                onUpdatePosition?.invoke(currentX, currentY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                performSnap()
            }
        }
        return true
    }

    private fun performSnap() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        val screenWidth = repo.screenWidth.value
        val panelWidthPx = panelRoot.width.takeIf { it > 0 }
            ?: (130f * resources.displayMetrics.density).toInt()
        val target = OverlayGeometry.snapTarget(
            panelCenterX = currentX + panelWidthPx / 2,
            screenWidth = screenWidth,
            panelWidthPx = panelWidthPx
        )
        isLeftEdge = target.isLeftEdge
        updateHandleEdge()
        flow.value = PanelState.Snapping
        snapAnimator = ValueAnimator.ofInt(currentX, target.x).apply {
            duration = 250
            addUpdateListener { a ->
                currentX = a.animatedValue as Int
                onUpdatePosition?.invoke(currentX, currentY)
            }
        }
        snapAnimator?.start()
        // Collapse to handle once the snap finishes (small buffer over 250ms).
        postDelayed({ if (flow.value == PanelState.Snapping) flow.value = PanelState.Collapsed }, 270)
    }

    private fun updateHandleEdge() {
        handleVisual.background = ResourcesCompat.getDrawable(
            resources,
            if (isLeftEdge) R.drawable.overlay_handle_left else R.drawable.overlay_handle_right,
            null
        )
        val params = (handleVisualWrap.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (isLeftEdge) (android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
            else (android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
        }
        handleVisualWrap.layoutParams = params
    }
}
```

- [ ] **Step 2：验证编译（此时尚未接线到 controller，编译即可）**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（`FloatingOverlayView` 存在但无引用，仅编译校验）

- [ ] **Step 3：Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt
git commit -m "feat(ui): add native FloatingOverlayView (render + flow collection + drag/snap)"
```

---

## Task 7 + 8：重写 FloatingWindowController（原生）+ 重接 PhantomScrollService（移除 config 桥接）

> 这两个任务耦合（controller 构造签名从 `config` 改为 `repository`，服务调用点需同步改），按计划作为一个提交。完成后恢复全量编译。在两步完成前，全量编译会在 `PhantomScrollService` 的 `FloatingWindowController(this, config, ...)` 行报错（预期，Task 8 修复）。

**Files:**
- Replace: `app/src/main/java/com/phantom/scroll/service/FloatingWindowController.kt`
- Modify: `app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt`

- [ ] **Step 1：重写 FloatingWindowController（原生视图）**

Replace the ENTIRE contents of `app/src/main/java/com/phantom/scroll/service/FloatingWindowController.kt` with:
```kotlin
package com.phantom.scroll.service

import android.content.Context
import android.graphics.PixelFormat
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
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                x = 0
                y = 200
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
        val targetFlags = if (state == PanelState.Expanded) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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
```

- [ ] **Step 2：重接 PhantomScrollService（移除 config 桥接，controller 改传 repository）**

编辑 `app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt`：

(a) 删除 `import com.phantom.scroll.config.ScrollConfig` 行。

(b) 删除整个 `config` 字段及其 `@Deprecated` 注解：
```kotlin
    @Deprecated("Bridge to repository; removed in Phase 2. Use repository instead.")
    val config by lazy { ScrollConfig(repository, serviceScope) }
```

(c) 在 `onServiceConnected()` 中，把
```kotlin
        floatingWindowController = FloatingWindowController(this, config, serviceScope, panelStateFlow)
```
改为
```kotlin
        floatingWindowController = FloatingWindowController(this, repository, serviceScope, panelStateFlow)
```

（其余 `repository` / `panelStateFlow` / orchestrator / receiver 接线不变。）

- [ ] **Step 3：验证全量编译恢复**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（服务不再引用 `config`；`FloatingWindowController` 用 `repository`）

> 此时 `ScrollConfig`、`FloatingPanel`、`OverlayLifecycleOwner` 已无生产引用，但仍存在于源码（Task 9 删除）。编译应仍通过（未被引用的类不影响编译）。

- [ ] **Step 4：验证全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（注意：`ScrollConfigTest` 仍存在并测试 `ScrollConfig` —— 它仍能通过，因为 `ScrollConfig` 还没删；Task 9 一并删除）

- [ ] **Step 5：Commit（Task 7 + 8 一起）**

```bash
git add app/src/main/java/com/phantom/scroll/service/FloatingWindowController.kt app/src/main/java/com/phantom/scroll/service/PhantomScrollService.kt
git commit -m "refactor(service): FloatingWindowController uses native FloatingOverlayView; service drops ScrollConfig bridge"
```

---

## Task 9：删除 Compose 面板 + OverlayLifecycleOwner + ScrollConfig 桥接 + 其测试

**Files:**
- Delete: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingPanel.kt`
- Delete: `app/src/main/java/com/phantom/scroll/service/OverlayLifecycleOwner.kt`
- Delete: `app/src/main/java/com/phantom/scroll/config/ScrollConfig.kt`
- Delete: `app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt`

> 前置确认：Task 7+8 后，`FloatingPanel`（仅旧的 ComposeView `setContent` 用，已移除）、`OverlayLifecycleOwner`（仅 ComposeView 的 ViewTree 用）、`ScrollConfig`（服务不再构造）均无生产引用。删除前先 grep 确认无残留引用。

- [ ] **Step 1：确认无残留引用**

Run（两条，分别检查生产代码和测试代码）:
```bash
grep -rn "FloatingPanel\|OverlayLifecycleOwner\|ScrollConfig\|ConfigSnapshot" app/src/main
grep -rn "ScrollConfig\|ConfigSnapshot" app/src/test
```
Expected:
- `app/src/main`：仅命中待删除文件自身（`FloatingPanel.kt`、`OverlayLifecycleOwner.kt`、`ScrollConfig.kt`）以及 `ScrollSettings.kt` 中一行注释（`/** 与旧 ScrollConfig 默认值一致… */`，无需处理）。若在 `main` 其它 `.kt` 文件命中 `import` 或代码引用，说明 Task 7/8 有遗漏，需先处理。
- `app/src/test`：仅命中 `ScrollConfigTest.kt` 自身（即将一并删除）。`ui/theme/Theme.kt` 中的 `OverlayTheme` 若仍被 `MainActivity` 引用则保留（不在删除范围）。

- [ ] **Step 2：删除四个文件**

```bash
git rm app/src/main/java/com/phantom/scroll/ui/overlay/FloatingPanel.kt
git rm app/src/main/java/com/phantom/scroll/service/OverlayLifecycleOwner.kt
git rm app/src/main/java/com/phantom/scroll/config/ScrollConfig.kt
git rm app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt
```

- [ ] **Step 3：验证编译 + 全量单测**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS。测试总数 = Phase 1 的 30 + Task 2 新增 OverlayGeometryTest 6 − 本任务删除 ScrollConfigTest 2 = **34**。明细：SettingsRepositoryTest 11、FailurePolicyTest 3、ScreenStateCoordinatorTest 4、GestureEngineTest 3、ScrollSettingsTest 1、MigrationMapperTest 3、ProfileKeyParsingTest 3、OverlayGeometryTest 6 = 34。报告实际数字。

> `app/src/main/java/com/phantom/scroll/config/` 目录删除后若为空可保留空目录或一并删除（Git 不跟踪空目录，无需额外操作）。

- [ ] **Step 4：Commit**

```bash
git commit -m "refactor: delete Compose FloatingPanel, OverlayLifecycleOwner, ScrollConfig bridge (Phase 2 cleanup)"
```

---

## Task 10：proguard 规则审查 + Release 构建

**Files:**
- Modify (if needed): `app/proguard-rules.pro`

- [ ] **Step 1：审查现有 proguard 规则**

Read `app/proguard-rules.pro`。确认是否需要为 Material `Slider`（`com.google.android.material.slider.*`）加 keep。Material Components 库自带 consumer ProGuard 规则，通常**无需**额外 keep。若 Task 11 的 release 构建通过则跳过 Step 2。

- [ ] **Step 2：（仅当 Step 1/11 发现需要时）追加 Slider keep**

若 release 构建报 Material Slider 相关混淆错误，在 `app/proguard-rules.pro` 追加：
```proguard
# Material Components Slider (overlay) — normally covered by the library's consumer rules
-keep class com.google.android.material.slider.** { *; }
```

- [ ] **Step 3：Release 构建**

Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL`（R8/minify + 资源缩减通过；`keystore.properties` 存在则用真实签名）。确认 `app-release.apk` 生成。

- [ ] **Step 4：Commit（仅当 Step 2 改了 proguard；否则跳过本步，无改动可提交）**

```bash
git add app/proguard-rules.pro
git commit -m "build: keep Material Slider in R8 release build"
```

---

## Task 11：Phase 2 对齐验收

**Files:** 无代码改动；记录验收结果。

- [ ] **Step 1：全量单测 + Release 构建绿**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`，全部通过（34 个：Phase 1 原有 28 个几何/逻辑测试 + 新 `OverlayGeometryTest` 6 个，无 `ScrollConfigTest`）。
Run: `./gradlew assembleRelease`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2：真机/模拟器对齐验收清单（逐项过）**

- [ ] App 启动正常；权限页（Compose `MainScreen`）正常显示与刷新。
- [ ] 授予权限后，悬浮窗**手柄**出现在屏幕边缘（默认左侧）。
- [ ] 点击手柄 → 展开**面板**（青蓝→紫渐变边框、暗色半透卡片、☽ Phantom 标题、三个滑块、播放按钮）。
- [ ] 视觉与旧 Compose 面板一致或可接受接近（配色/圆角/渐变）。
- [ ] 拖动"速度/间隔/距离"三个 Slider：数值文本实时变化，且滑动行为随之改变（**连续值无步进跳变**，无 tooltip 气泡）。
- [ ] 点击"开始/暂停"：自动滑动启停，**通知栏状态同步**，按钮文字/颜色在"▶ 开始（绿）/⏸ 暂停（红）"间切换。
- [ ] **拖拽**面板 → 松手 → 250ms 动画**吸附**到最近屏幕边缘（基于面板中心 vs 屏幕中线）→ 自动**折叠**为手柄；手柄渐变/圆角随吸附边（左/右）正确切换。
- [ ] 点击面板**外部**（`ACTION_OUTSIDE`）→ 折叠为手柄。
- [ ] **熄屏自动暂停**；**亮屏解锁后**按熄屏前状态恢复（熄屏前若已暂停，亮屏不自动恢复）。
- [ ] **旋屏**后手柄/面板位置正确重定位（不越界、不消失）。
- [ ] 杀进程后重启服务，duration/interval/distanceRatio 三项配置保留（Phase 1 DataStore 迁移路径仍工作）。
- [ ] **内存验收**：阅读期间 `adb shell dumpsys meminfo com.phantom.scroll` 确认悬浮窗不再常驻 Compose 运行时（对比 Phase 1，`.dex` / ViewRoot 相关内存下降，无 `androidx.compose.*` 常驻分配峰值）。

- [ ] **Step 3：把验收结果记入闸门标记提交**

```bash
git commit --allow-empty -m "chore: Phase 2 native overlay gate passed (parity verified, Compose runtime removed)"
```

---

## Phase 2 完成判据

1. `./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleRelease` 均 `BUILD SUCCESSFUL`。
2. 对齐验收清单全部通过：外观、Slider 实时性（连续无步进）、播放/通知联动、拖拽吸附折叠、外部点击折叠、锁屏恢复、旋屏重定位、配置持久化。
3. 阅读期间悬浮窗不再常驻 Compose 运行时（`dumpsys meminfo` 验证）—— 本轮性能头号收益。
4. Compose 仅保留给 `MainActivity`/`MainScreen`（权限页仍用 Compose 且正常）。
5. `FloatingPanel.kt` / `OverlayLifecycleOwner.kt` / `ScrollConfig.kt` / `ScrollConfigTest.kt` 已删除。

完成后即可撰写 **Phase 3 计划**（产品化功能：场景预设 / 运行统计 / 滚动方向 / 按 App 记忆）。Phase 3 的 `incrementStats` 调用、per-app 检测将直接复用 Phase 1 的 `SettingsRepository` 与 Phase 2 的原生悬浮窗。
