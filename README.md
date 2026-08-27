# PhantomScroll

👻 **PhantomScroll** 是一款专为小说和漫画阅读设计的**高性能、零卡顿** Android 自动化辅助工具。通过无障碍服务（Accessibility Service）模拟高度逼真的拟人化跨应用**纵向滑动**，辅助用户解放双手，享受丝滑的自动阅读体验。

---

## 🌟 核心特性

### 1. 单笔连续滑动手势引擎 (Single Continuous-Stroke Engine)

- **速度曲线编码进路径点距**：Android 在单个 `StrokeDescription` 内部按线性时间映射路径点、不做弧长重参数化。引擎利用这一点，把"急加速 / 缓减速"的非对称速度分布直接编码为**单笔路径的非均匀点距**（[SpeedCurve.resampleByTimeProgress](app/src/main/java/com/phantom/scroll/gesture/SpeedCurve.kt)）——无 `continueStroke` 接缝（消灭接缝微停顿），也没有慢速拖尾。
- **尾速下限 (Speed Floor) 防误点**：`flooredEaseOut` 保证手指末段速度不低于均速的一定比例，按滑动频段取值：极速/快速 (<700ms) 0.93、中速 (700–1049ms) 0.88、慢速 (≥1050ms) 0.82。手指掠过广告/诱导按钮只需 12–29ms，远低于任何点击判定阈值。
- **三阶贝塞尔轨迹**：C 弧（~60%，双控制点位于几何中点）与拇指枢轴 S 弧（~40%，控制点在 s=1/3、s=2/3 处反向偏移）随机切换；弧长均匀采样 40 点（快速频段加密至 64 点，防 ROM 丢事件后退化为"点击"），每个内部采样点叠加**每笔随机幅度**的高斯抖动（σ∈[0.6, 1.6]px，±2σ 截断），端点不抖保证落点精确。
- **SessionMotion 手指习惯模型**：滑动起点锚定在一条"习惯带"内缓慢随机游走、偶尔整体迁移（7% 概率，模拟手指抬起换位）；暂停恢复后的前两笔以 1.22× / 1.10× 时长温和回暖。
- **起点随机化 + 横向漂移收敛**：起点在安全区剩余空间内分布（`headroomFraction` 由 SessionMotion 供给），终点 X 紧贴起点 X（真实手指横漂极小）；时长与实际距离**速度耦合**（±5% 抖动，快速频段只增不减），避免距离/时长双噪声叠加成速度毛刺。
- **Bio-Noise 生物噪声**：滑动距离 ±8%、滑动间隔 ±8%（2σ 截断），符合正态分布的拟人浮动。
- **兼容性降级阶梯**：首选单笔连续路径若在个别 ROM 上连续 2 次被取消，自动切换回旧版双段 `continueStroke` 方案（Toast 提示），保留贝塞尔抖动与起点随机化；连续 3 次滑动失败则自动暂停并提示用户。

### 2. 严格间隔节奏模型 (Strict Interval Pacing)

滑动周期 = 手势时长 + **用户设定的间隔**（仅叠加 ±8% 高斯噪声）。间隔严格锚定用户配置：

- 曾实验过"休息式停顿"（偶发将间隔拉长 1.5–2.4×）与"智能间隔"（等待前台 App 内容稳定）等拟人化特性，因会产生**用户可感知的多秒级偶发停顿**而移除——节奏可预期性优先于额外的"真实感"。
- 间隔 clamp 对齐 Slider 的 `500..10000`ms 区间，杜绝越界。

### 3. 注入可靠性防护 (Dispatch Reliability)

- **息屏门控**：每次派发前后检查 `PowerManager.isInteractive`——从通知栏在息屏状态下点"开始"（此时不会再有 SCREEN_OFF 广播到来）也不会向黑屏注入手势，亮屏后自动继续。
- **手势重叠守护 + 逃生通道**：超时未回调的手势保持 in-flight 标志，循环等待其迟到回调防止"双手指"同屏；若回调彻底丢失（个别 ROM），连续 3 次跳过后强制清除标志恢复派发，杜绝永久停摆。
- **用户手指让路**：API 31+ 通过 `TouchInteractionController` 观察真实触摸（注入手势带合成标志，不会误报），手指在屏期间暂停注入，抬手后 350ms 冷却再继续；宽限窗口内的手势取消归因于用户而非 ROM，不污染降级计数。
- **失败策略**：连续 3 次失败自动暂停并 Toast 提示（手动暂停/锁屏期间不计失败）。

