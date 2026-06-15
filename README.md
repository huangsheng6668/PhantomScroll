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
   - **DataStore 持久化节流**：在 [SettingsRepository.kt](app/src/main/java/com/phantom/scroll/data/SettingsRepository.kt) 中以 `MutableStateFlow` + `.drop(1).debounce(500)` 写回 Preferences DataStore，杜绝用户拖拽 Slider 时高频磁盘 I/O 所引发的线程阻塞和卡顿（V2 起从 SharedPreferences 迁移至 DataStore）。
   - **主/后台线程隔离与零 GC 消耗 (Zero GC)**：所有数学计算和 Bio-Noise 生成异步在 `Dispatchers.Default` 进行。在服务生命周期内复用同一个 `Path` 对象并通过 `reset()` 清理，实现滑动循环 0 GC 消耗。
   - **无阻挂起定时器**：使用协程的 `delay()` 挂起函数替代传统的 Timer 线程，保证等待期间 CPU 核心可休眠，极致省电。

3. **智能边缘吸附悬浮窗 (Native Snapping Floating Panel)**
   - 使用系统 `WindowManager` 动态注入全局悬浮窗，**主界面用 Jetpack Compose；悬浮窗用原生 View + Material Components**（V2 Phase 2 起，阅读期不再常驻 Compose 运行时，内存更低）。
   - **命令式刷新**：`FloatingOverlayView` 收集 `SettingsRepository` 的 `StateFlow` 并命令式刷新 View，无重组开销。
   - **吸附状态机 (State Machine)**：支持 `Expanded`（展开面板）、`Snapping`（吸附中动画）、`Collapsed`（边缘折叠手柄）三种状态。
   - **智能边缘靠吸**：拖拽结束时自动计算 X 坐标，平滑吸附至屏幕最近的一侧边缘，并自动折叠为半透明功能手柄。

4. **安全保护与生命周期保活**
   - **高安全广播**：屏幕状态和通知栏控制广播通过 `RECEIVER_NOT_EXPORTED` 注册，从底层封锁外部恶意 app 伪造广播非法控制服务的漏洞。
   - **生命周期与锁屏管理**：屏幕熄灭或锁屏时**自动暂停**滑动，亮屏解锁后**自动恢复**先前状态，保护手机电池与屏幕寿命。

---

## 🛠️ 技术栈与兼容性

* **开发语言**：Kotlin (Coroutines + Flow)
* **UI**：主界面 Jetpack Compose（权限引导页）；悬浮窗原生 View（WindowManager + Material Components，V2 起移除 Compose 运行时常驻）
* **系统服务**：AccessibilityService, WindowManager, BroadcastReceiver
* **单元测试**：JUnit 4, Mockito, mockito-kotlin, kotlinx-coroutines-test
* **兼容规范**：
  - Compile SDK: `35` (Android 15)
  - Target SDK: `35` (Android 15)
  - Min SDK: `26` (Android 8.0)

---

## 📂 项目结构

