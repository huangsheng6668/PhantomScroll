# 悬浮窗暗黑玻璃拟态 UI 统一 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将悬浮窗从白底/绿强调重构为与主界面统一的暗黑玻璃拟态风格，补齐展开/收起过渡动效，全部字形图标换为矢量 drawable，并重设计收起气泡。

**Architecture:** 改动集中在资源层（colors/themes/drawables/layouts）+ 两个视图类（`FloatingOverlayView` 增加过渡动画与图标绑定、`BubbleView` 增加按压反馈）。状态机、`OverlayGeometry` 几何、触摸拦截逻辑、数据层全部不动。

**Tech Stack:** 原生 View + Material Components（M3 Dark）、vector drawable、ValueAnimator。

**Spec:** `docs/superpowers/specs/2026-08-28-overlay-dark-glass-ui-design.md`

## Global Constraints

- minSdk 26 / targetSdk 35；悬浮窗保持原生 View 实现，禁止引入 Compose 运行时。
- token 名不变（`overlay_*`），只换值；新增 token 仅 `overlay_surface_3`。
- 动画仅存在于状态切换路径，禁止进入手势/滑动热路径；禁止在动画回调中分配对象（`onAnimationEnd` 只做幂等终态设置）。
- `OverlayGeometryTest` / `ParamStepsTest` 必须保持通过（几何与分档标签不改）。
- 每个任务以 `gradlew :app:assembleDebug`（编译期校验资源引用闭环）为最低验证门槛。

---

### Task 1: 资源层 — 调色板 / 主题 / 矢量图标

**Files:**
- Modify: `app/src/main/res/values/colors.xml:15-26`
- Modify: `app/src/main/res/values/themes.xml:11-18`
- Modify: `app/src/main/res/drawable/overlay_bubble_bg.xml`
- Create: `app/src/main/res/drawable/ic_overlay_fold.xml`
- Create: `app/src/main/res/drawable/ic_overlay_tune.xml`
- Create: `app/src/main/res/drawable/ic_overlay_play.xml`
- Create: `app/src/main/res/drawable/ic_overlay_pause.xml`
- Create: `app/src/main/res/drawable/ic_overlay_arrow_down.xml`
- Create: `app/src/main/res/drawable/ic_overlay_arrow_up.xml`
- Create: `app/src/main/res/drawable/ic_overlay_ghost.xml`
- Create: `app/src/main/res/color/overlay_switch_track.xml`
- Create: `app/src/main/res/color/overlay_switch_thumb.xml`

**Interfaces:**
- Produces（后续任务按名引用）: 颜色 token 新值（见下）；drawable `ic_overlay_fold/tune/play/pause/arrow_down/arrow_up/ghost`；color-state-list `overlay_switch_track/thumb`。

- [x] **Step 1: 替换 colors.xml 的 overlay 段**

```xml
    <!-- ===== Overlay (dark glass — unified with the Compose permission page tokens) ===== -->
    <color name="overlay_surface">#F21E1E24</color>
    <color name="overlay_surface_2">#FF292933</color>
    <color name="overlay_surface_3">#FF33333F</color>
    <color name="overlay_fg">#FFF5F5F7</color>
    <color name="overlay_muted">#FFA8A8B2</color>
    <color name="overlay_subtle">#FF7A7A86</color>
    <color name="overlay_border">#FF3C3C48</color>
    <color name="overlay_accent">#FF00E5FF</color>
    <color name="overlay_accent_soft">#2E00E5FF</color>
    <color name="overlay_warning">#FFD98324</color>
    <color name="overlay_warning_soft">#2ED98324</color>
    <color name="overlay_on_accent">#FF00262B</color>
```

- [x] **Step 2: themes.xml overlay 主题转 Dark M3**

```xml
    <!-- Material3 theme used ONLY to inflate the native overlay (Slider requires a Material theme).
         Dark glass — shares the palette with the Compose permission page (see ui/theme/Theme.kt);
         the app's main theme stays Theme.AppCompat.NoActionBar. -->
    <style name="Theme.PhantomScroll.Overlay" parent="Theme.Material3.Dark.NoActionBar">
        <item name="colorPrimary">@color/overlay_accent</item>
        <item name="colorOnPrimary">@color/overlay_on_accent</item>
        <item name="colorPrimaryContainer">@color/overlay_accent_soft</item>
        <item name="android:colorBackground">@color/overlay_surface</item>
    </style>
```

