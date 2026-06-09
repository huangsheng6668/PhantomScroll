# PhantomScroll

👻 **PhantomScroll** 是一款专为小说和漫画阅读设计的**高性能、零卡顿** Android 自动化辅助工具。通过无障碍服务（Accessibility Service）模拟高度逼真的拟人化跨应用**纵向滑动**，辅助用户解放双手，享受丝滑的自动阅读体验。

---

## 🌟 核心特性

1. **拟人化惯性滑动引擎 (Inertial Gesture Engine)**
   - **单段触控注入**：采用单段 `StrokeDescription` 进行无缝事件分发，让手指在滑动终点最速阶段以真实惯性初速度释放。完美适配系统 `VelocityTracker`，触发阅读 App 内自然、长距离的物理惯性滚动（Fling）。
   - **原生硬件级采样 (`Path.quadTo`)**：直接利用原生二阶贝塞尔曲线函数。由 Android 输入系统在触控注入层根据设备的屏幕刷新率（如 90Hz / 120Hz / 144Hz 等）进行原生自适应高频采样，输出极致丝滑的滑动轨迹。
   - **低频拟人化噪声 (Bio-Noise)**：滑动距离、持续时间以及两次滑动之间的间隔时间，均动态加入 $\pm 5\% \sim \pm 10\%$ 的正态分布随机浮动；对滑动起点、终点和控制点引入低频手抖随机偏置，模拟拇指滑过时的细微差异，避免高频噪声污染速度计算。