### 4. 单进程架构与系统级保活 (Keep-Alive)

- **独立前台保活服务**：`KeepAliveService`（`specialUse` 类型前台服务）在无障碍服务连接时启动，持有常驻低优先级通知（含**暂停/恢复**与**停止服务**快捷按钮），将进程维持在前台优先级，抵御内存回收与 OEM 后台清理。
- **通知按钮的进程死亡补偿**：通知 Action 走 manifest 静态广播接收器，进程被杀后仍可拉起；服务重连期间收到的"开始"指令以 pending 标志暂存，重连后自动补执行。
- **1px 无障碍保活窗**：`TYPE_ACCESSIBILITY_OVERLAY` 的 1×1 像素窗口（参考 gkd / 李跳跳），让进程始终拥有"可见窗口"进一步降低 oom_adj；`FLAG_NOT_TOUCHABLE` 确保零触摸干扰。
- **锁屏语义（含进程死亡恢复）**：熄屏自动暂停、解锁自动恢复；恢复意图**持久化**存储——即使息屏期间进程被杀，解锁后依然恢复滚动；亮屏期间的手动暂停/自动暂停则会清除该意图，不会幽灵恢复。
- **安全广播**：所有运行时广播以 `RECEIVER_NOT_EXPORTED` 注册，封锁外部应用伪造指令。

### 5. 单一真相源数据层 (Single Source of Truth)

- **Reducer + Intent 架构**：所有可持久化状态变更收敛到 `SettingsRepository.apply(intent)` 单一入口，经纯函数 [SettingsReducer](app/src/main/java/com/phantom/scroll/data/SettingsReducer.kt) 产出原子 delta——Per-App 一致性规则有且只有一个家，可并发重放。
- **派生式 Per-App 开关**："按 App 分别记录"不再是一个全局持久化开关，而是**派生自当前前台 App 是否存在 profile**——每个 App 显示自己的记录状态，互不影响。
- **前台检测**：只信任 `TYPE_WINDOW_STATE_CHANGED`（内容变化事件不订阅——它对任意可见窗口都会洪泛触发，且已无消费者）；系统 denylist（SystemUI / Launcher / 全部输入法 / 自身包名）+ 300ms 防抖 + **尾沿补偿**（快速 A→B→A 突发时最终前台 App 不丢失；回到当前包名的事件会清除过期的防抖候选，防止把前台写错）。
- **持久化节流**：单一 `debounce(500ms)` collector 在初始磁盘加载完成后才启动（默认种子值绝不回写覆盖真实值）；服务销毁时 `flush()` 以 1.5s 超时有界落盘。V2 起从 SharedPreferences 迁移至 Preferences DataStore（`SharedPreferencesMigration` 自动搬运旧 key）。
- **距离口径统一**：`ScrollSettings.SAFE_ZONE_HEIGHT_RATIO (0.7)` 是唯一口径——屏幕适配、手势引擎安全区、悬浮窗 px 标签三方共用，**悬浮窗显示的滑动像素 = 实际物理滑动像素**。默认适配公式 `distanceRatio = 1500f / (screenHeight × 0.7)`（clamp 至 0.3..0.95），任意分辨率下默认物理滑动距离精准对齐 1500px。

### 6. 原生悬浮窗 (Native Floating Panel)

- **原生 View + Material Components**（阅读期无常驻 Compose 运行时，内存更低）；`StateFlow` → 命令式刷新，零重组开销。
- **状态机**：`Expanded`（展开面板）/ `Snapping`（250ms 吸附动画）/ `Collapsed`（20dp 边缘手柄气泡）。
- **智能边缘吸附**：拖拽结束按面板中心 X 吸附至最近边缘；竖屏下静止 Y 位置约束在避开四角曲面区的安全带内。
- **交互保护**：`ACTION_DOWN` 命中任何**已展开**的参数 Slider、开关、方向切换、忘记配置按钮等交互子视图时，禁止父视图拦截——微位移拖拽不会打断子控件操作。
- **全屏/刘海屏贴边**：`FLAG_LAYOUT_IN_SCREEN` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，状态机切换时标志不丢失。
- **纯逻辑抽离**：吸附/夹取几何（`OverlayGeometry`）与参数分档标签（`ParamSteps`）均为无 Android 依赖的 JVM 可测对象。