- [x] **Step 3: 气泡底 drawable（深色玻璃 + 2dp 40% 青描边）**

```xml
<shape xmlns:android="http://schemas.com/apk/res/android" android:shape="oval">
    <solid android:color="@color/overlay_surface" />
    <stroke android:width="2dp" android:color="#6600E5FF" />
</shape>
```

- [x] **Step 4: 新增 7 个矢量 drawable（内容见下方各文件全文）**

`ic_overlay_fold.xml`（16dp，chevron-down）:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.com/apk/res/android"
    android:width="16dp" android:height="16dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M7,10 L12,15 L17,10"
        android:strokeColor="@color/overlay_muted"
        android:strokeWidth="2.2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000" />
</vector>
```

`ic_overlay_tune.xml`（16dp，双滑杆 + 旋钮，替代 ⚙）:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.com/apk/res/android"
    android:width="16dp" android:height="16dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M4,8 L20,8 M4,16 L20,16"
        android:strokeColor="@color/overlay_muted"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:fillColor="#00000000" />
    <path android:pathData="M15,8 m-2.4,0 a2.4,2.4 0 1,0 4.8,0 a2.4,2.4 0 1,0 -4.8,0 M9,16 m-2.4,0 a2.4,2.4 0 1,0 4.8,0 a2.4,2.4 0 1,0 -4.8,0"
        android:fillColor="@color/overlay_muted" />
</vector>
```

`ic_overlay_play.xml`（14dp，实心三角，运行时由 iconTint 着色）:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.com/apk/res/android"
    android:width="14dp" android:height="14dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M9,6.2 L18,12 L9,17.8 Z" android:fillColor="#FFFFFFFF" />
</vector>
```

`ic_overlay_pause.xml`（14dp，双竖条）:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.com/apk/res/android"
    android:width="14dp" android:height="14dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M9,6.5 L9,17.5 M15,6.5 L15,17.5"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="3"
        android:strokeLineCap="round"
        android:fillColor="#00000000" />
</vector>
```

`ic_overlay_arrow_down.xml`（14dp，青色箭头，作 compound drawable）:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.com/apk/res/android"
    android:width="14dp" android:height="14dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M12,6 L12,18 M7.5,13.5 L12,18 L16.5,13.5"
        android:strokeColor="@color/overlay_accent"
        android:strokeWidth="2.2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000" />
</vector>
```

`ic_overlay_arrow_up.xml`（同上，path 换为）:
```xml
    <path android:pathData="M12,18 L12,6 M7.5,10.5 L12,6 L16.5,10.5"
```

`ic_overlay_ghost.xml`（20dp，幽灵 glyph：拱顶 + 锯齿裙边 + 镂空双眼）:
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.com/apk/res/android"
    android:width="20dp" android:height="20dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:pathData="M12,3.5 C8.68,3.5 6,6.18 6,9.5 L6,18 L8,16.4 L10,18 L12,16.4 L14,18 L16,16.4 L18,18 L18,9.5 C18,6.18 15.32,3.5 12,3.5 Z"
        android:fillColor="@color/overlay_accent" />
    <path android:pathData="M9.6,10 m-1.2,0 a1.2,1.2 0 1,0 2.4,0 a1.2,1.2 0 1,0 -2.4,0 M14.4,10 m-1.2,0 a1.2,1.2 0 1,0 2.4,0 a1.2,1.2 0 1,0 -2.4,0"
        android:fillColor="#FF1E1E24" />
</vector>
```

- [x] **Step 5: 新增 MaterialSwitch 的 color-state-list（`res/color/`）**

`overlay_switch_track.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.com/apk/res/android">
    <item android:state_checked="true" android:color="@color/overlay_accent" />
    <item android:color="@color/overlay_surface_3" />
</selector>
```

`overlay_switch_thumb.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.com/apk/res/android">
    <item android:state_checked="true" android:color="@color/overlay_accent" />
    <item android:color="@color/overlay_muted" />
</selector>
```

