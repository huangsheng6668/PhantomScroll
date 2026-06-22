# PhantomScroll 悬浮窗重新优化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 PhantomScroll 悬浮窗从深色/青色紧凑面板重新优化为浅色/绿色分区卡片式面板（240dp，2×2 参数网格 + 档位展开精调 + 56dp 气泡折叠 + ⚙ 设置子面板），主界面与通知同步换肤，纯 UI 层改造、零引擎/数据改动。

**Architecture:** 方案 B（组件化）。抽出可复用视图单元 `ParamCellView`（2×2 网格单格）、`BubbleView`（折叠气泡），`FloatingOverlayView` 退化为编排者；参数档位/角标格式化抽成纯逻辑 `ParamSteps`/`BadgeFormatter` 单测；宽度魔法数提取为 `OverlayGeometry` 常量。保留原生 View（不回 Compose），状态机 `PanelState` 不变。构建全程保持可编译：新组件先以"未接线"方式加入，最后一步原子替换面板布局+主视图+删除旧手柄。

**Tech Stack:** Kotlin + Coroutines/Flow，原生 Android View（WindowManager + Material Components Slider/Switch/MaterialButton），XML 布局/drawable，JUnit4 + Mockito 单测。Gradle wrapper（`./gradlew`）。

## Global Constraints

- 纯 UI/交互层改造：**不得触碰**手势引擎（`GestureEngine`/`SpeedCurve`/`ScrollOrchestrator`）、`data/` 全部、DataStore key、`FloatingWindowController.kt`。
- 方向**仅 ↑/↓**（`ScrollDirection.UP/DOWN`），不做横向/四向。
- 距离内部仍为 `distanceRatio`(0.30–0.95)，仅 UI 换算等效 px。
- 视图层**不引入 Robolectric**（与现 `FloatingOverlayView` 无测试一致）；仅纯逻辑写 JVM 单测。
- 测试命令：`./gradlew test`（76+ 现有用例须全绿）。编译命令：`./gradlew assembleDebug`。
- 主题：浅色底 + 绿色强调（`overlay_accent #1E9E55`）+ 琥珀暂停态（`overlay_warning #D98324`）。启动器图标**不动**（cyan 令牌仅供图标 drawable 复用）。
- `PanelState` 枚举不变（Expanded / Snapping / Collapsed）。
- 面板宽 `240dp`、折叠气泡宽 `56dp`，均经 `OverlayGeometry.PANEL_WIDTH_DP` / `COLLAPSED_WIDTH_DP` 引用，不得再硬编码 `130f`/`32f`。
- 分支：`overlay-light-green-redesign`（已创建并已提交 spec）。每个 Task 末尾 commit。

## File Structure

**新增（纯逻辑，可单测）**
- `app/src/main/java/com/phantom/scroll/ui/overlay/ParamSteps.kt` — duration/interval/distanceRatio → 友好档位文本
- `app/src/main/java/com/phantom/scroll/ui/overlay/BadgeFormatter.kt` — swipeCount → 角标文本（"N"/"99+"）

**新增（视图层，编译验证）**
- `app/src/main/java/com/phantom/scroll/ui/overlay/ParamCellView.kt` — 2×2 网格单格（标签+档位+展开 Slider）
- `app/src/main/java/com/phantom/scroll/ui/overlay/BubbleView.kt` — 56dp 折叠气泡 + 计数角标
- `app/src/main/res/layout/overlay_panel.xml`（重写）、`overlay_bubble.xml`、`overlay_settings.xml`、`overlay_param_cell.xml`
- drawables：`overlay_bubble_bg`、`overlay_status_pill_running`、`overlay_status_pill_paused`、`overlay_param_cell_bg`；重写 `overlay_card_border`、改色 `overlay_chip_bg`/`overlay_chip_bg_selected`

**修改**
- `OverlayGeometry.kt`（加 `PANEL_WIDTH_DP=240` / `COLLAPSED_WIDTH_DP=56`）
- `FloatingOverlayView.kt`（瘦身重写为编排者）
- `res/values/colors.xml`（加 overlay_* 令牌）、`ui/theme/Color.kt`、`ui/theme/Theme.kt`、`res/values/themes.xml`、`notification/NotificationHelper.kt`（`setColor`）、`data/ScrollSettings.kt`（KDoc 修正）

**删除**：`res/layout/overlay_handle.xml`

**新增测试**：`ParamStepsTest.kt`、`BadgeFormatterTest.kt`；更新 `OverlayGeometryTest.kt`

---

### Task 1: 纯逻辑 ParamSteps + 单测

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/ParamSteps.kt`
- Test: `app/src/test/java/com/phantom/scroll/ui/overlay/ParamStepsTest.kt`

**Interfaces:**
- Produces: `ParamSteps.toSpeedLabel(durationMs: Long): String`、`toIntervalLabel(intervalMs: Long): String`、`toDistanceLabel(distanceRatio: Float, screenH: Int): String`（Task 8 ParamCellView 使用）

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/phantom/scroll/ui/overlay/ParamStepsTest.kt`:

```kotlin
package com.phantom.scroll.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class ParamStepsTest {

    @Test
    fun speed_lower_duration_is_higher_multiplier() {
        assertEquals("极速 8x", ParamSteps.toSpeedLabel(150))   // <400 极速
        assertEquals("极速 8x", ParamSteps.toSpeedLabel(399))
        assertEquals("快速 5x", ParamSteps.toSpeedLabel(400))   // 边界 400→快速
        assertEquals("快速 5x", ParamSteps.toSpeedLabel(699))
        assertEquals("中速 3x", ParamSteps.toSpeedLabel(700))   // 边界 700→中速
        assertEquals("中速 3x", ParamSteps.toSpeedLabel(1049))
        assertEquals("慢速 1x", ParamSteps.toSpeedLabel(1050))  // 边界 1050→慢速
        assertEquals("慢速 1x", ParamSteps.toSpeedLabel(1500))
    }

    @Test
    fun interval_formats_seconds_one_decimal() {
        assertEquals("0.5s", ParamSteps.toIntervalLabel(500))
        assertEquals("2.0s", ParamSteps.toIntervalLabel(2000))
        assertEquals("10.0s", ParamSteps.toIntervalLabel(10000))
    }

    @Test
    fun distance_band_and_equivalent_px() {
        // screenH 2400px
        assertEquals("短距 720px", ParamSteps.toDistanceLabel(0.30f, 2400))  // 0.3*2400=720, <0.5 短
        assertEquals("中距 1200px", ParamSteps.toDistanceLabel(0.50f, 2400)) // 边界 0.5→中
        assertEquals("中距 1799px", ParamSteps.toDistanceLabel(0.7496f, 2400))
        assertEquals("长距 1800px", ParamSteps.toDistanceLabel(0.75f, 2400)) // 边界 0.75→长
        assertEquals("长距 2280px", ParamSteps.toDistanceLabel(0.95f, 2400))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.phantom.scroll.ui.overlay.ParamStepsTest"`
Expected: FAIL（`ParamSteps` 未定义，编译错误 `unresolved reference`）

- [ ] **Step 3: 写最小实现**

Create `app/src/main/java/com/phantom/scroll/ui/overlay/ParamSteps.kt`:

```kotlin
package com.phantom.scroll.ui.overlay

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure label resolvers for the 2×2 param grid. Map continuous [com.phantom.scroll.data.ScrollSettings]
 * values to friendly step labels shown in the collapsed grid cell. Side-effect-free, JVM-unit-testable.
 */
object ParamSteps {

    /**
     * duration(ms) → "中速 3x". Lower duration = faster scroll = higher multiplier.
     * Bands: <400 极速8x · 400–699 快速5x · 700–1049 中速3x · ≥1050 慢速1x.
     */
    fun toSpeedLabel(durationMs: Long): String {
        val (label, mult) = when {
            durationMs < 400 -> "极速" to 8
            durationMs < 700 -> "快速" to 5
            durationMs < 1050 -> "中速" to 3
            else -> "慢速" to 1
        }
        return "$label ${mult}x"
    }

    /** interval(ms) → "2.5s" (one decimal, Locale.ROOT for test stability). */
    fun toIntervalLabel(intervalMs: Long): String =
        String.format(Locale.ROOT, "%.1fs", intervalMs / 1000.0)

    /**
     * distanceRatio + screen height(px) → "中距 580px".
     * Bands: <0.5 短距 · 0.5–0.7499 中距 · ≥0.75 长距. px = ratio × screenH, rounded.
     */
    fun toDistanceLabel(distanceRatio: Float, screenH: Int): String {
        val band = when {
            distanceRatio < 0.5f -> "短距"
            distanceRatio < 0.75f -> "中距"
            else -> "长距"
        }
        val px = (distanceRatio * screenH).roundToInt()
        return "$band ${px}px"
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "com.phantom.scroll.ui.overlay.ParamStepsTest"`
Expected: PASS（3 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/ParamSteps.kt \
        app/src/test/java/com/phantom/scroll/ui/overlay/ParamStepsTest.kt
git commit -m "feat(overlay): add ParamSteps pure label resolvers with unit tests"
```

---

### Task 2: 纯逻辑 BadgeFormatter + 单测

**Files:**
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/BadgeFormatter.kt`
- Test: `app/src/test/java/com/phantom/scroll/ui/overlay/BadgeFormatterTest.kt`

**Interfaces:**
- Produces: `BadgeFormatter.format(count: Int): String`（Task 6 BubbleView 使用）

- [ ] **Step 1: 写失败测试**

Create `app/src/test/java/com/phantom/scroll/ui/overlay/BadgeFormatterTest.kt`:

```kotlin
package com.phantom.scroll.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class BadgeFormatterTest {
    @Test
    fun zero_and_small_counts_render_verbatim() {
        assertEquals("0", BadgeFormatter.format(0))
        assertEquals("1", BadgeFormatter.format(1))
        assertEquals("99", BadgeFormatter.format(99))
    }

    @Test
    fun counts_over_99_capped_to_99plus() {
        assertEquals("99+", BadgeFormatter.format(100))
        assertEquals("99+", BadgeFormatter.format(9999))
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.phantom.scroll.ui.overlay.BadgeFormatterTest"`
Expected: FAIL（`BadgeFormatter` 未定义）

- [ ] **Step 3: 写最小实现**

Create `app/src/main/java/com/phantom/scroll/ui/overlay/BadgeFormatter.kt`:

```kotlin
package com.phantom.scroll.ui.overlay

/**
 * Pure formatter for the collapsed bubble's count badge. Caps display at "99+".
 * JVM-unit-testable.
 */
object BadgeFormatter {
    fun format(count: Int): String = if (count > 99) "99+" else count.toString()
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "com.phantom.scroll.ui.overlay.BadgeFormatterTest"`
Expected: PASS（2 tests）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/BadgeFormatter.kt \
        app/src/test/java/com/phantom/scroll/ui/overlay/BadgeFormatterTest.kt
git commit -m "feat(overlay): add BadgeFormatter (99+ cap) with unit tests"
```

---

### Task 3: OverlayGeometry 宽度常量 + 测试

**背景：** `OverlayGeometry.snapTarget/edgeX/clamp` 已接受 `widthPx` 入参（无需改签名）。本任务仅新增面板/折叠宽度 DP 常量，供 Task 9 的 `FloatingOverlayView` 取代硬编码 `130f`/`32f`。

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/OverlayGeometry.kt`
- Test: `app/src/test/java/com/phantom/scroll/ui/overlay/OverlayGeometryTest.kt`

**Interfaces:**
- Produces: `OverlayGeometry.PANEL_WIDTH_DP = 240`、`OverlayGeometry.COLLAPSED_WIDTH_DP = 56`（Task 9 使用）

- [ ] **Step 1: 写失败测试**

Append to `OverlayGeometryTest.kt`（在 class 闭合 `}` 之前）：

```kotlin
    @Test
    fun panel_and_collapse_width_constants_match_redesign() {
        assertEquals(240, OverlayGeometry.PANEL_WIDTH_DP)
        assertEquals(56, OverlayGeometry.COLLAPSED_WIDTH_DP)
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew test --tests "com.phantom.scroll.ui.overlay.OverlayGeometryTest"`
Expected: FAIL（`PANEL_WIDTH_DP` 未定义）

- [ ] **Step 3: 加常量到 OverlayGeometry**

Edit `OverlayGeometry.kt`，在 `object OverlayGeometry {` 之后、`data class SnapTarget` 之前插入：

```kotlin
    /** Redesigned panel width (dp). See docs/superpowers/specs/2026-06-22-overlay-redesign-design.md. */
    const val PANEL_WIDTH_DP = 240

    /** Redesigned collapsed bubble width (dp). */
    const val COLLAPSED_WIDTH_DP = 56
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew test --tests "com.phantom.scroll.ui.overlay.OverlayGeometryTest"`
Expected: PASS（7 tests，含新增 1 个；现有 6 个几何测试不受影响——它们用任意 width 200）

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/phantom/scroll/ui/overlay/OverlayGeometry.kt \
        app/src/test/java/com/phantom/scroll/ui/overlay/OverlayGeometryTest.kt
git commit -m "feat(overlay): add PANEL/COLLAPSED_WIDTH_DP constants (240/56)"
```

---

### Task 4: 主题令牌 + 杂项修正（colors / Compose theme / 通知 / KDoc）

**Files:**
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/java/com/phantom/scroll/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/phantom/scroll/ui/theme/Theme.kt`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/java/com/phantom/scroll/notification/NotificationHelper.kt`
- Modify: `app/src/main/java/com/phantom/scroll/data/ScrollSettings.kt`

**Interfaces:**
- Produces: `@color/overlay_*` 资源（Task 5+ drawables/layout 引用）、Compose `PhantomScrollTheme` 浅色/绿

- [ ] **Step 1: colors.xml 加 overlay_* 令牌**

在 `colors.xml` 的 `<resources>` 内追加（保留现有 cyan/dark 令牌供图标复用）：

```xml
    <!-- ===== Overlay redesign (light/green) ===== -->
    <color name="overlay_surface">#FFFFFFFF</color>
    <color name="overlay_surface_2">#FFF4F4F6</color>
    <color name="overlay_fg">#FF2A2A33</color>
    <color name="overlay_muted">#FF6E6E76</color>
    <color name="overlay_subtle">#FF888892</color>
    <color name="overlay_border">#FFE1E1E6</color>
    <color name="overlay_border_2">#FFECECEF</color>
    <color name="overlay_accent">#FF1E9E55</color>
    <color name="overlay_accent_2">#FF3FB373</color>
    <color name="overlay_accent_soft">#FFE8F5EC</color>
    <color name="overlay_warning">#FFD98324</color>
    <color name="overlay_warning_soft">#FFFBEFD9</color>
    <color name="overlay_on_accent">#FFFFFFFF</color>