### 7. 暗黑权限引导页 (MainScreen)

- `#0F0F12` 背景 + `#1E1E24` 微光卡片 + `PhantomCyan`/`PhantomBlue` 双强调色。
- **5 项权限分三组**：必要（悬浮窗、无障碍）/ 建议（电池豁免、通知）/ 可选（自启动引导，无法程序检测、点击即视为已引导）；建议与可选分组支持 `AnimatedVisibility` 折叠动画。
- **0 资源 Canvas 矢量图标**：幽灵 Logo 与各卡片图标全部由 Compose `Canvas` + 贝塞尔路径纯代码手绘。
- **扫气动画进度环**：`animateFloatAsState` 驱动的渐变环形统计，中心 Monospace 展示 `已授予/总项`。
- **极简无按钮控制**：无底部全局授权大按钮，交互收敛到各卡片行的二态 Badge（`已开启 ✓` / `去授权`）。

---

## 🛠️ 技术栈与兼容性

* **开发语言**：Kotlin (Coroutines + Flow)
* **UI**：主界面 Jetpack Compose（权限引导页）；悬浮窗原生 View（WindowManager + Material Components）
* **系统服务**：AccessibilityService, WindowManager, BroadcastReceiver, ForegroundService (specialUse)
* **单元测试**：JUnit 4, kotlinx-coroutines-test
* **兼容规范**：
  * Compile SDK: `35` (Android 15)
  * Target SDK: `35` (Android 15)
  * Min SDK: `26` (Android 8.0)

---

## 📂 项目结构

```
gesture/                                # 【独立纯 JVM 算法模块】拟人化滑动引擎（零 Android 依赖）
└── src/
    ├── main/kotlin/com/phantom/scroll/gesture/
    │   ├── GestureEngine.kt         # 连续计划/双段计划生成 + 弧长采样 + 起手驻留 + easeScale 变奏
    │   ├── SpeedCurve.kt            # flooredEaseOut / 贝塞尔参数与弧长采样 / O(n) 时间域重采样
    │   ├── SessionMotion.kt         # 习惯起点带随机游走 + 暂停回暖
    │   ├── SafeZone.kt              # 安全区几何唯一口径（HEIGHT_RATIO + top/bottom/height）
    │   └── ScrollDirection.kt       # UP / DOWN
    └── test/kotlin/.../gesture/     # 65 个纯 JVM 测试（含弧长均匀性/驻留/变奏/确定性）

app/src/main/java/com/phantom/scroll/
├── PhantomScrollApp.kt          # Application: 通知渠道初始化
├── MainActivity.kt              # Compose 权限引导页（唯一 Compose 界面）
│
├── data/                        # 领域模型 + 单一真相源仓库 + DataStore 持久化
│   ├── ScrollSettings.kt        #   滚动配置（安全区口径引用 gesture.SafeZone）
│   ├── AppProfile.kt            #   按 App 的配置覆盖
│   ├── SettingsIntent.kt        #   全部状态变更的密封 Intent 形状
│   ├── SettingsReducer.kt       #   纯函数 reducer：per-app 一致性规则的唯一居所
│   ├── SettingsRepository.kt    #   全局唯一真相源（StateFlow + debounce 节流写回）
│   ├── ProfileStore.kt          #   持久化接口
│   ├── DataStoreProfileStore.kt #   DataStore 实现 + SharedPreferencesMigration
│   └── MigrationMapper.kt       #   旧 SharedPreferences key → 新结构的纯映射
│
├── notification/NotificationHelper.kt  # 通知渠道 + 常驻通知构建（含大图标缓存）
│
├── service/
│   ├── PhantomScrollService.kt      # 单一 AccessibilityService：事件路由 + 生命周期编排
│   ├── ScrollOrchestrator.kt        # 滑动循环：息屏门控 / 重叠守护 / 降级阶梯 / 失败策略
│   ├── GestureDescriptionFactory.kt # :gesture 计划 → GestureDescription（池化 Path，零分配）
│   ├── FloatingWindowController.kt  # WindowManager 悬浮窗编排（权限轮询 + flags 维护）
│   ├── ServiceEventReceiver.kt      # 屏幕状态 / 通知 action 广播 + isRunning 镜像
│   ├── FailurePolicy.kt             # 纯逻辑：失败计数 / 自动暂停（可单测）
│   ├── ScreenStateCoordinator.kt    # 纯逻辑：锁屏暂停 / 亮屏恢复 + 恢复意图持久化
│   ├── PerAppDetector.kt            # 纯逻辑：denylist/去重/防抖/尾沿补偿（可单测）
│   ├── PackageChangeExtractor.kt    # 纯逻辑：前台切换事件资格判定（可单测）
│   ├── NotificationActionReceiver.kt# manifest 静态广播：通知按钮 + 进程死亡 pending 补偿
│   ├── KeepAliveService.kt          # specialUse 前台保活服务（常驻通知）
│   └── KeepAliveWindow.kt           # 1px TYPE_ACCESSIBILITY_OVERLAY 保活窗
│
├── ui/
│   ├── overlay/
│   │   ├── FloatingOverlayView.kt   # 原生悬浮窗视图（拖拽/吸附/交互保护）
│   │   ├── ParamCellView.kt         # 参数格：点击展开内联 Slider
│   │   ├── ParamSteps.kt            # 纯 JVM：参数分档标签（含安全区 px 换算）
│   │   ├── BubbleView.kt            # 折叠态 20dp 手柄气泡
│   │   ├── PanelState.kt            #   Expanded / Snapping / Collapsed
│   │   └── OverlayGeometry.kt       #   纯 JVM 几何：吸附目标 / 边缘 / clamp
│   ├── screen/MainScreen.kt         # Compose 权限页（5 项三组）
│   └── theme/ (Color.kt / Theme.kt / Type.kt)
│
└── util/PhantomLog.kt           # 编译期门控日志（release 擦除 d/w；JVM 测试回退 println）

baselineprofile/                    # Baseline Profile 生成器（com.android.test）
└── src/main/java/.../baselineprofile/
    ├── BaselineProfileGenerator.kt # 生成 MainActivity 冷启动 profile
    └── StartupBenchmark.kt         # Macrobenchmark：Profile 前/后冷启动对比

app/src/test/java/com/phantom/scroll/   # app 层纯逻辑 JVM 单元测试（87 个，无 Robolectric）
├── data/       (SettingsRepository + 并发回归 / SettingsReducer / ScrollSettings / MigrationMapper / ProfileKeyParsing)
├── service/    (FailurePolicy / ScreenStateCoordinator 含持久化意图 / PerAppDetector 含尾沿清除 / PackageChangeExtractor)
└── ui/overlay/ (OverlayGeometry / ParamSteps)
```

