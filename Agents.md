# Role

你是一个资深的 Android 顶尖架构师，精通 Kotlin 异步高并发编程、Jetpack Compose 动画与自定义手势拦截、Android 无障碍服务（Accessibility Service）底层的事件注入机制。你对 Android 系统的内存管理、线程调度以及防止 UI 掉帧（Jank）有极深的造诣。

# Task

我需要开发一个高性能、零卡顿的 Android 自动化辅助工具 **"PhantomScroll"**，核心功能是通过无障碍服务模拟逼真的拟人化跨应用**纵向滑动**，辅助用户阅读小说或漫画。请为我提供完整的项目架构设计与关键核心代码实现。

# Architecture Decisions

| 决策项 | 结论 |
|--------|------|
| 服务架构 | 合并为单一 `PhantomScrollService extends AccessibilityService` |
| 项目名称 | PhantomScroll |
| 锁屏行为 | 屏幕熄灭/锁屏后自动**暂停**滑动，亮屏后恢复（恢复意图持久化：息屏期间进程被杀，解锁后仍恢复） |
| 滑动方向 | 支持**纵向滑动**（包含向上 UP 与向下 DOWN 滚动切换） |
| compileSdk / targetSdk | API 35 (Android 15) |
| minSdk | API 26 (Android 8.0) |
| 引导页控制按钮 | 彻底**移除**底部全局一键授权/刷新按钮，简化交互至各权限卡片独立操作 |

# Technical Stack & Refined Requirements

## 1. 悬浮窗设计与边缘吸附状态机 (WindowManager & Native Overlay)

- **悬浮窗构建**：使用系统 `WindowManager` 动态添加全局悬浮窗，`LayoutParams` 必须正确配置 `TYPE_APPLICATION_OVERLAY`、`FLAG_NOT_FOCUSABLE`、`FLAG_LAYOUT_IN_SCREEN` 并设置刘海屏适配（如 `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`），且 `LayoutParams.gravity = Gravity.TOP or Gravity.LEFT`。同时需在状态机切换（Expanded/Collapsed）更新标志时，保留 `FLAG_LAYOUT_IN_SCREEN` 以确保全屏模式下边缘贴合和正常隐藏。
- **UI 框架分工**：
  - 悬浮窗控制面板全面重写为**原生 View (XML 布局 + Material Components)**，彻底移除阅读期间常驻的 Compose 运行时以节省内存。
  - 主界面权限引导页 (`MainScreen`) 采用 **Jetpack Compose 现代化暗黑渐变发光风格**重构，界面与实际应用主色调保持 100% 吻合（背景 `#0F0F12`，卡片 `#1E1E24`，强调色 `#00E5FF`/`#2979FF`）。
- **引导页界面设计规范**：
  - **自绘矢量图标**：完全摒弃 Emoji 图标和外置图片资源，各卡片图标统一使用 Compose `Canvas` 进行矢量路径（`Path` / 二阶贝塞尔曲线 `quadraticTo`）纯代码手绘，实现 0 资源依赖和极致的视网膜屏显示精度。
  - **渐变进度环 (Summary Card)**：头部采用 Canvas 自定义绘制统计卡片，使用 `animateFloatAsState` 控制环形渐变进度平滑扫气动画，正中心使用 Monospace 字体展示 `已授予项/总项` 比例。
  - **折叠展示组**：将 5 项权限科学分入必要、建议（后台保活）及可选三组卡片。“建议”与“可选”栏目外包 `AnimatedVisibility` 容器，支持带高度缓动拉伸动画的折叠与收起，点击 Toggle 条时箭头 `▴`/`▾` 会同步翻转。（注：原第 6 项"使用情况访问权限"已移除——前台检测实际依赖无障碍窗口事件，该权限无功能消费方。）
  - **极简无按钮控制**：完全移除底部多余的全局“开始授权/刷新”大主按钮，将所有的交互完全剥离给各个卡片行的微光状态 Badge（`已开启 ✓` 与 `去授权`），简化用户的操作心理负担。
- **拖拽与状态机**：利用自定义 View 的 `onTouchEvent` 与 `onInterceptTouchEvent` 拦截与监听用户拖拽。悬浮窗内部维护一个状态机（State）：`Expanded`（展开面板）、`Snapping`（吸附中动画）、`Collapsed`（边缘折叠手柄）。
- **边缘吸附与折叠动画**：
  - 当拖拽结束时，计算当前 $X$ 坐标。若超过屏幕宽度的一半，利用 `ValueAnimator` 动画将悬浮窗平滑推至右边缘，反之推至左边缘（250ms 吸附）。
  - 吸附完成后，自动切换为 `Collapsed` 状态：控制面板隐藏，仅在边缘渲染一个高透明度、宽 32dp 的"手柄（Handle）"。
  - 点击或向内滑动该手柄，平滑展开控制面板。
- **触控拦截守护**：在 parent custom view 中监听 `ACTION_DOWN`，判断如果触摸点落在交互式子视图（如 Slider、开关、方向切换、忘记 App 配置按钮等）的 global bounds 范围内，则将 `disallowIntercept` 设为 true，防止微小位移导致 ViewGroup 拦截事件并取消子视图的点击行为。

## 2. 状态管理与单一真相源 (SettingsRepository & DataStore)

- **全局唯一真相源**：在 Service 内部引入 `data/` 层，通过 `SettingsRepository` 持有所有核心 `MutableStateFlow` 并进行集中状态分发。
- **领域模型**：
  - `ScrollSettings`：保存当前的 duration (速度)、interval (间隔)、distanceRatio (距离)、direction (方向)，并持有 `SAFE_ZONE_HEIGHT_RATIO` 距离换算唯一口径。
  - `AppProfile`：存储前台特定 App 的定制化设置配置。
  - （注：原 `ScrollStats` 运行统计模型与"点击重置"功能已随产品简化移除。）
