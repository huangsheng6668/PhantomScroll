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
| 滑动方向 | 仅支持**纵向滑动**（暂不支持横向） |
| compileSdk / targetSdk | API 34 (Android 14) |
| minSdk | API 26 (Android 8.0) |

# Technical Stack & Refined Requirements

## 1. 悬浮窗设计与边缘吸附状态机 (WindowManager & Compose Dragging)

- **悬浮窗构建**：使用系统 `WindowManager` 动态添加全局悬浮窗，`LayoutParams` 必须正确配置 `TYPE_APPLICATION_OVERLAY`、`FLAG_NOT_FOCUSABLE` 以及 `LayoutParams.gravity = Gravity.TOP or Gravity.LEFT`。
- **拖拽与状态机**：使用 Jetpack Compose 的 `Modifier.pointerInput` 与 `detectDragGestures` 监听用户拖拽。悬浮窗内部维护一个状态机（State）：`Expanded`（展开面板）、`Snapping`（吸附中动画）、`Collapsed`（边缘折叠手柄）。
- **边缘吸附与折叠动画**：
  - 当拖拽结束时（`onDragEnd`），计算当前 $X$ 坐标。若超过屏幕宽度的一半，利用 Compose `Animatable` 动画将悬浮窗平滑推至右边缘，反之推至左边缘。
  - 吸附完成后，自动切换为 `Collapsed` 状态：控制面板隐藏，仅在边缘渲染一个高透明度、极窄的半圆或条状"手柄（Handle）"。
  - 点击或向内滑动该手柄，平滑反转动画，重新展开控制面板。
- **UI 规范**：极致极简、高对比度暗黑模式，背景组件使用半透明（如 `Color.Black.copy(alpha = 0.6f)`），确保视觉重心完全留给背景的漫画或小说内容。

## 2. 状态管理与 Slider 实时通信

- **数据流设计**：在 Service 内部维护以下 `MutableStateFlow`：
  1. `scrollDurationFlow`: 单次滑动持续时间（Slider 范围：200ms - 1500ms，默认 500ms）。
  2. `scrollIntervalFlow`: 两次滑动间隔时间（Slider 范围：500ms - 10000ms，默认 2000ms）。
  3. `scrollDistanceFlow`: 单次滑动距离（Slider 范围：屏幕高度 30% - 95%，默认 75%）。
  4. `isRunningFlow`: 当前是否正在执行自动滑动（Boolean）。
- **实时同步**：Compose UI 中的 `Slider` 直接通过状态绑定修改上述数据流，后台执行滑动的协程需通过结构化并发实时读取最新值。

## 3. 极限性能优化与零 GC 消耗设计 (Coroutines & Object Pooling)

连续的高频滑动极易导致内存抖动并触发系统 GC 造成掉帧。必须严格遵循以下优化方案：
- **线程完全隔离**：
  - **UI 线程 (Dispatchers.Main.immediate)**：仅负责悬浮窗的拖拽手势响应、手柄折叠吸附动画。
  - **计算线程 (Dispatchers.Default)**：所有贝塞尔曲线轨迹点、随机噪声、时间加权算法必须在 `Dispatchers.Default` 中异步计算。
- **对象复用 (Object Pooling)**：
  - 绝对禁止在滑动循环中重复 `new Path()`。在 Service 作用域内复用同一个 `android.graphics.Path` 对象，每次计算新轨迹前强制调用 `path.reset()`。
  - 预分配 `FloatArray` 存储采样点坐标，避免每次循环 `new float[]`。
- **无阻塞定时器**：使用协程的 `delay()` 挂起函数替代传统的定时器，确保等待期间 CPU 核心可进入休眠状态，极致省电。
- **采样点策略**：采样密度公式 `samplingPoints = max(10, scrollDuration / 16)`，匹配 60fps 刷新率。

## 4. 工业级拟人化滑动算法 (Bezier Curve & Custom Interpolator)

