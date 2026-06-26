# Role

你是一个资深的 Android 顶尖架构师，精通 Kotlin 异步高并发编程、Jetpack Compose 动画与自定义手势拦截、Android 无障碍服务（Accessibility Service）底层的事件注入机制。你对 Android 系统的内存管理、线程调度以及防止 UI 掉帧（Jank）有极深的造诣。

# Task

我需要开发一个高性能、零卡顿的 Android 自动化辅助工具 **"PhantomScroll"**，核心功能是通过无障碍服务模拟逼真的拟人化跨应用**纵向滑动**，辅助用户阅读小说或漫画。请为我提供完整的项目架构设计与关键核心代码实现。

# Architecture Decisions

| 决策项 | 结论 |
|--------|------|
| 服务架构 | 合并为单一 `PhantomScrollService extends AccessibilityService` |
| 项目名称 | PhantomScroll |
| 锁屏行为 | 屏幕熄灭/锁屏后自动**暂停**滑动，亮屏后恢复 |
| 滑动方向 | 支持**纵向滑动**（包含向上 UP 与向下 DOWN 滚动切换） |
| compileSdk / targetSdk | API 35 (Android 15) |
| minSdk | API 26 (Android 8.0) |

# Technical Stack & Refined Requirements

## 1. 悬浮窗设计与边缘吸附状态机 (WindowManager & Native Overlay)

- **悬浮窗构建**：使用系统 `WindowManager` 动态添加全局悬浮窗，`LayoutParams` 必须正确配置 `TYPE_APPLICATION_OVERLAY`、`FLAG_NOT_FOCUSABLE`、`FLAG_LAYOUT_IN_SCREEN` 并设置刘海屏适配（如 `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`），且 `LayoutParams.gravity = Gravity.TOP or Gravity.LEFT`。同时需在状态机切换（Expanded/Collapsed）更新标志时，保留 `FLAG_LAYOUT_IN_SCREEN` 以确保全屏模式下边缘贴合和正常隐藏。
- **UI 框架分工**：主界面权限引导页采用 Jetpack Compose 构建；悬浮窗控制面板全面重写为**原生 View (XML 布局 + Material Components)**，彻底移除阅读期间常驻的 Compose 运行时以节省内存。
- **拖拽与状态机**：利用自定义 View 的 `onTouchEvent` 与 `onInterceptTouchEvent` 拦截与监听用户拖拽。悬浮窗内部维护一个状态机（State）：`Expanded`（展开面板）、`Snapping`（吸附中动画）、`Collapsed`（边缘折叠手柄）。
- **边缘吸附与折叠动画**：
  - 当拖拽结束时，计算当前 $X$ 坐标。若超过屏幕宽度的一半，利用 `ValueAnimator` 动画将悬浮窗平滑推至右边缘，反之推至左边缘（250ms 吸附）。
  - 吸附完成后，自动切换为 `Collapsed` 状态：控制面板隐藏，仅在边缘渲染一个高透明度、宽 32dp 的"手柄（Handle）"。
  - 点击或向内滑动该手柄，平滑展开控制面板。
- **触控拦截守护**：在 parent custom view 中监听 `ACTION_DOWN`，判断如果触摸点落在交互式子视图（如 Slider、开关、方向切换、忘记 App 配置按钮等）的 global bounds 范围内，则将 `disallowIntercept` 设为 true，防止微小位移导致 ViewGroup 拦截事件并取消子视图的点击行为。

## 2. 状态管理与单一真相源 (SettingsRepository & DataStore)

- **全局唯一真相源**：在 Service 内部引入 `data/` 层，通过 `SettingsRepository` 持有所有核心 `MutableStateFlow` 并进行集中状态分发。
- **领域模型**：
  - `ScrollSettings`：保存当前的 duration (速度)、interval (间隔)、distanceRatio (距离)、direction (方向)。
  - `ScrollStats`：保存累计翻页次数与运行时长。
  - `AppProfile`：存储前台特定 App 的定制化设置配置。
- **双向同步与节流落盘**：
  - 原生 View 中的 `Slider`、开关等交互控件通过观察 Repository 的 `StateFlow` 进行命令式刷新（零重组开销）。
  - 用户拖动 Slider 改写内存状态立即生效，后台通过协程 Flow `debounce(500ms)` 对 Preferences DataStore 写入节流，杜绝磁盘 I/O 阻塞。

## 3. 极限性能优化与零 GC 消耗设计 (Coroutines & Object Pooling)