```

- [ ] **Step 2: Color.kt 加浅色/绿 Compose 令牌**

在 `Color.kt` 末尾追加：

```kotlin
// ===== Overlay redesign (light/green) =====
val LightBackground = Color(0xFFF4F4F6)
val LightSurface = Color(0xFFFFFFFF)
val OverlayGreen = Color(0xFF1E9E55)
val OverlayGreenSoft = Color(0xFFE8F5EC)
val OverlayWarningOrange = Color(0xFFD98324)
val OnLight = Color(0xFF2A2A33)
val OnLightMuted = Color(0xFF6E6E76)
```

- [ ] **Step 3: Theme.kt 换 lightColorScheme 并删除死代码 OverlayTheme**

整文件替换为：

```kotlin
package com.phantom.scroll.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = OverlayGreen,
    secondary = OverlayGreen,
    tertiary = OverlayWarningOrange,
    background = LightBackground,
    surface = LightSurface,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = OnLight,
    onSurface = OnLight
)

@Composable
fun PhantomScrollTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}
```

（移除 `OverlayTheme` —— 已确认无源码引用；移除未再用的 `darkColorScheme`/`Activity`/`SideEffect`/`toArgb`/`LocalView` import。）

- [ ] **Step 4: themes.xml 把 overlay Material3 主题改为浅色/绿**

把 `Theme.PhantomScroll.Overlay` style 改为：

```xml
    <style name="Theme.PhantomScroll.Overlay" parent="Theme.Material3.Light.NoActionBar">
        <item name="colorPrimary">@color/overlay_accent</item>
        <item name="colorOnPrimary">@color/overlay_on_accent</item>
        <item name="colorPrimaryContainer">@color/overlay_accent_soft</item>
        <item name="android:colorBackground">@color/overlay_surface</item>
    </style>
```

- [ ] **Step 5: NotificationHelper 加 setColor**

在 `NotificationHelper.kt` 顶部 import 区加：

```kotlin
import androidx.core.content.ContextCompat
```

在 `buildNotification` 的 builder 链中，`.setSmallIcon(...)` 之后加一行：

```kotlin
            .setColor(ContextCompat.getColor(context, R.color.overlay_accent))
```

- [ ] **Step 6: ScrollSettings KDoc 范围修正**

把 `ScrollSettings.kt` 第 5 行 `@param duration 单次滑动时长 ms，范围 200..1500` 改为：

```kotlin
 * @param duration 单次滑动时长 ms，范围 150..1500
```

- [ ] **Step 7: 编译 + 全量测试**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

Run: `./gradlew test`
Expected: 全绿（现有 76+ 用例不受影响；Theme.kt 改动后若 `MainScreen`/`MainActivity` 引用 `PhantomScrollTheme` 仍正常编译——它仍存在，仅换浅色）

- [ ] **Step 8: Commit**

```bash
git add app/src/main/res/values/colors.xml \
        app/src/main/java/com/phantom/scroll/ui/theme/Color.kt \
        app/src/main/java/com/phantom/scroll/ui/theme/Theme.kt \
        app/src/main/res/values/themes.xml \
        app/src/main/java/com/phantom/scroll/notification/NotificationHelper.kt \
        app/src/main/java/com/phantom/scroll/data/ScrollSettings.kt
git commit -m "feat(theme): rebrand to light/green (colors, Compose theme, notification accent); fix ScrollSettings KDoc"
```

---

### Task 5: 浅色 drawables

**Files:**
- Create: `app/src/main/res/drawable/overlay_bubble_bg.xml`
- Create: `app/src/main/res/drawable/overlay_status_pill_running.xml`
- Create: `app/src/main/res/drawable/overlay_status_pill_paused.xml`
- Create: `app/src/main/res/drawable/overlay_param_cell_bg.xml`
- Modify (rewrite): `app/src/main/res/drawable/overlay_card_border.xml`
- Modify (recolor): `app/src/main/res/drawable/overlay_chip_bg.xml`
- Modify (recolor): `app/src/main/res/drawable/overlay_chip_bg_selected.xml`

- [ ] **Step 1: 气泡背景**

Create `overlay_bubble_bg.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/overlay_surface" />
    <stroke android:width="1.5dp" android:color="@color/overlay_accent" />
</shape>
```

- [ ] **Step 2: 状态胶囊背景（运行/暂停）**

Create `overlay_status_pill_running.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/overlay_accent_soft" />
    <corners android:radius="999dp" />
</shape>
```

Create `overlay_status_pill_paused.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/overlay_warning_soft" />
    <corners android:radius="999dp" />
</shape>
```

- [ ] **Step 3: 参数格背景**

Create `overlay_param_cell_bg.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/overlay_surface" />
    <corners android:radius="10dp" />
</shape>
```

- [ ] **Step 4: 重写面板卡片边框（浅色）**

整文件替换 `overlay_card_border.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- 1dp light border -->
    <item>
        <shape android:shape="rectangle">
            <solid android:color="@color/overlay_border" />
            <corners android:radius="18dp" />
        </shape>
    </item>
    <!-- inner surface, inset 1dp to reveal border -->
    <item android:left="1dp" android:top="1dp" android:right="1dp" android:bottom="1dp">
        <shape android:shape="rectangle">
            <solid android:color="@color/overlay_surface" />
            <corners android:radius="17dp" />
        </shape>
    </item>
</layer-list>
```

- [ ] **Step 5: 改色 chip 背景**

整文件替换 `overlay_chip_bg.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/overlay_surface_2" />
    <corners android:radius="8dp" />
</shape>
```

整文件替换 `overlay_chip_bg_selected.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/overlay_accent_soft" />
    <corners android:radius="8dp" />
</shape>
```

- [ ] **Step 6: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（drawables 引用的颜色已在 Task 4 定义）

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/drawable/overlay_bubble_bg.xml \
        app/src/main/res/drawable/overlay_status_pill_running.xml \
        app/src/main/res/drawable/overlay_status_pill_paused.xml \
        app/src/main/res/drawable/overlay_param_cell_bg.xml \
        app/src/main/res/drawable/overlay_card_border.xml \
        app/src/main/res/drawable/overlay_chip_bg.xml \
        app/src/main/res/drawable/overlay_chip_bg_selected.xml
git commit -m "feat(overlay): add light/green drawables (bubble, status pills, card border, chips)"
```

---

### Task 6: BubbleView + overlay_bubble.xml（未接线）

**Files:**
- Create: `app/src/main/res/layout/overlay_bubble.xml`
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/BubbleView.kt`

**Interfaces:**
- Consumes: `BadgeFormatter.format`（Task 2）
- Produces: `BubbleView.setCount(count: Int)`、`BubbleView.setIconTint(colorRes: Int)`（Task 9 使用）

- [ ] **Step 1: 气泡布局**

Create `overlay_bubble.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/bubble_root"
    android:layout_width="56dp"
    android:layout_height="56dp"
    android:visibility="gone"
    android:clickable="true"
    android:focusable="true">
    <!-- Ghost icon (green stroke nod to Phantom brand) -->
    <TextView
        android:id="@+id/bubble_icon"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:text="👻"
        android:textSize="26sp" />
    <!-- Count badge, top-end -->
    <TextView
        android:id="@+id/bubble_count"
        android:layout_width="wrap_content"
        android:layout_height="20dp"
        android:layout_gravity="top|end"
        android:layout_marginTop="-2dp"
        android:layout_marginEnd="-2dp"
        android:background="@drawable/overlay_status_pill_running"
        android:paddingStart="5dp"
        android:paddingEnd="5dp"
        android:gravity="center"
        android:minWidth="20dp"
        android:text="0"
        android:textColor="@color/overlay_accent"
        android:textSize="10sp"
        android:textStyle="bold"
        android:fontFamily="monospace"
        android:includeFontPadding="false" />