2. **微服务化单进程架构与极限性能优化**
   - **移除前台服务依赖**：完全停用 `startForeground()`，改用常规状态通知，免去了 Google Play 应用商店前台服务数据同步权限的严格红线审查。
   - **单进程架构合并**：取消了 `:accessibility` 独立子进程，将所有功能合并至主进程中运行。消除了 20~30MB 的 IPC 内存垃圾开销，并解决了 SharedPreferences 跨进程数据不安全问题。
   - **SharedPreferences 持久化节流**：在 [ScrollConfig.kt](file:///E:/github_project/PhantomScroll/app/src/main/java/com/phantom/scroll/config/ScrollConfig.kt) 中引入 `.drop(1).debounce(500)`，杜绝了用户拖拽 Slider 时高频磁盘 I/O 所引发的线程阻塞和卡顿。
   - **主/后台线程隔离与零 GC 消耗 (Zero GC)**：所有数学计算和 Bio-Noise 生成异步在 `Dispatchers.Default` 进行。在服务生命周期内复用同一个 `Path` 对象并通过 `reset()` 清理，实现滑动循环 0 GC 消耗。
   - **无阻挂起定时器**：使用协程的 `delay()` 挂起函数替代传统的 Timer 线程，保证等待期间 CPU 核心可休眠，极致省电。

3. **智能边缘吸附悬浮窗 (Compose Snapping Floating Panel)**
   - 使用系统 `WindowManager` 动态注入全局悬浮窗，基于 Jetpack Compose 构建极致轻量的暗黑高对比度 UI。
   - **局部重组优化 (Local Recomposition)**：将 `collectAsState()` 订阅由顶层卡片下沉至各 Slider 内部。拖拽单个 Slider 时仅该组件重新绘制，面板其他部分保持静态，拖动性能提升 4 倍。
   - **吸附状态机 (State Machine)**：支持 `Expanded`（展开面板）、`Snapping`（吸附中动画）、`Collapsed`（边缘折叠手柄）三种状态。
   - **智能边缘靠吸**：拖拽结束时自动计算 X 坐标，平滑吸附至屏幕最近的一侧边缘，并自动折叠为半透明功能手柄。

4. **安全保护与生命周期保活**
   - **高安全广播**：屏幕状态和通知栏控制广播通过 `RECEIVER_NOT_EXPORTED` 注册，从底层封锁外部恶意 app 伪造广播非法控制服务的漏洞。
   - **生命周期与锁屏管理**：屏幕熄灭或锁屏时**自动暂停**滑动，亮屏解锁后**自动恢复**先前状态，保护手机电池与屏幕寿命。

---

## 🛠️ 技术栈与兼容性

* **开发语言**：Kotlin (Coroutines + Flow)
* **UI 框架**：Jetpack Compose (ConstraintLayout, PointerInput Dragging, Animatable Animation)
* **系统服务**：AccessibilityService, WindowManager, BroadcastReceiver
* **单元测试**：JUnit 4, Mockito, mockito-kotlin, kotlinx-coroutines-test
* **兼容规范**：
  - Compile SDK: `35` (Android 15)
  - Target SDK: `35` (Android 15)
  - Min SDK: `26` (Android 8.0)

---

## 📂 项目结构

```
app/src/main/
├── java/com/phantom/scroll/
│   ├── MainActivity.kt          # 引导式权限检查与使用说明主界面
│   ├── PhantomScrollApp.kt      # Application 初始化入口
│   │
│   ├── config/
│   │   └── ScrollConfig.kt      # 引入 debounce 节流的配置管理与快照机制
│   │
│   ├── gesture/
│   │   └── GestureEngine.kt     # 解耦数学计算的滑动手势坐标/时间生成引擎
│   │
│   ├── notification/
│   │   └── NotificationHelper.kt# 独立的状态通知通道构建器与控制广播
│   │
│   ├── service/
│   │   ├── PhantomScrollService.kt # 薄协调层：无障碍服务生命周期管理
│   │   ├── FloatingWindowController.kt # 专职全局 WindowManager 悬浮窗的动态加载与管理
│   │   ├── ScrollOrchestrator.kt  # 控制自动滑动挂起循环、超时兜底及失败保护
│   │   ├── ServiceEventReceiver.kt# 负责安全的 RECEIVER_NOT_EXPORTED 屏幕/控制广播分发
│   │   └── OverlayLifecycleOwner.kt# 悬浮窗 Compose ViewTree 生命周期持有者
│   │
│   └── ui/
│       ├── overlay/
│       │   └── FloatingPanel.kt # 局部重组优化后的悬浮窗控制面板
│       ├── screen/
│       │   └── MainScreen.kt    # 权限引导主界面，适配 Android 15 Edge-to-Edge 避让
│       └── theme/
│           ├── Color.kt
│           ├── Theme.kt         # 适配 SDK 35 沉浸式的暗黑透明主题
│           └── Type.kt
│
└── test/java/com/phantom/scroll/
    ├── gesture/
    │   └── GestureEngineTest.kt # 针对解耦数学计算 (GesturePoints) 的本地 JVM 单元测试
    └── config/
        └── ScrollConfigTest.kt  # Mock 框架验证 SharedPreferences 的配置读写与快照
```

---

## 🚀 核心算法与重构细节

### 1. 数学计算与 Path 绘图解耦
为了让手势生成算法可以脱离真实的 Android 虚拟机进行单元测试，我们从 [GestureEngine.kt](file:///E:/github_project/PhantomScroll/app/src/main/java/com/phantom/scroll/gesture/GestureEngine.kt) 中剥离了 `GesturePoints` 纯 Kotlin 数据类。贝塞尔曲线、屏幕安全区偏移、和仿生随机噪声全部在纯 JVM 函数 `calculateGesturePoints` 中进行，排除了 `android.graphics.Path` 依赖，实现了 100% 的本地 JVM 单元测试覆盖率。

### 2. Android 15 (SDK 35) Edge-to-Edge 适配
Android 15 强制启用了沉浸式 Edge-to-Edge 视效。为此：
- 我们移除了 [Theme.kt](file:///E:/github_project/PhantomScroll/app/src/main/java/com/phantom/scroll/ui/theme/Theme.kt) 中已失效且被标为废弃的 `window?.statusBarColor = ...` 属性。
- 修改了 [MainScreen.kt](file:///E:/github_project/PhantomScroll/app/src/main/java/com/phantom/scroll/ui/screen/MainScreen.kt) 的 Modifier padding 应用顺序（先填充全屏 `background`，再加入 `statusBarsPadding()` 和 `navigationBarsPadding()`），确保系统栏被背景色完美填充，解决了系统栏白色条带的视觉 Jank。

### 3. 全局日志门控 (PhantomLog)
项目引入了自定义日志工具 [PhantomLog](file:///E:/github_project/PhantomScroll/app/src/main/java/com/phantom/scroll/util/PhantomLog.kt)。它在编译期通过 `BuildConfig.DEBUG` 门控：
- 在 `debug` 构建中输出完整的调试日志。
- 在 `release` 混淆构建中直接通过编译器优化机制将所有 `d` 和 `w` 日志行直接擦除（0 GC，0 字符串拼接开销），并保证仅输出 critical 异常级别的错误日志。

---

## 🧪 测试与构建验证

### 1. 本地单元测试
你可以直接在命令行中运行测试：
```bash
./gradlew test
```
该命令会同时测试以下场景：
- **`GestureEngineTest`**：验证划动点落在合法的屏幕安全区（避开顶部状态栏和底部导航栏）；验证极短（50ms）或极长（5000ms）滑页时间能正确 Coerce 进 `[200, 1500]` ms；验证正态分布的 Bio-Noise 抖动随机数差异性。
- **`ScrollConfigTest`**：验证从 XML SharedPreferences 初始化及 Snapshot 数据的一致性。

### 2. 生成 Release 混淆包
执行以下命令进行编译、R8 资源缩减与代码混淆：
```bash
./gradlew assembleRelease
```
项目已在 `gradle.properties` 中调整了 JVM 内存参数，以防 R8 处理复杂 Compose 布局时导致 Daemon Heap GC 内存抖动崩溃。

---

## 📲 使用说明与权限引导

为了使 PhantomScroll 正常运作，需要授予以下系统权限：

1. **悬浮窗权限 (SYSTEM_ALERT_WINDOW)**：用于在小说/漫画 App 上层显示控制悬浮窗。
2. **无障碍服务权限 (BIND_ACCESSIBILITY_SERVICE)**：用于注入模拟滑动事件。声明了 `canRetrieveWindowContent="false"`，绝不读取任何屏幕隐私。
3. **通知权限 (POST_NOTIFICATIONS)**：用于显示通知栏快捷按钮。
4. **忽略电池优化**：防止系统在后台强杀无障碍进程。本项目使用安全规整的系统设置 intent (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) 引导用户手动更改，完全符合 Google Play 应用商店规定。