- [x] **Step 6: 编译验证** — `gradlew :app:assembleDebug`，期望 BUILD SUCCESSFUL。
- [x] **Step 7: Commit** — `feat(overlay): dark-glass palette, M3 dark theme, vector icons`

### Task 2: 布局层 — overlay_panel / overlay_bubble

**Files:**
- Modify: `app/src/main/res/layout/overlay_panel.xml`
- Modify: `app/src/main/res/layout/overlay_bubble.xml`

**Interfaces:**
- Consumes: Task 1 的全部 drawable 与 color-state-list。
- Produces: `fold_button`/`settings_button` 内部改为 ImageView（外层 FrameLayout 与 id 不变，代码绑定零改动）；`toggle_btn` 携带 icon（代码将调用 `setIconResource`）；方向值显示改为「纯文字 + compound drawable」。

- [x] **Step 1: overlay_panel.xml — 折叠/设置按钮的字形 TextView 换成 ImageView**

fold_button 内部（替换 TextView "⌄"）:
```xml
<ImageView
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:scaleType="centerInside"
    android:src="@drawable/ic_overlay_fold"
    android:importantForAccessibility="no" />
```
settings_button 内部（替换 TextView "⚙"，src 换 `@drawable/ic_overlay_tune`）同构。

- [x] **Step 2: overlay_panel.xml — 状态 pill 与主按钮**

`status_pill` 默认文案 `"● 运行中"` → `"运行中"`（代码同步，见 Task 3）。
`toggle_btn` 默认文案 `"⏸ 暂停滑动"` → `"开始滑动"`，并追加属性：
```xml
app:icon="@drawable/ic_overlay_play"
app:iconGravity="textStart"
app:iconPadding="6dp"
app:iconTint="@color/overlay_on_accent"
```

- [x] **Step 3: overlay_panel.xml — MaterialSwitch 显式着色 + perapp_label 9sp**

```xml
<com.google.android.material.materialswitch.MaterialSwitch
    android:id="@+id/perapp_switch"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    app:thumbTint="@color/overlay_switch_thumb"
    app:trackTint="@color/overlay_switch_track" />
```
`perapp_label` `textSize 8sp` → `9sp`。

- [x] **Step 4: overlay_bubble.xml — 幽灵 glyph 替代 launcher mipmap**

```xml
<ImageView
    android:id="@+id/bubble_icon"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="3dp"
    android:src="@drawable/ic_overlay_ghost"
    android:scaleType="fitCenter"
    android:clipToOutline="true"
    android:importantForAccessibility="no" />
```
同步更新文件头注释（20dp 深色玻璃气泡 + 青色幽灵 glyph）。

- [x] **Step 5: 编译验证** — `gradlew :app:assembleDebug`。
- [x] **Step 6: Commit** — `feat(overlay): dark glass panel layout, vector glyph slots`

### Task 3: 代码层 — 过渡动画 / 图标绑定 / 按压反馈

**Files:**
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/FloatingOverlayView.kt`
- Modify: `app/src/main/java/com/phantom/scroll/ui/overlay/BubbleView.kt`

**Interfaces:**
- Consumes: Task 1 的 `ic_overlay_play/pause/arrow_down/arrow_up`。
- Produces: `applyState` 的动画化行为（内部细节，无外部契约变化）；`applyRunning`/`applySettings` 的图标绑定。

- [x] **Step 1: FloatingOverlayView — 新增 import 与常量、过渡字段**

```kotlin
import android.animation.Animator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.animation.DecelerateInterpolator
```
companion:
```kotlin
private companion object {
    const val TRANSITION_DURATION_MS = 160L
    const val PANEL_EXIT_SCALE_DELTA = 0.08f
    const val BUBBLE_ENTER_SCALE_MIN = 0.5f
}
```
字段:
```kotlin
private val decelerateInterpolator = DecelerateInterpolator(1.3f)
private var transitionAnimator: ValueAnimator? = null
private var transitionToCollapsed = false
```

- [x] **Step 2: applyState 改为动画化 + Snapping 取消过渡**

`Collapsed`/`Expanded` 分支保留原位置计算与 `onUpdatePosition`，末尾改调 `animateTransition(toCollapsed = true/false)`；`Snapping` 分支开头 `cancelTransition()` 后按原样设置 visibility 并 `resetTransitionVisuals()`。

- [x] **Step 3: 实现 animateTransition / applyStateDirect / resetTransitionVisuals / cancelTransition**

```kotlin
/**
 * Crossfade + scale transition between the expanded panel and the collapsed bubble.
 * One ValueAnimator also interpolates the window X between the two edge anchors, so the
 * bubble never teleports when the WRAP_CONTENT window re-anchors at a different width.
 * Runs only on state changes (never in the gesture hot path) and is cancel-safe: cancel
 * synchronously fires onAnimationEnd, which snaps visuals to the transition's end state —
 * always identical to [panelStateFlow]'s latest value.
 */