---

## 🚀 核心算法与设计细节

### 1. 数学计算与 Android 彻底解耦（独立 `:gesture` 纯 JVM 模块）

滑动算法整体抽为独立 Gradle 模块 `:gesture`（Kotlin JVM，零 Android 依赖）：引擎在纯点空间产出计划（`ContinuousPlan` / `GesturePlan` 折线点列表），`:app` 侧的 `GestureDescriptionFactory` 用**池化 Path**（`reset()` + 重建折线，循环内零分配）将其转换为 `StrokeDescription`。`calculateGesturePoints`（三阶贝塞尔控制点、安全区、起点随机化、速度耦合时长）与 `SpeedCurve`（采样/重采样/速度曲线）全部可在 plain JVM 上直接单测——包括 `generateContinuousPlan` 的完整流水线（弧长均匀性、起手驻留、easeScale 变奏、确定性都有测试钉住）。

### 2. 为什么是"单笔连续路径"而不是"双段拼接"

双段 `continueStroke` 在接缝处存在速度不连续（微停顿），且末段慢拖尾会被广告 SDK 读作"缓慢按压"。单笔路径把速度分布编码进点距后，这两个误检根因同时消除。双段方案仅作为个别 ROM 不兼容时的自动降级保留。详见 [ScrollOrchestrator](app/src/main/java/com/phantom/scroll/service/ScrollOrchestrator.kt) 类文档。

### 3. 无障碍事件订阅面最小化

服务配置只订阅 `typeWindowStateChanged`（前台切换判定）；`typeWindowContentChanged` 不订阅——注入滑动期间它会以每笔成百上千的量级洪泛，跨 binder 传输后被全部丢弃，纯属耗电。`canRetrieveWindowContent="false"`，绝不读取屏幕内容。

### 4. 距离语义与多分辨率适配

