# 悬浮窗重新优化设计文档（浅色/绿主题 + 组件化）

> 日期：2026-06-22
> 状态：已批准，待实施
> 方案：方案 B（组件化抽离，保留原生 View）
> 前置文档：[2026-06-14-phantomscroll-v2-optimization-design.md](./2026-06-14-phantomscroll-v2-optimization-design.md)（V2 四阶段已落地）
> 参考视觉：`floating-window-proper.html`（用户提供的设计稿，通用展示稿）

---

## 概述

在 V2 Phase 2（悬浮窗已从 Compose 重写为原生 View `FloatingOverlayView`）的基础上，依据用户提供的设计稿对悬浮窗进行**视觉与交互层面的重新优化**。

**项目现状**：悬浮窗为 130dp 紧凑面板，深色底 `#1E1E24` + 青色霓虹 `#00E5FF`，含标题/预设芯片/三个连续滑块/方向切换/统计行/per-app 开关/播放按钮；折叠态为 8×36dp 边缘吸附手柄。

**目标状态**：浅色底 + 绿色强调的分区卡片式面板；2×2 参数网格（档位显示 + 展开精调）；56dp 气泡折叠（带实时计数角标）+ 边缘吸附；预设收进 ⚙ 设置子面板。主界面与通知同步换肤。

**核心约束**：纯 UI/交互层改造，**不触碰手势引擎、数据模型、DataStore key**；保留 V2 Phase 2 的原生 View 内存收益（不回 Compose）；对滚动行为/预设/per-app/统计**零功能影响**，可整体 revert。

---

## 目标与非目标

### 目标

1. 悬浮窗面板视觉重构为**浅色/绿主题**的分区卡片式结构（头部 / 状态胶囊 / 大号计数 / 2×2 参数网格 / per-app 开关 / 操作栏）。
2. 参数控制改为**混合模式**：网格显示友好档位文本，点击格子展开内联迷你滑块精调；连续数据模型不变。
3. 折叠态从边缘细手柄改为 **56dp 气泡 + 计数角标**，保留"吸附最近边缘"行为。
4. 预设（小说/漫画/自定义）与"忘记当前 App"收进 **⚙ 设置子面板**，主面板保持精简。
5. MainActivity（Compose 权限页）与通知强调色**同步换肤**为浅色/绿。
6. 抽出可单测的**纯逻辑**（参数档位解析、角标格式化），更新几何单测。

### 非目标（明确不做）

- **横向滑动 / 四向方向**：本应用面向纵向阅读，方向仅保留 ↑/↓（设计稿的 ↓↑→← 不采纳）。
- **距离数据模型改 px**：内部仍为 `distanceRatio`(0.30–0.95)，仅在 UI 换算为等效 px 展示。
- **回 Compose**：不推翻 V2 Phase 2 的原生 View 决定。
- **折叠态拖拽**：气泡仅点击展开，不在折叠态拖动（降复杂度）。
- **改启动器图标**：保留刚替换的 neon ghost 图标（cyan 令牌仅供图标 drawable 复用）。
- **DataStore 迁移 / key 变更**：零持久化改动。
- **悬浮窗视图层 Robolectric 测试**：与现有 `FloatingOverlayView` 一致，视图层不引 Robolectric，仅纯逻辑单测。

---

## 设计决策记录（与设计稿的差异及理由）

设计稿为通用展示稿（示例写"抖音"、方向四向、距离用 px），与 PhantomScroll 的纵向专用 / 连续滑块 / 幽灵品牌存在张力。经澄清确认以下四项关键决策：

| # | 决策 | 理由 |
|---|---|---|
| 1 | **整套换浅色/绿色主题**（非保留 cyan/dark） | 用户选择给 Phantom 重新做视觉品牌；主界面/通知同步换肤保持一致 |
| 2 | **混合参数控制**：档位显示 + 展开精调 | 浮窗小滑块难操作（现需大量 touch-intercept），离散循环顺但丢精度；混合两者兼得 |
| 3 | **方向仅 ↑/↓ 垂直** | 纵向滑动是核心，横向属另一引擎，不在"优化悬浮窗"范围 |
| 4 | **气泡 + 吸附边缘**折叠 | 采纳设计稿气泡+角标，同时保留近期刚打磨的边缘吸附/刘海贴边 |