</FrameLayout>
```

- [ ] **Step 2: BubbleView**

Create `BubbleView.kt`:

```kotlin
package com.phantom.scroll.ui.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.TextView
import com.phantom.scroll.R

/**
 * Collapsed overlay bubble: 56dp circle + live count badge. Imperative refresh only;
 * [setCount] short-circuits identical text to avoid layout passes at ~0.6 swipe/sec.
 */
class BubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val countView: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_bubble, this, true)
        countView = findViewById(R.id.bubble_count)
    }

    /** Renders [count] via [BadgeFormatter]; skips setText when unchanged. */
    fun setCount(count: Int) {
        val text = BadgeFormatter.format(count)
        if (countView.text.toString() != text) countView.text = text
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL（BubbleView 未被引用，但作为类型可独立编译）

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/overlay_bubble.xml \
        app/src/main/java/com/phantom/scroll/ui/overlay/BubbleView.kt
git commit -m "feat(overlay): add BubbleView (56dp bubble + live count badge)"
```

---

### Task 7: overlay_settings.xml（未接线）

**Files:**
- Create: `app/src/main/res/layout/overlay_settings.xml`

- [ ] **Step 1: 设置子面板布局**

Create `overlay_settings.xml`（预设芯片 + 忘记当前 App；由 Task 9 的 panel 布局 `<include>` 进同一面板，默认 `GONE`）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/settings_root"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:visibility="gone"
    android:paddingTop="4dp"
    android:paddingBottom="4dp">

    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="场景预设"
        android:textColor="@color/overlay_subtle"
        android:textSize="9.5sp"
        android:fontFamily="monospace"
        android:textAllCaps="true"
        android:letterSpacing="0.08"
        android:layout_marginBottom="4dp" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center">
        <TextView
            android:id="@+id/chip_novel"
            android:layout_width="0dp"
            android:layout_height="24dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="小说"
            android:textColor="@color/overlay_fg"
            android:textSize="10sp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true" />
        <Space android:layout_width="6dp" android:layout_height="match_parent" />
        <TextView
            android:id="@+id/chip_comic"
            android:layout_width="0dp"
            android:layout_height="24dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="漫画"
            android:textColor="@color/overlay_fg"
            android:textSize="10sp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true" />
        <Space android:layout_width="6dp" android:layout_height="match_parent" />
        <TextView
            android:id="@+id/chip_custom"
            android:layout_width="0dp"
            android:layout_height="24dp"
            android:layout_weight="1"
            android:gravity="center"
            android:text="自定义"
            android:textColor="@color/overlay_fg"
            android:textSize="10sp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true" />
    </LinearLayout>

    <TextView
        android:id="@+id/forget_app_btn"
        android:layout_width="match_parent"
        android:layout_height="28dp"
        android:layout_marginTop="6dp"
        android:gravity="center"
        android:text="忘记当前 App 配置"
        android:textColor="@color/overlay_warning"
        android:textSize="10sp"
        android:background="@drawable/overlay_chip_bg"
        android:clickable="true"
        android:focusable="true" />
</LinearLayout>
```

- [ ] **Step 2: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/layout/overlay_settings.xml
git commit -m "feat(overlay): add settings sub-panel layout (presets + forget-app)"
```

---

### Task 8: ParamCellView + overlay_param_cell.xml（未接线）

**Files:**
- Create: `app/src/main/res/layout/overlay_param_cell.xml`
- Create: `app/src/main/java/com/phantom/scroll/ui/overlay/ParamCellView.kt`

**Interfaces:**
- Consumes: `ParamSteps`（Task 1，经 `stepResolver` 闭包传入）
- Produces: `ParamCellView.configure(label, valueFrom, valueTo, stepResolver)`、`setValue(v, fromFlow)`、`onUserChange: ((Float)->Unit)?`、`slider: Slider`（Task 9 用于 disallowIntercept）、`isExpanded`（Task 9 读取）

- [ ] **Step 1: 单格布局**

Create `overlay_param_cell.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/param_cell_root"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@drawable/overlay_param_cell_bg"
    android:padding="9dp"
    android:clickable="true"
    android:focusable="true">
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">
        <TextView
            android:id="@+id/param_label"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:textColor="@color/overlay_muted"
            android:textSize="11sp" />
        <TextView
            android:id="@+id/param_value"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="@color/overlay_fg"
            android:textSize="12sp"
            android:textStyle="bold"
            android:fontFamily="monospace" />
    </LinearLayout>
    <com.google.android.material.slider.Slider
        android:id="@+id/param_slider"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:visibility="gone"
        app:labelBehavior="gone"
        app:thumbColor="@color/overlay_accent"
        app:trackColorActive="@color/overlay_accent"
        app:trackColorInactive="@color/overlay_border" />
</LinearLayout>
```

- [ ] **Step 2: ParamCellView**

Create `ParamCellView.kt`:

```kotlin
package com.phantom.scroll.ui.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.phantom.scroll.R

/**
 * One cell of the 2×2 param grid. Shows label + current step text; tap to reveal an inline
 * [Slider] for fine adjustment. Owns its own expand state. The parent ([FloatingOverlayView])
 * reads [slider] / [isExpanded] to suppress whole-panel drag while the slider is in use.
 *
 * @param stepResolver maps the raw float value to the friendly step label shown when collapsed.
 */
class ParamCellView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val labelView: TextView
    private val valueView: TextView
    val slider: Slider
    var isExpanded: Boolean = false
        private set

    private var stepResolver: ((Float) -> String)? = null
    var onUserChange: ((Float) -> Unit)? = null
    private var applyingFromFlow = false

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_param_cell, this, true)
        orientation = VERTICAL
        labelView = findViewById(R.id.param_label)
        valueView = findViewById(R.id.param_value)
        slider = findViewById(R.id.param_slider)

        findViewById<View>(R.id.param_cell_root).setOnClickListener { toggleExpand() }

        slider.addOnChangeListener { _, value, fromUser ->
            refreshValueText(value)
            if (fromUser && !applyingFromFlow) onUserChange?.invoke(value)
        }
    }

    /** Sets static label + slider range + the value→label resolver. Call once after inflate. */
    fun configure(label: String, valueFrom: Float, valueTo: Float, stepResolver: (Float) -> String) {
        labelView.text = label
        slider.valueFrom = valueFrom
        slider.valueTo = valueTo
        slider.value = valueFrom
        this.stepResolver = stepResolver
    }

    /** Pushes [v] from a flow (no writeback) or reflects a user drag (already handled by listener). */
    fun setValue(v: Float, fromFlow: Boolean) {
        applyingFromFlow = true
        slider.value = v.coerceIn(slider.valueFrom, slider.valueTo)
        applyingFromFlow = false
        refreshValueText(slider.value)
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        slider.visibility = if (isExpanded) VISIBLE else GONE
    }

    private fun refreshValueText(v: Float) {
        valueView.text = stepResolver?.invoke(v) ?: ""
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/layout/overlay_param_cell.xml \
        app/src/main/java/com/phantom/scroll/ui/overlay/ParamCellView.kt
git commit -m "feat(overlay): add ParamCellView (label + step + expandable inline slider)"
```

---

### Task 9: 原子替换 —— 重写 overlay_panel.xml + FloatingOverlayView + 删除 handle

**背景：** 这是集成点。必须 panel 布局 + 主视图 + 删除旧 handle 在**同一个 commit**，否则 `findViewById` 旧 ID 会导致编译断裂。新组件（Task 6/7/8）已就位、未被引用；本任务把它们接线并把旧 `130f`/`32f` 全部换成 `OverlayGeometry` 常量。

**Files:**
- Rewrite: `app/src/main/res/layout/overlay_panel.xml`
- Rewrite: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`
- Delete: `app/src/main/res/layout/overlay_handle.xml`

**Interfaces:**
- Consumes: `ParamCellView`/`BubbleView`（Task 6/8）、`ParamSteps`（Task 1）、`BadgeFormatter`（Task 2）、`OverlayGeometry.PANEL_WIDTH_DP/COLLAPSED_WIDTH_DP`（Task 3）、所有 overlay_* 颜色（Task 4/5）

- [ ] **Step 1: 重写 overlay_panel.xml**

整文件替换 `overlay_panel.xml`（240dp，分区结构；内置 settings_root via include）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/panel_root"
    android:layout_width="240dp"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:background="@drawable/overlay_card_border"
    android:padding="6dp">

    <!-- drag handle (visual only; whole panel draggable) -->
    <View
        android:layout_width="36dp"
        android:layout_height="4dp"
        android:layout_gravity="center_horizontal"
        android:layout_marginTop="2dp"
        android:background="@drawable/overlay_chip_bg" />

    <!-- Header -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="8dp"
        android:paddingEnd="4dp"
        android:paddingTop="6dp"
        android:paddingBottom="8dp">
        <TextView
            android:id="@+id/header_icon"
            android:layout_width="32dp"
            android:layout_height="32dp"
            android:gravity="center"
            android:text="👻"
            android:textSize="18sp"
            android:background="@drawable/overlay_status_pill_running" />
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical"
            android:layout_marginStart="8dp">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="自动滑动"
                android:textColor="@color/overlay_fg"
                android:textSize="14sp"
                android:textStyle="bold" />
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="AUTO SCROLL"
                android:textColor="@color/overlay_subtle"
                android:textSize="9sp"
                android:fontFamily="monospace"
                android:letterSpacing="0.08" />
        </LinearLayout>
        <FrameLayout
            android:id="@+id/fold_button"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true">
            <TextView
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="⌄"
                android:textColor="@color/overlay_muted"
                android:textSize="14sp" />
        </FrameLayout>
        <FrameLayout
            android:id="@+id/settings_button"
            android:layout_width="28dp"
            android:layout_height="28dp"
            android:layout_marginStart="4dp"
            android:background="@drawable/overlay_chip_bg"
            android:clickable="true"
            android:focusable="true">
            <TextView
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="⚙"
                android:textColor="@color/overlay_muted"
                android:textSize="13sp" />
        </FrameLayout>
    </LinearLayout>

    <!-- Status row -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingStart="10dp"
        android:paddingEnd="10dp"
        android:paddingBottom="8dp">
        <TextView
            android:id="@+id/status_pill"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="@drawable/overlay_status_pill_running"
            android:paddingStart="9dp"
            android:paddingEnd="9dp"
            android:paddingTop="4dp"
            android:paddingBottom="4dp"
            android:text="● 运行中"
            android:textColor="@color/overlay_accent"
            android:textSize="10.5sp"
            android:fontFamily="monospace"
            android:textStyle="bold" />
        <TextView
            android:id="@+id/status_meta"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:gravity="end"
            android:text="前台 · -"
            android:textColor="@color/overlay_subtle"
            android:textSize="10.5sp"
            android:fontFamily="monospace"
            android:maxLines="1"
            android:ellipsize="end" />
    </LinearLayout>

    <!-- Metric section -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="bottom"
        android:paddingStart="10dp"
        android:paddingEnd="10dp"
        android:paddingTop="6dp"
        android:paddingBottom="8dp">
        <LinearLayout
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:orientation="vertical">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="已滑动"
                android:textColor="@color/overlay_subtle"
                android:textSize="9.5sp"
                android:fontFamily="monospace"
                android:textAllCaps="true" />
            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="bottom">
                <TextView
                    android:id="@+id/metric_count"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="0"
                    android:textColor="@color/overlay_fg"
                    android:textSize="26sp"
                    android:textStyle="bold"
                    android:fontFamily="monospace" />
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="2dp"
                    android:layout_marginBottom="3dp"
                    android:text="次"
                    android:textColor="@color/overlay_muted"
                    android:textSize="11sp" />
            </LinearLayout>
        </LinearLayout>
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:gravity="end">
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="时长"
                android:textColor="@color/overlay_subtle"
                android:textSize="9.5sp"
                android:fontFamily="monospace"
                android:textAllCaps="true" />
            <TextView
                android:id="@+id/metric_elapsed"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="约 0 分钟"
                android:textColor="@color/overlay_fg"
                android:textSize="12sp"
                android:textStyle="bold"
                android:fontFamily="monospace" />
        </LinearLayout>
    </LinearLayout>

    <!-- 2×2 param grid -->
    <GridLayout
        android:id="@+id/param_grid"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:columnCount="2"
        android:rowCount="2"
        android:useDefaultMargins="true">
        <com.phantom.scroll.ui.overlay.ParamCellView
            android:id="@+id/param_cell_speed"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1" />
        <com.phantom.scroll.ui.overlay.ParamCellView
            android:id="@+id/param_cell_interval"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1" />
        <com.phantom.scroll.ui.overlay.ParamCellView
            android:id="@+id/param_cell_distance"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_rowWeight="1" />
        <!-- Direction toggle cell -->
        <LinearLayout
            android:id="@+id/direction_cell"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_columnWeight="1"
            android:layout_gravity="fill"
            android:orientation="vertical"
            android:background="@drawable/overlay_param_cell_bg"
            android:padding="9dp"
            android:clickable="true"
            android:focusable="true">
            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="方向"
                android:textColor="@color/overlay_muted"
                android:textSize="11sp" />
            <TextView
                android:id="@+id/direction_value"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="↓ 向下"
                android:textColor="@color/overlay_fg"
                android:textSize="12sp"
                android:textStyle="bold"
                android:fontFamily="monospace" />
        </LinearLayout>
    </GridLayout>

    <!-- Settings sub-panel (included, GONE by default) -->
    <include layout="@layout/overlay_settings" />

    <!-- Per-app row -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="9dp">
        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="按 App 分别记录"
            android:textColor="@color/overlay_fg"
            android:textSize="12sp" />
        <com.google.android.material.materialswitch.MaterialSwitch
            android:id="@+id/perapp_switch"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content" />
    </LinearLayout>
    <TextView
        android:id="@+id/perapp_label"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:text=""
        android:textColor="@color/overlay_accent"
        android:textSize="8sp"
        android:visibility="gone"
        android:paddingBottom="4dp" />

    <!-- Action bar -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="6dp">
        <com.google.android.material.button.MaterialButton
            android:id="@+id/toggle_btn"
            android:layout_width="0dp"
            android:layout_height="40dp"
            android:layout_weight="1"
            android:minWidth="0dp"
            android:minHeight="0dp"
            android:padding="0dp"
            android:insetTop="0dp"
            android:insetBottom="0dp"
            android:text="⏸ 暂停滑动"
            android:textColor="@color/overlay_fg"
            android:textSize="13sp"
            android:textStyle="bold"
            app:cornerRadius="11dp"
            app:backgroundTint="@color/overlay_surface_2"
            app:strokeColor="@color/overlay_border"
            app:strokeWidth="1dp" />
        <com.google.android.material.button.MaterialButton
            android:id="@+id/reset_btn"
            android:layout_width="40dp"
            android:layout_height="40dp"
            android:layout_marginStart="7dp"
            android:minWidth="0dp"
            android:minHeight="0dp"
            android:padding="0dp"
            android:insetTop="0dp"
            android:insetBottom="0dp"
            android:text="↻"
            android:textColor="@color/overlay_muted"
            android:textSize="16sp"
            app:cornerRadius="11dp"
            app:backgroundTint="@color/overlay_surface"
            app:strokeColor="@color/overlay_border"
            app:strokeWidth="1dp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 2: 重写 FloatingOverlayView.kt**

整文件替换 `FloatingOverlayView.kt`（编排者：inflate 面板+气泡，绑定 flow，转发交互；宽度全用 `OverlayGeometry` 常量；保留原有拖拽/吸附/ACTION_OUTSIDE 折叠逻辑）：

```kotlin
package com.phantom.scroll.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.phantom.scroll.R
import com.phantom.scroll.data.Preset
import com.phantom.scroll.data.PresetSelection
import com.phantom.scroll.data.ScrollDirection
import com.phantom.scroll.data.ScrollSettings
import com.phantom.scroll.data.ScrollStats
import com.phantom.scroll.data.SettingsRepository
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
 * Orchestrator for the redesigned overlay. Inflates the panel + bubble, collects
 * [SettingsRepository] flows to refresh imperative views, and emits user interactions back.
 * Holds NO business logic beyond view↔state binding. Param/badge logic lives in [ParamSteps] /
 * [BadgeFormatter]; geometry in [OverlayGeometry].
 *
 * MUST be constructed with a Material3-themed context (Slider requires it); the controller wraps
 * the service context in [R.style.Theme_PhantomScroll_Overlay].
 */
class FloatingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var repository: SettingsRepository? = null
    private var panelStateFlow: MutableStateFlow<PanelState>? = null
    private var onUpdatePosition: ((x: Int, y: Int) -> Unit)? = null
    private var bound = false

    // panel children
    private val panelRoot: View
    private val settingsRoot: View
    private val settingsButton: View
    private val foldButton: View
    private val statusPill: TextView
    private val statusMeta: TextView
    private val metricCount: TextView
    private val metricElapsed: TextView
    private val cellSpeed: ParamCellView
    private val cellInterval: ParamCellView
    private val cellDistance: ParamCellView
    private val directionCell: View
    private val directionValue: TextView
    private val chipNovel: TextView
    private val chipComic: TextView
    private val chipCustom: TextView
    private val forgetAppBtn: View
    private val perAppSwitch: MaterialSwitch
    private val perAppLabel: TextView
    private val toggleBtn: com.google.android.material.button.MaterialButton
    private val resetBtn: com.google.android.material.button.MaterialButton
    // bubble
    private val bubble: BubbleView

    private var settingsVisible = false

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
    private var disallowIntercept = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var snapAnimator: ValueAnimator? = null
    private var applyingFromFlow = false

    private val density get() = resources.displayMetrics.density
    private fun panelWidthPx() = (OverlayGeometry.PANEL_WIDTH_DP * density).toInt()
    private fun collapsedWidthPx() = (OverlayGeometry.COLLAPSED_WIDTH_DP * density).toInt()

    init {
        inflate(context, R.layout.overlay_panel, this)
        // BubbleView inflates overlay_bubble.xml itself; just add one (avoids duplicate bubble_root IDs).
        bubble = BubbleView(context).also {
            addView(it, LayoutParams(collapsedWidthPx(), collapsedWidthPx()))
        }

        panelRoot = findViewById(R.id.panel_root)
        settingsRoot = findViewById(R.id.settings_root)
        settingsButton = findViewById(R.id.settings_button)
        foldButton = findViewById(R.id.fold_button)
        statusPill = findViewById(R.id.status_pill)
        statusMeta = findViewById(R.id.status_meta)
        metricCount = findViewById(R.id.metric_count)
        metricElapsed = findViewById(R.id.metric_elapsed)
        cellSpeed = findViewById(R.id.param_cell_speed)
        cellInterval = findViewById(R.id.param_cell_interval)
        cellDistance = findViewById(R.id.param_cell_distance)
        directionCell = findViewById(R.id.direction_cell)
        directionValue = findViewById(R.id.direction_value)
        chipNovel = findViewById(R.id.chip_novel)
        chipComic = findViewById(R.id.chip_comic)
        chipCustom = findViewById(R.id.chip_custom)
        forgetAppBtn = findViewById(R.id.forget_app_btn)
        perAppSwitch = findViewById(R.id.perapp_switch)
        perAppLabel = findViewById(R.id.perapp_label)
        toggleBtn = findViewById(R.id.toggle_btn)
        resetBtn = findViewById(R.id.reset_btn)

        configureCells()

        foldButton.setOnClickListener { panelStateFlow?.value = PanelState.Collapsed }
        settingsButton.setOnClickListener { toggleSettings() }
        toggleBtn.setOnClickListener { repository?.toggleRunning() }
        resetBtn.setOnClickListener {
            scope.launch { repository?.resetStats() }
        }
        chipNovel.setOnClickListener { scope.launch { repository?.applyPreset(Preset.NOVEL) } }
        chipComic.setOnClickListener { scope.launch { repository?.applyPreset(Preset.COMIC) } }
        chipCustom.setOnClickListener { scope.launch { repository?.applyCustomPreset() } }
        forgetAppBtn.setOnClickListener {
            scope.launch {
                repository?.forgetActiveProfile()
                toast("已忘记当前 App 配置")
            }
        }
        directionCell.setOnClickListener {
            val repo = repository ?: return@setOnClickListener
            val active = repo.activeSettings.value
            val next = if (active.direction == ScrollDirection.UP) ScrollDirection.DOWN else ScrollDirection.UP
            scope.launch { repo.updateActive(active.copy(direction = next)) }
        }
        perAppSwitch.setOnCheckedChangeListener { _, checked ->
            if (applyingFromFlow) return@setOnCheckedChangeListener
            repository?.setPerAppEnabled(checked)
        }

        applyState(PanelState.Expanded)
        applyRunning(false)
    }

    private fun configureCells() {
        cellSpeed.configure(
            label = "速度",
            valueFrom = 150f, valueTo = 1500f,
            stepResolver = { ParamSteps.toSpeedLabel(it.toLong()) }
        )
        cellInterval.configure(
            label = "间隔",
            valueFrom = 500f, valueTo = 10000f,
            stepResolver = { ParamSteps.toIntervalLabel(it.toLong()) }
        )
        // distance resolver closes over screen height; re-bound on size change via applySettings.
        cellDistance.configure(
            label = "距离",
            valueFrom = 0.30f, valueTo = 0.95f,
            stepResolver = { ratio -> ParamSteps.toDistanceLabel(ratio, screenH()) }
        )
        cellSpeed.onUserChange = { v -> updateActive { it.copy(duration = v.toLong()) } }
        cellInterval.onUserChange = { v -> updateActive { it.copy(interval = v.toLong()) } }
        cellDistance.onUserChange = { v -> updateActive { it.copy(distanceRatio = v) } }
    }

    private fun screenH(): Int = repository?.screenHeight?.value ?: 1

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
            launch { repo.activeSettings.collect { applySettings(it) } }
            launch { repo.isRunning.collect { applyRunning(it) } }
            launch {
                combine(repo.screenWidth, repo.screenHeight) { w, h -> w to h }
                    .collect { repositionToBounds(it.first, it.second) }
            }
            launch { repo.selectedPreset.collect { applyPresetSelection(it) } }
            launch { repo.stats.collect { applyStats(it) } }
            launch { repo.perAppEnabled.collect { applyPerAppEnabled(it) } }
            launch { repo.currentPackage.collect { applyCurrentPackage(it) } }
        }
    }

    private fun applyState(state: PanelState) {
        val repo = repository ?: return
        val screenWidth = repo.screenWidth.value
        when (state) {
            PanelState.Collapsed -> {
                bubble.visibility = VISIBLE
                panelRoot.visibility = GONE
                currentX = OverlayGeometry.edgeX(isLeftEdge, screenWidth, collapsedWidthPx())
                onUpdatePosition?.invoke(currentX, currentY)
            }
            PanelState.Expanded -> {
                panelRoot.visibility = VISIBLE
                bubble.visibility = GONE
                currentX = OverlayGeometry.edgeX(isLeftEdge, screenWidth, panelWidthPx())
                onUpdatePosition?.invoke(currentX, currentY)
            }
            PanelState.Snapping -> {
                panelRoot.visibility = VISIBLE
                bubble.visibility = GONE
            }
        }
    }

    private fun applySettings(s: ScrollSettings) {
        applyingFromFlow = true
        cellSpeed.setValue(s.duration.toFloat(), fromFlow = true)
        cellInterval.setValue(s.interval.toFloat(), fromFlow = true)
        cellDistance.setValue(s.distanceRatio, fromFlow = true)
        applyingFromFlow = false
        directionValue.text = if (s.direction == ScrollDirection.DOWN) "↓ 向下" else "↑ 向上"
    }

    private fun applyRunning(running: Boolean) {
        // status pill: green running / amber paused
        statusPill.text = if (running) "● 运行中" else "● 已暂停"
        statusPill.setBackgroundResource(
            if (running) R.drawable.overlay_status_pill_running else R.drawable.overlay_status_pill_paused
        )
        statusPill.setTextColor(ResourcesCompat.getColor(resources, R.color.overlay_accent.takeIf { running } ?: R.color.overlay_warning, null))
        // main button: running = neutral outline ("暂停滑动"); paused = green filled ("开始滑动")
        toggleBtn.text = if (running) "⏸ 暂停滑动" else "▶ 开始滑动"
        if (running) {
            toggleBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.overlay_surface_2, null))
            toggleBtn.strokeColor = android.content.res.ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.overlay_border, null))
            toggleBtn.strokeWidth = density.toInt().coerceAtLeast(1) // 1dp outline
            toggleBtn.setTextColor(ResourcesCompat.getColor(resources, R.color.overlay_fg, null))
        } else {
            toggleBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
                ResourcesCompat.getColor(resources, R.color.overlay_accent, null))
            toggleBtn.strokeWidth = 0
            toggleBtn.setTextColor(ResourcesCompat.getColor(resources, R.color.overlay_on_accent, null))
        }
    }

    private fun applyPresetSelection(sel: PresetSelection) {
        val selectedBg = ResourcesCompat.getDrawable(resources, R.drawable.overlay_chip_bg_selected, null)
        val plainBg = ResourcesCompat.getDrawable(resources, R.drawable.overlay_chip_bg, null)
        val accent = ResourcesCompat.getColor(resources, R.color.overlay_accent, null)
        val fg = ResourcesCompat.getColor(resources, R.color.overlay_fg, null)
        listOf(
            chipNovel to (sel is PresetSelection.BuiltIn && sel.preset == Preset.NOVEL),
            chipComic to (sel is PresetSelection.BuiltIn && sel.preset == Preset.COMIC),
            chipCustom to (sel is PresetSelection.Custom)
        ).forEach { (chip, on) ->
            chip.background = if (on) selectedBg else plainBg
            chip.setTextColor(if (on) accent else fg)
        }
    }

    private fun applyStats(stats: ScrollStats) {
        metricCount.text = stats.swipeCount.toString()
        val minutes = Math.round(stats.elapsedMs / 60000.0)
        metricElapsed.text = "约 $minutes 分钟"
        bubble.setCount(stats.swipeCount)
    }

    private fun applyPerAppEnabled(enabled: Boolean) {
        applyingFromFlow = true
        perAppSwitch.isChecked = enabled
        applyingFromFlow = false
        updatePerAppLabelVisibility()
    }

    private fun applyCurrentPackage(pkg: String?) {
        statusMeta.text = "前台 · ${pkg ?: "-"}"
        updatePerAppLabelVisibility()
    }

    private fun updatePerAppLabelVisibility() {
        val repo = repository ?: return
        val pkg = repo.currentPackage.value
        val enabled = repo.perAppEnabled.value
        perAppLabel.visibility = if (pkg != null && enabled) VISIBLE else GONE
        perAppLabel.text = if (pkg != null) "当前：$pkg" else ""
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        settingsRoot.visibility = if (settingsVisible) VISIBLE else GONE
        // 120ms crossfade
        val anim = AlphaAnimation(if (settingsVisible) 0f else 1f, if (settingsVisible) 1f else 0f)
        anim.duration = 120
        settingsRoot.startAnimation(anim)
        // if panel now taller and near bottom, re-clamp Y
        repositionToBounds(repository?.screenWidth?.value ?: 0, repository?.screenHeight?.value ?: 0)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun updateActive(transform: (ScrollSettings) -> ScrollSettings) {
        val repo = repository ?: return
        scope.launch { repo.updateActive(transform(repo.activeSettings.value)) }
    }

    private fun repositionToBounds(screenWidth: Int, screenHeight: Int) {
        val flow = panelStateFlow
        val isCollapsed = flow != null && flow.value == PanelState.Collapsed
        val widthPx = if (isCollapsed) collapsedWidthPx() else panelWidthPx()
        val panelH = panelRoot.height.takeIf { it > 0 }
            ?: (260f * density).toInt()
        val nx = if (!dragging) {
            OverlayGeometry.edgeX(isLeftEdge, screenWidth, widthPx)
        } else {
            OverlayGeometry.clamp(currentX, 0, (screenWidth - widthPx).coerceAtLeast(0))
        }
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

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            snapAnimator?.cancel()
            downRawX = ev.rawX; downRawY = ev.rawY
            lastRawX = ev.rawX; lastRawY = ev.rawY
            dragging = false
            // interactive children: expanded sliders + direction cell + buttons + switch + chips + settings btn
            val expandedSliders = listOf(cellSpeed, cellInterval, cellDistance)
                .filter { it.isExpanded }.map { it.slider }
            val interactive = expandedSliders + listOf(
                directionCell, toggleBtn, resetBtn, foldButton, settingsButton,
                perAppSwitch, chipNovel, chipComic, chipCustom, forgetAppBtn
            )
            disallowIntercept = interactive.any { isTouchInsideView(ev, it) }
        }
        if (disallowIntercept) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                    dragging = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disallowIntercept = false
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            // settings open → close settings first (don't collapse whole panel)
            if (settingsVisible) { toggleSettings(); return true }
            val flow = panelStateFlow
            if (flow != null && flow.value == PanelState.Expanded) flow.value = PanelState.Collapsed
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
        val repo = repository ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging &&
                    (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
                    dragging = true; lastRawX = ev.rawX; lastRawY = ev.rawY
                }
                if (dragging) {
                    val w = repo.screenWidth.value
                    val h = repo.screenHeight.value
                    val dx = (ev.rawX - lastRawX).toInt()
                    val dy = (ev.rawY - lastRawY).toInt()
                    val panelW = panelRoot.width.takeIf { it > 0 } ?: panelWidthPx()
                    val panelH = panelRoot.height.takeIf { it > 0 } ?: (260f * density).toInt()
                    currentX = OverlayGeometry.clamp(currentX + dx, 0, w - panelW)
                    currentY = OverlayGeometry.clamp(currentY + dy, 0, h - panelH)
                    lastRawX = ev.rawX; lastRawY = ev.rawY
                    onUpdatePosition?.invoke(currentX, currentY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disallowIntercept = false
                if (dragging) { dragging = false; performSnap() }
            }
        }
        return true
    }

    private fun performSnap() {
        val repo = repository ?: return
        val flow = panelStateFlow ?: return
        val screenWidth = repo.screenWidth.value
        val panelWidthPx = panelRoot.width.takeIf { it > 0 } ?: panelWidthPx()
        val target = OverlayGeometry.snapTarget(
            panelCenterX = currentX + panelWidthPx / 2,
            screenWidth = screenWidth,
            panelWidthPx = panelWidthPx
        )
        isLeftEdge = target.isLeftEdge
        flow.value = PanelState.Snapping
        snapAnimator = ValueAnimator.ofInt(currentX, target.x).apply {
            duration = 250
            addUpdateListener { a ->
                currentX = a.animatedValue as Int
                onUpdatePosition?.invoke(currentX, currentY)
            }
        }
        snapAnimator?.start()
        postDelayed({ if (flow.value == PanelState.Snapping) flow.value = PanelState.Collapsed }, 270)
    }
}
```

> **实现者注意：** `FloatingOverlayView` 只 inflate `overlay_panel`；气泡通过 `addView(BubbleView(context))` 挂载，`BubbleView` 自己 inflate `overlay_bubble.xml`。**不要**在 `FloatingOverlayView` 里再 inflate `overlay_bubble.xml`（会导致 `bubble_root`/`bubble_count` ID 重复），也**不要删除** `overlay_bubble.xml`（`BubbleView` 内部依赖它）。气泡默认 `GONE`（`overlay_bubble.xml` 根节点已设）。

- [ ] **Step 3: 删除 overlay_handle.xml**

```bash
git rm app/src/main/res/layout/overlay_handle.xml
```

- [ ] **Step 4: 编译验证（关键闸门）**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。若报 `unresolved reference`（如某 view ID 拼写不一致），对照 Step 1 布局 ID 与 Step 2 `findViewById` 逐一核对。

- [ ] **Step 5: 全量单测**

Run: `./gradlew test`
Expected: 全绿（视图层无单测；纯逻辑用例 + 几何用例不受影响）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/layout/overlay_panel.xml \
        app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt
git commit -m "feat(overlay): rewrite panel + FloatingOverlayView (240dp light/green, 2x2 grid, bubble collapse)

- New panel layout: header/status/metric/2x2 grid/per-app/action + settings sub-panel
- FloatingOverlayView thinned to orchestrator; widths via OverlayGeometry constants
- 56dp BubbleView collapse with live badge; edge snap preserved
- Direction UP/DOWN only; settings open ⇒ outside-touch closes settings first
- Removes overlay_handle.xml"
```

---

### Task 10: 最终验证 + 验收清单

**Files:** 无新增；仅验证。

- [ ] **Step 1: Release 构建冒烟**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 全量单测**

Run: `./gradlew test`
Expected: 全绿（现 76 + 新增 ParamStepsTest 3 + BadgeFormatterTest 2 + OverlayGeometryTest +1 = 82）

- [ ] **Step 3: 真机验收清单（对照 spec §验收标准）**

安装到设备，逐项勾选：

- [ ] 展开态：浅色/绿、240dp、含 头部/状态胶囊/大号计数/2×2 网格/per-app/操作栏，与 spec wireframe 一致
- [ ] 点速度/间隔/距离格 → 展开内联迷你滑块精调；松开后档位文本正确反映连续值；方向格点一下 ↑↔↓
- [ ] 折叠态：56dp 气泡 + 计数角标，吸附最近边缘且完全可见；运行时角标实时跳动；点气泡展开
- [ ] ⚙ 打开设置子面板：预设单选高亮 + 忘记当前 App 可用；展开/收起有过渡；面板贴底时自动上推不溢出
- [ ] 主按钮：运行=中性描边"暂停滑动"，暂停=绿色实心"开始滑动"；状态胶囊同步绿/琥珀
- [ ] MainActivity 与通知为浅色/绿；通知 `setColor()` 生效（绿）
- [ ] 5 寸/720p 小屏：2×2 格可点、展开 Slider 可拖、不被面板拖拽抢夺
- [ ] 锁屏恢复后气泡角标数值正确，展开后计数与统计一致
- [ ] 拖到边缘吸附后旋转屏幕不越界
- [ ] 手势引擎/预设/per-app/统计行为与改造前逐项一致

- [ ] **Step 4: 更新 README（可选，若统计/外观描述受影响）**

如 spec 未要求改 README，跳过；否则更新"悬浮窗"特性段。

- [ ] **Step 5: 最终 Commit（如有验收小修）**

```bash
git add -A
git commit -m "test(overlay): final device acceptance pass for redesign"
```

---

## Self-Review Notes

- **Spec 覆盖：** 决策①(浅色/绿)→T4/T5/T9；②(混合参数)→T1/T8/T9；③(仅↑/↓)→T9 directionCell；④(气泡+吸附)→T3/T6/T9。预设进⚙→T7/T9；计数右侧放时长→T9 metric；主按钮语义翻转→T9 applyRunning；KDoc 修正→T4；setColor→T4；OverlayTheme 删除→T4；FloatingWindowController 不动→Global Constraints。全部覆盖。
- **类型/ID 一致：** `ParamCellView.configure/setValue/onUserChange/slider/isExpanded`、`BubbleView.setCount`、`BadgeFormatter.format`、`ParamSteps.toSpeedLabel/toIntervalLabel/toDistanceLabel`、`OverlayGeometry.PANEL_WIDTH_DP/COLLAPSED_WIDTH_DP` 在各 Task 间签名一致。布局 ID（panel_root/settings_root/param_cell_*/direction_cell/metric_count/metric_elapsed/status_pill/status_meta/toggle_btn/reset_btn/perapp_switch/perapp_label/chip_*/forget_app_btn/fold_button/settings_button）与 FloatingOverlayView findViewById 一一对应。
- **气泡挂载：** `FloatingOverlayView` 仅 inflate `overlay_panel`，气泡靠 `addView(BubbleView(context))`；`overlay_bubble.xml` 由 `BubbleView` 内部 inflate，不删除、不在主视图重复 inflate（避免 ID 重复）。
- **applyRunning 对称：** running 分支显式设 `strokeWidth = 1dp`、paused 分支设 `strokeWidth = 0`，避免暂停→运行循环后描边丢失（self-review 修正）。