`distanceRatio` 的语义是**占安全区高度的比例**（安全区 = 屏幕 15%~85%，见 `ScrollSettings.SAFE_ZONE_HEIGHT_RATIO`）。默认值按 `1500px / (屏高 × 0.7)` 适配，悬浮窗标签按同一口径换算 px——规格、引擎、UI 三方数字一致。

### 5. 全局日志门控 (PhantomLog)

`BuildConfig.DEBUG` 编译期门控：debug 输出全量日志；release 中 `d`/`w` 直接被编译器优化擦除（0 GC、0 字符串拼接），仅保留 error。JVM 单测环境自动探测并回退到 `println`，避免 `Method not mocked` 崩溃。

### 6. Android 15 (SDK 35) Edge-to-Edge 适配

移除 Theme 中已废弃的 `statusBarColor` 属性；`MainScreen` 先填充全屏背景再叠加 `statusBarsPadding()`/`navigationBarsPadding()`，系统栏被背景色完美填充，无白色条带。

---

## 🧪 测试与构建验证

```bash
./gradlew test                  # 152 个纯逻辑 JVM 单元测试（:gesture 65 + :app 87，无 Robolectric）
./gradlew assembleDebug         # 调试包
./gradlew assembleRelease       # R8 混淆发布包
```

覆盖要点：

- **`:gesture` 模块测试**：采样落点与抖动约束、时长 clamp、方向镜像、起点随机化、横向漂移收敛、高 ratio 不截断、flooredEaseOut 端点/尾速下限/easeScale 不变量、**弧长采样步长均匀性（且优于参数均匀采样）**、**起手驻留区间与元数据一致性**、**跨种子速度剖面变奏**、固定种子确定性、习惯带锚定与回暖系数。
- **`:app` 数据层**：activeSettings 回落/切换、profile 增删、`apply(intent)` API、历史并发竞态回归、per-app 一致性规则、屏幕适配（按安全区口径）。
- **`:app` 服务与 UI 层**：denylist / 去重 / 防抖 / 尾沿补偿 / 回原包清除候选、恢复意图跨进程死亡持久化、失败策略、前台切换事件资格、吸附几何、参数标签。

---

## 📲 使用说明与权限引导

引导页将 **5 项配置**分为三组，按需授予：

1. **悬浮窗权限 (SYSTEM_ALERT_WINDOW)** 【必要】：在阅读 App 上层显示控制悬浮窗。
2. **无障碍服务 (BIND_ACCESSIBILITY_SERVICE)** 【必要】：注入模拟滑动。声明 `canRetrieveWindowContent="false"`，绝不读取任何屏幕隐私。
3. **忽略电池优化** 【建议】：防止系统后台强杀无障碍进程（走系统设置页引导，符合 Google Play 规范）。
4. **通知权限 (POST_NOTIFICATIONS)** 【建议】：常驻保活通知 + 快捷暂停/停止按钮。
5. **自启动引导** 【可选】：国产 ROM 的自启动/关联启动设置，无法程序检测，点击跳转对应设置页后标记为已引导。

授予必要权限后：打开阅读 App → 展开悬浮面板 → 点击"开始滑动"。息屏自动暂停、解锁自动恢复；通知栏可随时暂停/恢复或停止服务。

---

## 🔧 依赖升级路线（待办）

`gradle/libs.versions.toml` 中的依赖停留在 2024 年末版本。升级时务必注意版本矩阵耦合（AGP↔Gradle wrapper↔Kotlin↔Compose Compiler），并跑 `./gradlew testDebugUnitTest assembleDebug lintDebug` 全绿后再合并。建议目标：

| 依赖 | 当前 | 建议目标 | 注意 |
|------|------|----------|------|
| AGP | 8.7.3 | 8.7.x 末版（已最新）→ 视需要升 8.9+ | 跨大版本需同步升级 Gradle wrapper |
| Kotlin | 2.0.21 | 2.1.x / 2.2.x | Compose Compiler 插件版本必须与 Kotlin 一致 |
| Compose BOM | 2024.12.01 | 最新 BOM | 跟随 Kotlin 版本 |
| kotlinx-coroutines | 1.9.0 | 1.10.2 | 1.10.x 目标 Kotlin 2.2.x，降级使用前验证二进制兼容 |
| Gradle wrapper | 8.9 | 8.13+ | 与 AGP 版本要求对齐 |
| lifecycle / core-ktx / datastore | 2.8.7 / 1.13.1 / 1.1.1 | 各自最新稳定 | 纯 AndroidX，升级风险低 |