**与设计稿的结构性差异**（贴合现有数据模型）：
- 设计稿"方向"在计数右侧与网格第 4 格**两处重复** → 计数右侧改放**累计时长**（`stats.elapsedMs`，原先无归宿的"约 M 分钟"），方向只留网格。
- 预设不在主面板（设计稿无预设行） → 收进 ⚙。
- 主按钮语义：**运行中=中性描边("暂停滑动")，已暂停=绿色实心("开始滑动")**（设计稿语义，比现状 running=红 更直觉）。

---

## 信息架构与布局

面板宽度 **约 240dp**（现 130dp ↔ 设计稿 320dp 之间，兼顾 2×2 网格密度与阅读遮挡，可调）。

> ⚠️ 实施注意：现有 `FloatingOverlayView.kt` 中面板宽度 `130f` **硬编码 3 处**（`applyState`/`repositionToBounds`/`onTouchEvent` 各 1），外加折叠手柄宽 `32f` **2 处**（`applyState`/`repositionToBounds`）——改尺寸时须一并替换。建议将面板宽提取为 `OverlayGeometry.PANEL_WIDTH_DP`、折叠宽为 `COLLAPSED_WIDTH_DP` 常量统一引用。

### 展开态面板

```
┌──────────────────────────────────────┐
│         ▬▬▬  (drag handle)            │  ← 仅视觉指示拖拽区域，非独立热区；整面板均可拖
│ ┌──┐  自动滑动          ⌄折叠  ⚙设置   │  头部：幽灵图标(绿描边) + 标题/AUTO SCROLL + 折叠/设置
│ │👻│  AUTO SCROLL                     │
│ └──┘                                  │
│ ●运行中              前台 · com.x.x    │  状态胶囊(脉冲点, 绿/琥珀) + 当前 App 包名
│────────────────────────────────────── │
│  已滑动                 时长           │
│   142 次           约 6 分钟           │  大号计数(swipeCount) + 累计时长(elapsedMs)
│────────────────────────────────────── │
│ 速度          │ 间隔                   │
│ 中速 3x   ▣  │ 2.5s    ▣              │  2×2 网格；点格子 → 格内展开迷你滑块
│ 距离          │ 方向                   │  （方向格为 ↑/↓ 两态切换，非滑块）
│ 580px  ▣    │ ↓ 向下  ▣              │
│────────────────────────────────────── │
│ 按 App 分别记录            (●) 开关    │  per-app 开关
│────────────────────────────────────── │
│ [   ⏸ 暂停滑动   ]        [↻]         │  主按钮(运行=描边/暂停=绿实心) + 重置
└──────────────────────────────────────┘
```

网格说明：3 个 `ParamCellView`（速度/间隔/距离，slider 模式）+ 1 个方向切换格，共用同一视觉格子样式（背景/标签排版一致）。

### 折叠态（气泡 + 吸附边缘）

```
靠左：         靠右：
┌────┐         ┌────┐
│ 👻 │         │ 👻 │   56dp 圆形气泡，浅底 + 绿描边
└────┘         └────┘
   [N]          [N]      计数角标(swipeCount，>99 显示 99+)，折叠态也实时跳动
```

> ⚠️ 角标性能：`BubbleView` 的 badge `setText()` 每次调用触发 layout pass。实现时须在 `setText()` 前做 `if (currentText != newText)` 短路判断，避免高频滑动模式（~0.6次/秒）下的无意义 relayout。

```
x≈0(全显)       x≈屏宽-56  完全可见地贴边
```

交互：拖拽面板松手 → 沿用"吸附最近边缘"动画 → 切折叠气泡（复用 `OverlayGeometry.snapTarget`，折叠视觉由细手柄换成气泡）；点气泡 → 展开。

