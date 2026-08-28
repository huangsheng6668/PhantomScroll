# 悬浮窗暗黑玻璃拟态 UI 统一设计（2026-08-28）

## 背景与问题

用户要求「优化项目 UI，尤其是悬浮窗」。现状审查发现四个问题：

1. **视觉割裂**：悬浮窗使用白底 + 绿强调（`overlay_accent #1E9E55`）的独立亮色 Material 主题，
   与主界面（暗黑渐变发光 Compose 页，`#0F0F12` / `#1E1E24` / `#00E5FF`）风格完全不同。
   两套配色「刻意独立」的历史理由（高对比度）已不成立：深色面板在浅色阅读页面上对比度同样充足。
2. **动效缺失**：面板 ↔ 气泡切换是硬切（visibility 瞬变），只有吸附（250ms）和设置子面板
   淡入（120ms）有动画；吸附动画无缓动插值器。
3. **字形图标**：折叠 `⌄`、设置 `⚙`、播放 `▶`、暂停 `⏸`、状态点 `●`、方向箭头 `↑/↓` 全部是
   Unicode 字符，违背项目「完全摒弃 Emoji/字形图标、矢量自绘」的设计原则（主界面已是 Canvas 矢量）。
4. **气泡弱可视**：20dp 白底绿圈气泡在白色页面上存在感弱，依赖 6dp 阴影兜底。

## 目标

1. 悬浮窗视觉与应用主色调统一：**暗黑玻璃拟态**（`#1E1E24` 半透明卡片 + `#00E5FF` 青色强调 +
   琥珀 `#D98324` 暂停警示），token 与主界面同源。
2. 展开/收起加入 160ms 缩放 + 透明度 + 窗口位移动画（decelerate 缓动）；吸附动画加
   `DecelerateInterpolator`；气泡按压缩放反馈。
3. 所有字形图标替换为矢量 drawable（chevron / tune / play / pause / 箭头 / 幽灵 glyph）。
4. 气泡重设计：深色玻璃底 + 青色描边 + 青色幽灵 glyph（主界面吉祥物的矢量简化版）。

## 非目标（YAGNI）

- 不改面板信息架构：2×2 参数网格、行结构、状态机、`OverlayGeometry` 几何常量、触摸拦截逻辑全部不动。
- 不改主界面 `MainScreen`（已符合规格、近期刚重构达标）。
- 不引入预设 chips（小说/漫画）与统计 badge（历史规格已明确移除）。
- 保持原生 View + Material Components 实现（AGENTS.md 架构决策，不引入 Compose 运行时）。

## 方案取舍

| 方案 | 内容 | 结论 |
|------|------|------|
| A（采纳） | 暗黑玻璃拟态统一 + 动效 + 矢量图标 + 气泡重设计；交互结构不动 | 视觉收益最大、风险可控（改动集中在资源层 + 一个视图类） |
| B | 只做动效 + 图标 + 气泡，保持白绿配色 | 改动最小，但「与主界面统一」的核心诉求未解决 |
| C | 重构布局（底部胶囊控制条/多层抽屉）+ 全面重写动效 | 改动大、回归风险高，超出「优化」范畴 |

## 调色板映射（token 名不变，只换值）

| Token | 旧值（白/绿） | 新值（暗黑玻璃） | 用途 |
|-------|--------------|------------------|------|
| `overlay_surface` | `#FFFFFFFF` | `#F21E1E24`（95% 不透明，玻璃感） | 卡片底 |
| `overlay_surface_2` | `#FFF4F4F6` | `#FF292933` | chip / 参数格底 |
| `overlay_surface_3`（新增） | — | `#FF33333F` | 开关未选中轨道等浮起面 |
| `overlay_fg` | `#FF2A2A33` | `#FFF5F5F7` | 主文字 |
| `overlay_muted` | `#FF6E6E76` | `#FFA8A8B2` | 次级文字 / 图标 |
| `overlay_subtle` | `#FF888892` | `#FF7A7A86` | 弱文字（meta） |
| `overlay_border` | `#FFE1E1E6` | `#FF3C3C48` | 描边 / 滑轨未激活 |
| `overlay_accent` | `#FF1E9E55`（绿） | `#FF00E5FF`（青，= `phantom_cyan`） | 强调 / 激活 |
| `overlay_accent_soft` | `#FFE8F5EC` | `#2E00E5FF`（18% 青） | 运行 pill / 头像底 |
| `overlay_warning` | `#FFD98324` | `#FFD98324`（不变，= 主界面琥珀） | 暂停警示 |
| `overlay_warning_soft` | `#FFFBEFD9` | `#2ED98324`（18% 琥珀） | 暂停 pill 底 |
| `overlay_on_accent` | `#FFFFFFFF` | `#FF00262B`（青底深字，= `onPrimary`） | 青底上的文字/图标 |