- **双向同步与节流落盘**：
  - 原生 View 中的 `Slider`、开关等交互控件通过观察 Repository 的 `StateFlow` 进行命令式刷新（零重组开销）。
  - 用户拖动 Slider 改写内存状态立即生效，后台通过协程 Flow `debounce(500ms)` 对 Preferences DataStore 写入节流，杜绝磁盘 I/O 阻塞。
- **全局设定值动态复位与屏幕适配**：
  - 切换到没有 Profile 配置的 App 时（"按App分别记录"关闭），全局设定值会立即恢复并重置到预设默认值：速度快速（持续时间 `500ms`）、间隔 `2s`（`2000ms`）、方向向下 (`DOWN`)。
  - 默认滑动距离在获取或变化屏幕物理高度时会动态计算：`distanceRatio = (1500f / (screenHeight × 0.7f)).coerceIn(0.3f, 0.95f)`（0.7 为安全区高度比例，见 `ScrollSettings.SAFE_ZONE_HEIGHT_RATIO`；引擎实际在屏幕 15%~85% 安全区内滑动），确保在任意分辨率的视网膜屏设备上首次加载与复位时，物理滑动距离精准对齐为 `1500px`，克服了固定比例导致的高分屏 Jank。

## 3. 极限性能优化与零 GC 消耗设计 (Coroutines & Object Pooling)

连续的高频滑动极易导致内存抖动并触发系统 GC 造成掉帧。必须严格遵循以下优化方案：
- **线程完全隔离**：
  - **UI 线程 (Dispatchers.Main.immediate)**：仅负责悬浮窗的拖拽手势响应、手柄折叠吸附动画。
  - **计算线程 (Dispatchers.Default)**：所有贝塞尔曲线轨迹点、随机噪声、时间加权算法必须在 `Dispatchers.Default` 中异步计算。
- **对象复用 (Object Pooling)**：
  - 绝对禁止在滑动循环中重复 `new Path()`。滑动算法已抽为独立纯 JVM 模块 `:gesture`（零 Android 依赖，计划以折线点列表表达）；`android.graphics.Path` 仅存在于 `:app` 侧的 `GestureDescriptionFactory` 适配器中，复用同一批 Path 对象，每次计算新轨迹前强制调用 `path.reset()`。
  - 采样点计算解耦为纯 JVM Kotlin 数据类返回，便于在本地 JVM 线程运行高覆盖率单测。
- **无阻塞定时器**：使用协程的 `delay()` 挂起函数替代传统的定时器，确保等待期间 CPU 核心可进入休眠状态，极致省电。
- **单元测试本地 JVM 兼容与防挂起设计**：
  - 封装自定义日志 `PhantomLog`，在类加载时通过安全调用反射探针自动识别 JVM 单测环境，将 `Log.d` 等平台日志方法自动回退代理至标准控制台 `println`，避免发生 `Method not mocked` 崩溃。
  - 持久化层已收敛为"初始加载完成后才启动的单一 `debounce(500ms)` collector + 销毁时 `flush()`"，不存在周期性后台协程，`advanceUntilIdle()` 无挂起风险；单测经 `FakeProfileStore` 与注入的测试调度器驱动。

## 4. 工业级拟人化滑动算法 (Bezier Curve & Custom Interpolator)

- **手势注入**：使用 `AccessibilityService.dispatchGesture()`，通过 `GestureDescription.StrokeDescription` 注入事件。
- **动态曲线生成**：算法需动态获取屏幕的宽度和高度，在屏幕中央安全区域（避开顶部状态栏和底部导航栏，默认 0.15 ~ 0.85）生成纵向滑动路径。
- **贝塞尔曲线**：利用二阶贝塞尔曲线公式 $B(t) = (1-t)^2P_0 + 2t(1-t)P_1 + t^2P_2$，在起始点 $P_0$ 和终点 $P_2$ 之间引入一个带有微小随机水平偏移的控制点 $P_1$，使滑动轨迹产生符合人类手指习惯的微小弧度。
- **非对称速度曲线**：实现加速阶段短而急促、减速阶段长而平缓的真实手指运动特征。
- **生物拟人化噪声（Bio-Noise）**：
  - 单次滑动的总距离、持续时间以及间隔时间，必须在用户设定值的基础上动态加入 $\pm 5\% \sim \pm 10\%$ 的正态分布随机浮动。
  - 轨迹采样点之间引入微小的像素级随机抖动（Noise）。

## 5. 产品化特性支持

- **按 App 记忆配置 (Per-App)**：前台包名变化检测（支持系统 denylist 过滤与 300ms 快速切换防抖 + 尾沿补偿：防抖窗口内的候选不丢失，且"回到当前包名"的事件会清除过期候选），当开启该功能并调整 Slider 时，自动创建并持久化当前 App 的专属配置 profile；切换回普通应用时自动还原全局默认，点击悬浮面板上的"忘记配置"按钮即可删除该 App 配置。
- **节奏模型**：滑动周期 = 手势时长 + 用户设定间隔（仅叠加 ±8% 正态噪声）。不做任何超出噪声带的间隔拉长/门控——节奏可预期性优先。（注：原"运行统计"功能已随产品简化移除。）

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
| 9 | 样式系统 | `res/values/colors.xml` | 悬浮窗暗黑玻璃拟态配色（`overlay_*`，与主界面 `phantom_*` 同源：`#1E1E24` 卡片 / `#00E5FF` 青色强调 / 琥珀 `#D98324` 暂停警示）+ Compose 权限页暗黑色值（`dark_surface`/`phantom_*`）及 Drawable 矢量图标样式包 |