---

## 组件拆分（方案 B）

| 组件 | 职责 | 持有 |
|---|---|---|
| `ParamCellView` | 2×2 网格的**单格**（写一次、复用 3 次）。标签 + 当前档位文本 + 展开后的迷你 `Slider` | 自身 `isExpanded`；value/valueFrom/valueTo；档位文本解析器；`onUserChange` 回调 |
| 方向切换格 | ↑/↓ 两态切换（独立简单布局，复用格子视觉样式） | 当前 `ScrollDirection`；点击回调 |
| `BubbleView` | 折叠气泡：56dp 圆形图标 + 计数角标 | 图标 drawable；badge 文本 |
| `FloatingOverlayView`（瘦身） | **编排者**：inflate 面板/气泡/设置三套布局，收集所有 flow 绑定，转发交互；不再内联网格逻辑 | 3 个 `ParamCellView` + 1 方向格；header/status/metric/toggle/action 绑定 |
| `overlay_settings.xml`（⚙ 子面板） | 预设芯片（小说/漫画/自定义）+ "忘记当前 App"。同一悬浮窗内 visibility 切换 | 复用现有 chip drawable（改色） |

**⚙ 子面板交互模式**：点击 ⚙ 按钮后，主面板内容区（状态胶囊以下、操作栏以上）**整体替换**为设置子面板（`GONE`/`VISIBLE` 切换，建议加 `crossfade` 120ms 过渡动画）；头部与底部操作栏保持不变。再次点击 ⚙ 或点击主面板外部回到主面板。若面板贴近屏幕底部边缘，子面板高度应 `wrap_content` 且面板整体通过 `repositionToBounds` 自动上推，防止溢出。

抽出**纯逻辑**（沿用 SpeedCurve/OverlayGeometry 可测风格），单独 JVM 单测：
- `ParamSteps.toSpeedLabel(durationMs) → "中速 3x"`（duration→档位+倍率）
- `ParamSteps.toIntervalLabel(intervalMs) → "2.5s"`
- `ParamSteps.toDistanceLabel(distanceRatio, screenH) → "中距 580px"`
- `BadgeFormatter.format(count) → "N" 或 "99+"`

档位映射（实现时可微调阈值）：
- 速度(duration 150–1500ms)：duration 越短 = 滚动越快 = 倍率越高。极速 8x(<400) / 快速 5x(400–700) / 中速 3x(700–1050) / 慢速 1x(>1050)
  > ⚠️ 范围说明：Slider XML `valueFrom="150.0"` 与此处一致，但 `ScrollSettings.kt` 的 KDoc 注释写 `范围 200..1500`，实施时须同步修正 KDoc 为 `150..1500`。
- 间隔(interval 500–10000ms)：显示秒数 `%.1fs`，档位 快(<1500)/中(1500–4000)/慢(>4000)
- 距离(distanceRatio 0.30–0.95)：等效 px = `ratio × screenH`，档位 短(<0.5)/中(0.5–0.75)/长(>0.75)，显示如"中距 580px"

---

## 主题令牌（`colors.xml` 新增；oklch→hex 近似，实现时微调）

```
overlay_surface      #FFFFFF    overlay_surface_2   #F4F4F6
overlay_fg           #2A2A33    overlay_muted       #6E6E76    overlay_subtle  #888892
overlay_border       #E1E1E6    overlay_border_2    #ECECEF
overlay_accent       #1E9E55    overlay_accent_2    #3FB373    overlay_accent_soft  #E8F5EC   (绿：运行/主CTA/胶囊)
overlay_warning      #D98324    overlay_warning_soft #FBEFD9   (琥珀：已暂停)
```