连续的高频滑动极易导致内存抖动并触发系统 GC 造成掉帧。必须严格遵循以下优化方案：
- **线程完全隔离**：
  - **UI 线程 (Dispatchers.Main.immediate)**：仅负责悬浮窗的拖拽手势响应、手柄折叠吸附动画。
  - **计算线程 (Dispatchers.Default)**：所有贝塞尔曲线轨迹点、随机噪声、时间加权算法必须在 `Dispatchers.Default` 中异步计算。
- **对象复用 (Object Pooling)**：
  - 绝对禁止在滑动循环中重复 `new Path()`。在 Service 作用域内复用同一个 `android.graphics.Path` 对象，每次计算新轨迹前强制调用 `path.reset()`。
  - 采样点计算解耦为纯 JVM Kotlin 数据类返回，便于在本地 JVM 线程运行高覆盖率单测。
- **无阻塞定时器**：使用协程的 `delay()` 挂起函数替代传统的定时器，确保等待期间 CPU 核心可进入休眠状态，极致省电。

## 4. 工业级拟人化滑动算法 (Bezier Curve & Custom Interpolator)

- **手势注入**：使用 `AccessibilityService.dispatchGesture()`，通过 `GestureDescription.StrokeDescription` 注入事件。
- **动态曲线生成**：算法需动态获取屏幕的宽度和高度，在屏幕中央安全区域（避开顶部状态栏和底部导航栏，默认 0.15 ~ 0.85）生成纵向滑动路径。
- **贝塞尔曲线**：利用二阶贝塞尔曲线公式 $B(t) = (1-t)^2P_0 + 2t(1-t)P_1 + t^2P_2$，在起始点 $P_0$ 和终点 $P_2$ 之间引入一个带有微小随机水平偏移的控制点 $P_1$，使滑动轨迹产生符合人类手指习惯的微小弧度。
- **非对称速度曲线**：实现加速阶段短而急促、减速阶段长而平缓的真实手指运动特征。
- **生物拟人化噪声（Bio-Noise）**：
  - 单次滑动的总距离、持续时间以及间隔时间，必须在用户设定值的基础上动态加入 $\pm 5\% \sim \pm 10\%$ 的正态分布随机浮动。
  - 轨迹采样点之间引入微小的像素级随机抖动（Noise）。

## 5. 产品化特性支持

- **运行统计**：利用协程自动统计并更新已翻页次数与累计分钟，支持点击重置。
- **按 App 记忆配置 (Per-App)**：前台包名变化检测（支持系统 denylist 过滤与 300ms 快速切换防抖），当开启该功能并调整 Slider 时，自动创建并持久化当前 App 的专属配置 profile；切换回普通应用时自动还原全局默认，长按标签即可忘记该 App 配置。

## 6. 系统工程化收尾

- **Baseline Profile**：引入独立的 `:baselineprofile` 模块（使用 `androidx.baselineprofile` 插件），自动在编译 release 时生成 Main 权限页的启动配置文件，将 profile 打入发布包以加速冷启动与首帧渲染。
- **Compose 编译器优化**：通过开启 Compose Compiler 稳定性报告指导重构，将 `MainScreen` 的状态聚合为 `@Immutable PermissionStatus` 减小重组范围。
- **低优先级通知保活**：合并为单进程，通过低优先级状态栏通知对无障碍服务进行保活，并且通知包含快捷 Action 按钮支持一键暂停/恢复。

# What I Need From You

请按照生产环境标准，分模块提供结构严密、带有详尽注释的 Kotlin 代码：

| # | 模块 | 文件/类名 | 说明 |
|---|------|-----------|------|
| 1 | 系统配置 | `AndroidManifest.xml` | 权限声明、Service 注册 |
| 2 | 系统配置 | `res/xml/accessibility_service_config.xml` | 无障碍服务配置 |
| 3 | 主入口 | `MainActivity` + `MainScreen` | 权限检查引导、使用说明 |
| 4 | 核心服务 | `PhantomScrollService` | 合并后的无障碍服务及生命周期管理 |
| 5 | 数据模型 | `data/ScrollSettings.kt` / `SettingsRepository.kt` | 单一真相源及状态仓储服务 |
| 6 | 手势引擎 | `GestureEngine` + `SpeedCurve` | 贝塞尔曲线、Bio-Noise、对象池、采样策略、**双段连续 Stroke 非对称速度曲线、贝塞尔重采样像素抖动、起点随机化** |
| 7 | 悬浮窗 UI | `FloatingOverlayView` | 原生自定义视图、拖拽吸附状态机、交互保护拦截 |
| 8 | 通知管理 | `NotificationHelper` | 通知渠道、前台状态通知构建 |
| 9 | 样式系统 | `res/values/colors.xml` | 暗黑高对比度色值及 Drawable 样式包 |