```
app/src/main/java/com/phantom/scroll/
├── PhantomScrollApp.kt          # Application: 通知渠道初始化
├── MainActivity.kt              # Compose 权限引导页（唯一 Compose 界面）
│
├── data/                        # 【V2】领域模型 + 单一真相源仓库 + DataStore 持久化
│   ├── ScrollSettings.kt        #   滚动配置（duration/interval/distanceRatio/direction）
│   ├── ScrollDirection.kt       #   UP / DOWN
│   ├── AppProfile.kt            #   按 App 的配置覆盖
│   ├── Preset.kt                #   场景预设（小说/漫画）+ PresetSelection 派生态（自定义）
│   ├── PresetRegistry.kt        #   纯逻辑：global → 命中预设 / 自定义
│   ├── ScrollStats.kt           #   运行统计（翻页次数 + 累计时长）
│   ├── SettingsRepository.kt    #   全局唯一真相源（StateFlow + DataStore 节流写回）
│   ├── ProfileStore.kt          #   持久化接口
│   └── DataStoreProfileStore.kt #   DataStore 实现 + SharedPreferencesMigration
│
├── gesture/GestureEngine.kt     # 贝塞尔 + Bio-Noise + 方向（UP/DOWN）+ 零 GC Path 复用
│
├── notification/NotificationHelper.kt  # 状态通知通道 + 通知 action 广播
│
├── service/
│   ├── PhantomScrollService.kt          # 单一 AccessibilityService，含 per-app 检测
│   ├── FloatingWindowController.kt      # WindowManager 编排原生悬浮窗
│   ├── ScrollOrchestrator.kt            # 滑动循环 + 统计累计 + 失败策略
│   ├── ServiceEventReceiver.kt          # 屏幕状态 / 通知 action 广播
│   ├── FailurePolicy.kt                 # 纯逻辑：失败计数 / 自动暂停（可单测）
│   ├── ScreenStateCoordinator.kt        # 纯逻辑：锁屏暂停 / 亮屏恢复（可单测）
│   └── PerAppDetector.kt                # 纯逻辑：per-app 事件过滤（denylist/去重/防抖）
│
├── ui/
│   ├── overlay/
│   │   ├── FloatingOverlayView.kt       # 原生悬浮窗（Phase 2 替代 Compose FloatingPanel）
│   │   ├── PanelState.kt                #   Expanded / Snapping / Collapsed
│   │   └── OverlayGeometry.kt           #   纯几何：吸附目标 / 边缘 / clamp
│   ├── screen/MainScreen.kt             # Compose 权限页
│   └── theme/ (Color.kt / Theme.kt / Type.kt)
│
└── util/PhantomLog.kt                   # 编译期门控日志（release 擦除 d/w）

baselineprofile/                         # 【V2 Phase 4】Baseline Profile 生成器（com.android.test）
└── src/main/java/.../baselineprofile/
    ├── BaselineProfileGenerator.kt      # 生成 MainActivity 冷启动 profile
    └── StartupBenchmark.kt              # Macrobenchmark：Profile 前/后冷启动对比

app/src/test/java/com/phantom/scroll/    # 纯逻辑 JVM 单元测试（58 个，无 Robolectric）
├── data/       (SettingsRepository / ScrollSettings / MigrationMapper / ProfileKeyParsing)
├── gesture/    (GestureEngine 含方向)
├── service/    (FailurePolicy / ScreenStateCoordinator / PerAppDetector)
└── ui/overlay/ (OverlayGeometry)
```

---

## 🔧 V2 优化（四阶段）

V2 对项目做了第二轮架构与性能优化，分四个阶段推进（每阶段独立 commit、可单独 revert）：

1. **Phase 1 — 可维护性地基**：引入 `data/` 领域模型与 `SettingsRepository` 单一真相源，持久化解耦为 `ProfileStore` 接口并迁移到 Preferences DataStore（`SharedPreferencesMigration` 自动搬旧 key）；核心循环与状态机纯逻辑抽离（`FailurePolicy` / `ScreenStateCoordinator`）并加单测。**对外行为零变化。**
2. **Phase 2 — 悬浮窗原生 View 重写**：把悬浮窗从 Compose (`ComposeView`) 重写为原生 View（`FloatingOverlayView` + Material Components），移除阅读时常驻的 Compose 运行时（内存下降）；删除 `FloatingPanel` / `OverlayLifecycleOwner` / `ScrollConfig` 桥接。
3. **Phase 3 — 产品化功能**：场景预设（小说/漫画/自定义）、运行统计（翻页次数 + 累计分钟，可重置）、滚动方向切换（↑/↓）、按 App 记忆配置（开关可控、自动创建 profile、denylist + 防抖、可忘记）。
4. **Phase 4 — 性能收尾**：Baseline Profile（`:baselineprofile` 模块，加速 `MainActivity` 冷启动）+ Compose 编译器稳定性报告 + 热路径零分配复核 + 测量留痕。实测数字见 [spec §4.4.1](docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md)。

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
该命令会测试 **58 个纯逻辑 JVM 单元测试**（无 Robolectric），覆盖：
- **`GestureEngineTest`**：滑动点落在合法屏幕安全区；极短/极长时间正确 Coerce 进 `[150,1500]`ms；Bio-Noise 抖动差异性；**UP/DOWN 方向翻转**（endY 与 startY 相对关系）。
- **`SettingsRepositoryTest`**：activeSettings 回落/切换、profile 增删、stats 累加/重置、`applyPreset`/`updateActive`/`forgetActiveProfile`/`selectedPreset` 派生。
- **`PresetRegistryTest`**：global 命中小说/漫画预设或回落自定义。
- **`PerAppDetectorTest`**：denylist / 去重 / 自身包名过滤 / 300ms 防抖。
- **`FailurePolicyTest` / `ScreenStateCoordinatorTest` / `OverlayGeometryTest` / `MigrationMapperTest` / `ProfileKeyParsingTest` / `ScrollSettingsTest`**。

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