- 头部幽灵图标保留（向 Phantom 品牌点头），改绿色描边。
- `Theme_PhantomScroll_Overlay`（给 Slider 的 Material3 context）→ 浅色/绿基底。
- 启动器图标：保留 neon ghost；cyan 令牌仅供图标 drawable 复用，不进新主题。
- 通知强调色（`NotificationHelper`）→ 绿。具体实施：在 `buildNotification()` 的 `NotificationCompat.Builder` 链中增加 `.setColor(ContextCompat.getColor(context, R.color.overlay_accent))`（当前代码**未调用** `setColor()`，需新增）。

---

## 状态机与几何

`PanelState` 枚举**不变**（Expanded / Snapping / Collapsed），仅改折叠视觉与几何常量：

- `Collapsed` 视觉 = `BubbleView`（原 `handle`）。`applyState`：Collapsed→显气泡隐面板；Expanded→显面板隐气泡；Snapping→显面板做动画。
- 折叠宽 **32dp → 56dp**（气泡）。`OverlayGeometry.snapTarget / edgeX` 入参随之改；`OverlayGeometryTest` 同步更新。建议把折叠宽参数化（`collapsedWidthPx` 入参）而非硬编码，便于气泡/手柄切换。
- 面板宽 **130dp → 240dp**。`FloatingOverlayView` 中所有 `(130f * density)` 硬编码（`applyState`×1、`repositionToBounds`×1、`onTouchEvent`×1）须统一替换；建议与折叠宽一起提取为 `OverlayGeometry` 常量。
- 气泡贴边**完全可见**（x=0 或 x=屏宽−56dp），不再半隐藏。
- 新增**面板内**交互态（不改 `PanelState`）：`ParamCellView.isExpanded`；展开滑块时，`FloatingOverlayView.onInterceptTouchEvent` 的"可交互子节点"列表加入该滑块，避免整面板拖动抢走滑块手势（扩展现有 `disallowIntercept` 机制）。
- 气泡**仅点击展开**，不在折叠态拖拽。外部点击(`ACTION_OUTSIDE`)/⚙→折叠 等现有逻辑保留。

---

## 数据流

全部沿用现有 `SettingsRepository` flow，**零模型改动**：

- 4 格绑定：`速度←duration` · `间隔←interval` · `距离←distanceRatio(+screenH)`（`ParamCellView` slider 模式）；`方向←direction`（2 态切换格）。
- 头部胶囊 ← `isRunning`(运行绿/暂停琥珀) + `currentPackage`。
- 大号计数 ← `stats.swipeCount` + `stats.elapsedMs`。
- per-app 开关 ← `perAppEnabled`；⚙ 忘记 ← `forgetActiveProfile()`；重置 ← `resetStats()`；主按钮 ← `toggleRunning()`。
- **气泡角标即使折叠也实时跳动** ← `stats.swipeCount`（折叠态反馈，优于现手柄）。
- 用户改值 → `ParamCellView.onUserChange` → `FloatingOverlayView.updateActive{}` → repo（现路径不变，`applyingFromFlow` 守卫复用）。

---

## 测试

遵循项目"纯逻辑才单测、无 Robolectric"边界：

- **新增纯逻辑单测**：`ParamStepsTest`（3 个 label 解析器 + 档位边界）+ `BadgeFormatterTest`（`99+` 边界）→ 约 8 个 JVM 用例。
- **更新** `OverlayGeometryTest`：折叠宽 32→56dp 的 snap/clamp 断言。
- 视图层（`FloatingOverlayView` / `ParamCellView` / `BubbleView`）**不加单测**——与现 `FloatingOverlayView` 无测试一致。
- 引擎/预设/per-app/统计等现有测试**零影响**。

---

## 换肤范围（文件清单）

**新增**：
- `res/layout/overlay_bubble.xml`、`res/layout/overlay_settings.xml`
- `ParamCellView.kt`、`BubbleView.kt`、`ParamSteps.kt`、`BadgeFormatter.kt`（`ui/overlay/`）
- 浅色 drawable：`overlay_bubble_bg`、`overlay_status_pill_running`、`overlay_status_pill_paused`、新版 `overlay_card_border`、`overlay_chip_bg`（覆盖改色）