private fun animateTransition(toCollapsed: Boolean) {
    if (!isAttachedToWindow) {
        applyStateDirect(toCollapsed) // first applyState from init, before attach
        return
    }
    cancelTransition()
    val repo = repository ?: return
    val screenWidth = repo.screenWidth.value
    val targetX = OverlayGeometry.edgeX(
        isLeftEdge, screenWidth,
        if (toCollapsed) collapsedWidthPx() else panelWidthPx()
    )
    val startX = currentX
    transitionToCollapsed = toCollapsed
    panelRoot.visibility = VISIBLE
    bubble.visibility = VISIBLE
    if (toCollapsed) {
        panelRoot.alpha = 1f; panelRoot.scaleX = 1f; panelRoot.scaleY = 1f
        bubble.alpha = 0f; bubble.scaleX = BUBBLE_ENTER_SCALE_MIN; bubble.scaleY = BUBBLE_ENTER_SCALE_MIN
    } else {
        panelRoot.alpha = 0f
        panelRoot.scaleX = 1f - PANEL_EXIT_SCALE_DELTA; panelRoot.scaleY = 1f - PANEL_EXIT_SCALE_DELTA
        bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f
    }
    transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = TRANSITION_DURATION_MS
        interpolator = decelerateInterpolator
        addUpdateListener { a ->
            val t = a.animatedValue as Float
            currentX = (startX + (targetX - startX) * t).toInt()
            onUpdatePosition?.invoke(currentX, currentY)
            panelRoot.alpha = if (toCollapsed) 1f - t else t
            val panelScale = 1f - PANEL_EXIT_SCALE_DELTA * (if (toCollapsed) t else 1f - t)
            panelRoot.scaleX = panelScale; panelRoot.scaleY = panelScale
            bubble.alpha = if (toCollapsed) t else 1f - t
            val bubbleScale = BUBBLE_ENTER_SCALE_MIN + (1f - BUBBLE_ENTER_SCALE_MIN) * (if (toCollapsed) t else 1f - t)
            bubble.scaleX = bubbleScale; bubble.scaleY = bubbleScale
        }
        addListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                applyStateDirect(transitionToCollapsed)
                if (transitionAnimator === animation) transitionAnimator = null
            }
        })
        start()
    }
}

/** Applies a transition's end state instantly (visibility + reset of animated properties). */
private fun applyStateDirect(toCollapsed: Boolean) {
    resetTransitionVisuals()
    if (toCollapsed) {
        bubble.visibility = VISIBLE
        panelRoot.visibility = GONE
    } else {
        panelRoot.visibility = VISIBLE
        bubble.visibility = GONE
    }
}

private fun resetTransitionVisuals() {
    panelRoot.alpha = 1f; panelRoot.scaleX = 1f; panelRoot.scaleY = 1f
    bubble.alpha = 1f; bubble.scaleX = 1f; bubble.scaleY = 1f
}