- **手势注入**：使用 `AccessibilityService.dispatchGesture()`，通过 `GestureDescription.StrokeDescription` 注入事件。
- **动态曲线生成**：算法需动态获取屏幕的宽度和高度，在屏幕中央安全区域（避开顶部状态栏和底部导航栏）生成纵向滑动路径。
- **贝塞尔曲线**：利用二阶贝塞尔曲线公式 $B(t) = (1-t)^2P_0 + 2t(1-t)P_1 + t^2P_2$，在起始点 $P_0$ 和终点 $P_2$ 之间引入一个带有微小随机水平偏移的控制点 $P_1$，使滑动轨迹产生符合人类手指习惯的微小弧度。
- **非对称速度曲线**：使用自定义插值器 `cubicBezier(0.25, 0.1, 0.25, 1.0)` 替代对称的 `AccelerateDecelerateInterpolator`，实现加速阶段短而急促、减速阶段长而平缓的真实手指运动特征。
- **生物拟人化噪声（Bio-Noise）**：
  - 单次滑动的总距离、持续时间以及间隔时间，必须在用户设定值的基础上动态加入 $\pm 5\% \sim \pm 10\%$ 的正态分布随机浮动。
  - 轨迹采样点之间引入微小的像素级随机抖动（Noise）。
- **错误处理**：在 `GestureResultCallback.onCancelled()` 中记录日志，连续失败 3 次后暂停滑动并通知用户。

## 5. 统一服务架构 (Single AccessibilityService)

- 将悬浮窗和自动滑动功能合并到单一 `PhantomScrollService extends AccessibilityService`。
- 在 `onServiceConnected()` 中通过 `WindowManager` 添加 Compose 悬浮窗。
- 在 `onDestroy()` 中移除悬浮窗并取消所有协程。
- 使用 `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)` 作为 Service 级作用域。
- 所有状态流（duration / interval / distance / isRunning）直接在同一个 Service 实例内共享，零通信开销。

## 6. 前台通知与服务保活

- 创建 `NotificationChannel`（Android 8.0+ 必需）。
- 在服务启动时调用 `startForeground()` 绑定低优先级常驻通知。
- 通知显示当前状态：`● 滑动中` / `○ 已暂停`。
- 通知 Action 按钮：快速暂停/恢复。

## 7. 主界面与权限引导 (MainActivity)

- 使用 Jetpack Compose 构建引导式权限检查页面。
- 权限检查流程：悬浮窗权限 (`Settings.canDrawOverlays()`) → 无障碍服务权限。
- 权限全部就绪后显示使用说明卡片。
- 提供"启动服务"入口。

## 8. 生命周期与锁屏管理

- 注册 `BroadcastReceiver` 监听 `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON`。
- 屏幕熄灭时自动暂停自动滑动协程（保存当前运行状态）。
- 屏幕亮起后自动恢复之前的运行状态。

# What I Need From You

请按照生产环境标准，分模块提供结构严密、带有详尽注释的 Kotlin 代码：

| # | 模块 | 文件/类名 | 说明 |
|---|------|-----------|------|
| 1 | 系统配置 | `AndroidManifest.xml` | 权限声明、Service 注册 |
| 2 | 系统配置 | `res/xml/accessibility_service_config.xml` | 无障碍服务配置 |
| 3 | 主入口 | `MainActivity` + `MainScreen` | 权限检查引导、使用说明 |
| 4 | 核心服务 | `PhantomScrollService` | 合并后的无障碍 + 悬浮窗 + 滑动控制服务 |
| 5 | 手势引擎 | `GestureEngine` | 贝塞尔曲线、Bio-Noise、对象池、采样策略 |
| 6 | 悬浮窗 UI | `FloatingPanel` | Compose UI、拖拽、吸附状态机、折叠手柄 |
| 7 | 状态管理 | `ScrollConfig` | 所有配置状态流的数据持有类 |
| 8 | 通知管理 | `NotificationHelper` | 通知渠道、前台通知构建 |
| 9 | 主题 | `Theme.kt` | 暗黑模式设计系统 |