**重写**：
- `res/layout/overlay_panel.xml`、`FloatingOverlayView.kt`（瘦身）

**改色/微调**：
- `res/values/colors.xml`（加 overlay_* 令牌）
- `ui/theme/Color.kt`（新增浅色/绿色令牌：`OverlayGreen`、`LightBackground`、`LightSurface` 等）
- `ui/theme/Theme.kt`（`darkColorScheme` → `lightColorScheme`，primary/accent 映射为绿色；`OverlayTheme` 可移除——悬浮窗已是原生 View，该函数为 V1 遗留死代码）
- `notification/NotificationHelper.kt`（增加 `.setColor(R.color.overlay_accent)`，强调色→绿）
- `ui/overlay/OverlayGeometry.kt`（折叠宽参数化 32→56dp，面板宽参数化 130→240dp，新增 `PANEL_WIDTH_DP` / `COLLAPSED_WIDTH_DP` 常量）

**删除**：
- `res/layout/overlay_handle.xml`（被气泡取代，避免死代码）

**不动**：启动器图标、手势引擎（`GestureEngine`/`SpeedCurve`/`ScrollOrchestrator`）、`data/` 全部、DataStore key、`FloatingWindowController.kt`（flags 逻辑已兼容气泡折叠态——Collapsed 态不带 `FLAG_WATCH_OUTSIDE_TOUCH`，无需改动）。

---

## 风险与兜底

| 风险 | 兜底 |
|---|---|
| 展开滑块时手势抢夺 | `disallowIntercept` 扩展覆盖展开滑块；真机验证 |
| 浅色对比度不足 | 实现后核对绿/琥珀在浅底上的可读性 |
| V2 Phase 2 内存收益丢失 | 仍原生 View，不回 Compose；收益保留 |
| 改动面大、难 revert | 纯 UI 层改造，引擎/数据零影响，可整体 revert |
| 小屏 2×2 网格触控精确性 | 240dp 宽下每格约 110×48dp，展开迷你 Slider 后须确保 thumb 触控区 ≥48dp；**5 寸/720p 真机验证** |
| ⚙ 设置子面板溢出屏幕 | 面板贴底时子面板展开可能越界；`repositionToBounds` 在子面板可见时须重新计算面板高度并自动上推 |
| 气泡角标高频 relayout | `BubbleView.setText()` 前做 `currentText != newText` 短路判断，避免 0.6次/秒的无意义 layout pass |

---

## 验收标准

1. 展开态面板为浅色/绿、240dp、含头部/状态胶囊/大号计数/2×2 网格/per-app/操作栏，布局与上方 wireframe 一致。
2. 点速度/间隔/距离格 → 展开内联迷你滑块精调；松开后档位文本正确反映连续值；方向格点一下 ↑↔↓。
3. 折叠态为 56dp 气泡 + 计数角标，吸附最近边缘且完全可见；运行时角标实时跳动；点气泡展开。
4. ⚙ 打开设置子面板：预设单选高亮 + 忘记当前 App 可用；子面板展开/收起有过渡动画。
5. 主按钮：运行=中性描边"暂停滑动"，暂停=绿色实心"开始滑动"；状态胶囊同步绿/琥珀。
6. MainActivity 与通知强调色为浅色/绿，视觉与悬浮窗一致；通知 `setColor()` 生效。
7. `./gradlew test` 全绿（含新增 `ParamStepsTest`/`BadgeFormatterTest` 与更新的 `OverlayGeometryTest`）。
8. 手势引擎、预设、per-app、统计行为与改造前**逐项一致**（仅 UI 变）。
9. 5 寸/720p 小屏设备上 2×2 网格格子可点击、展开 Slider 可正常拖动、不被面板拖拽抢夺。
10. 锁屏恢复后气泡角标数值正确，面板展开后计数与统计一致。
11. 面板拖拽到屏幕边缘后吸附，随后旋转屏幕不越界（`repositionToBounds` 回调正常工作）。