private fun cancelTransition() {
    val anim = transitionAnimator ?: return
    transitionAnimator = null
    anim.cancel() // synchronous onAnimationEnd → applyStateDirect(transitionToCollapsed)
}
```

- [x] **Step 4: 生命周期与触摸取消**

`onInterceptTouchEvent` 的 `ACTION_DOWN` 分支追加 `cancelTransition()`；
`onDetachedFromWindow` 在 `snapAnimator?.cancel()` 旁追加 `cancelTransition()`；
`performSnap` 的 snap animator 追加 `interpolator = decelerateInterpolator`。

- [x] **Step 5: applyRunning / applySettings 的图标绑定**

```kotlin
statusPill.text = if (running) "运行中" else "已暂停"
toggleBtn.text = if (running) "暂停滑动" else "开始滑动"
toggleBtn.setIconResource(if (running) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play)
```
running 分支 `toggleBtn.iconTint = ColorStateList.valueOf(fg色)`；paused 分支 `iconTint = ColorStateList.valueOf(on_accent色)`（与 textColor 同源）。原三处 `android.content.res.ColorStateList.valueOf` 全限定用法统一改用新 import。
```kotlin
directionValue.text = if (s.direction == ScrollDirection.DOWN) "向下" else "向上"
directionValue.setCompoundDrawablesRelativeWithIntrinsicBounds(
    if (s.direction == ScrollDirection.DOWN) R.drawable.ic_overlay_arrow_down else R.drawable.ic_overlay_arrow_up,
    0, 0, 0
)
```

- [x] **Step 6: BubbleView 按压缩放反馈 + KDoc 更新**

```kotlin
private val pressInterpolator = DecelerateInterpolator()

override fun setPressed(pressed: Boolean) {
    super.setPressed(pressed)
    val target = if (pressed) 0.85f else 1f
    animate().scaleX(target).scaleY(target)
        .setDuration(90)
        .setInterpolator(pressInterpolator)
        .start()
}
```
KDoc 改述：dark-glass bubble（deep surface + cyan ring + ghost glyph），elevation 阴影保留。

- [x] **Step 7: 验证** — `gradlew :app:assembleDebug` + `gradlew :app:testDebugUnitTest`，期望全绿。
- [x] **Step 8: Commit** — `feat(overlay): expand/collapse transition, vector glyph binding, bubble press feedback`

### Task 4: 文档同步与最终验证

**Files:**
- Modify: `AGENTS.md`（§9 配色描述）
- Modify: `app/src/main/java/com/phantom/scroll/ui/theme/Theme.kt:8-15`（KDoc）

- [x] **Step 1: AGENTS.md §9**

「悬浮窗浅色/绿色高对比度配色（`overlay_*`）」→「悬浮窗暗黑玻璃拟态配色（`overlay_*`，与主界面 `phantom_*` 同源：`#1E1E24` 卡片 / `#00E5FF` 青色强调 / 琥珀 `#D98324` 暂停警示）」。

- [x] **Step 2: Theme.kt KDoc**

```kotlin
/**
 * Dark neon-glow theme for the permission guide page (AGENTS.md design spec):
 * background #0F0F12, cards #1E1E24, accents #00E5FF / #2979FF.
 *
 * The native floating overlay uses its own Material3 dark theme
 * (res/values/themes.xml → Theme.PhantomScroll.Overlay) sharing this same palette
 * (dark glass + cyan accent) via the overlay_* tokens in res/values/colors.xml;
 * this Compose scheme still must not leak into the overlay at runtime.
 */
```

- [x] **Step 3: 最终验证** — `gradlew :app:assembleDebug :app:testDebugUnitTest`。
- [x] **Step 4: Commit** — `docs: sync overlay dark-glass palette notes (AGENTS/Theme)`

## Self-Review

1. **Spec coverage**: 调色板（Task1 Step1-3）、暗色 M3 主题（Task1 Step2）、7 矢量图标（Task1 Step4）、Switch 着色（Task1 Step5）、布局字形替换/按钮 icon/9sp（Task2）、气泡重设计（Task1 Step3 + Task2 Step4）、过渡动画/缓动/取消安全（Task3 Step1-4）、pill 与按钮文案（Task3 Step5）、方向 compound drawable（Task3 Step5）、气泡按压反馈（Task3 Step6）、文档同步（Task4）。README 无配色描述（已核实），无需改动。
2. **Placeholder scan**: 无 TBD/TODO；所有代码步骤含全文。
3. **Type consistency**: `transitionAnimator/transitionToCollapsed/cancelTransition/applyStateDirect/resetTransitionVisuals` 命名在 Step 2-4 间一致；drawable 名与 Task 2/3 引用一致。