主题：`Theme.PhantomScroll.Overlay` parent 由 `Theme.Material3.Light.NoActionBar` 改为
`Theme.Material3.Dark.NoActionBar`（Slider/MaterialSwitch 的 M3 默认色随暗色主题）。
`MaterialSwitch` 显式给定 track/thumb `ColorStateList`（选中青色 / 未选中 `surface_3`、灰白 thumb）。

## 动效设计

全部为**状态切换时的一次性动画**，不在滑动热路径上，不违背零 GC/零卡顿原则：

- **过渡（新增）**：`applyState(Collapsed/Expanded)` 由硬切改为 160ms 过渡——单一 `ValueAnimator`
  驱动：窗口 X（气泡边 ↔ 面板边线性插值，解决窗口重锚时气泡跳变）、面板 alpha 1→0 +
  scale 1→0.92、气泡 alpha 0→1 + scale 0.5→1（展开方向反向）。`DecelerateInterpolator(1.3)`。
  结束/取消时幂等复位到目标终态；`ACTION_DOWN`、`onDetachedFromWindow` 会取消过渡，
  取消回调按过渡目标终态收敛，与 `panelStateFlow.value` 恒一致。首次 `init`（未 attach）走直切路径。
- **吸附**：保持 250ms，补 `DecelerateInterpolator`（共享实例，避免每次分配）。
- **气泡按压**：`BubbleView.setPressed` 缩放至 0.85（90ms），提供触觉层面的反馈。
- **状态 pill**：文字去掉 `●` 字形（pill 底色已编码运行/暂停状态），按钮文字去掉 `▶`/`⏸` 字形。

## 矢量图标（新增 drawable，24dp viewport）

| Drawable | 形状 | 位置 |
|----------|------|------|
| `ic_overlay_fold` | chevron-down（2dp 圆头描边） | 折叠按钮 |
| `ic_overlay_tune` | 双滑杆 tune 图标（描边 + 实心旋钮） | 设置按钮（替代 `⚙`） |
| `ic_overlay_play` | 实心三角（14dp） | 主按钮-开始 |
| `ic_overlay_pause` | 双竖条（3dp 圆头描边，14dp） | 主按钮-暂停 |
| `ic_overlay_arrow_down` / `up` | 箭头（14dp，青色 tint） | 方向值 compound drawable |
| `ic_overlay_ghost` | 幽灵 glyph（拱顶 + 锯齿裙边 + 镂空双眼，青色填充） | 气泡（替代 launcher mipmap） |

## 涉及文件

**资源**：`values/colors.xml`、`values/themes.xml`、`drawable/overlay_card_border|chip_bg|param_cell_bg|
bubble_bg|status_pill_*.xml`、新增 `drawable/ic_overlay_*.xml`（7 个）、`color/overlay_switch_track|thumb.xml`（2 个）。
**布局**：`layout/overlay_panel.xml`（字形→ImageView/vector；按钮加 icon 属性；perapp_label 8sp→9sp）、
`overlay_bubble.xml`（ghost vector）、`overlay_param_cell.xml`、`overlay_settings.xml`（token 自动生效，微调）。
**代码**：`FloatingOverlayView.kt`（过渡动画、图标/箭头绑定、pill 文案）、`BubbleView.kt`（按压反馈 + KDoc）。
**文档**：`AGENTS.md` §9、`ui/theme/Theme.kt` KDoc、README 配色描述同步更新。
**测试**：`OverlayGeometryTest` / `ParamStepsTest` 不受影响（几何与分档标签未改）。

## 验证

1. `gradlew :app:assembleDebug` 编译通过。
2. `gradlew :app:testDebugUnitTest`（全量单测，含 :gesture 与 data 层）通过。
3. 资源引用一致性：所有新 token/drawable 在布局与代码中引用闭环（编译期校验）。
