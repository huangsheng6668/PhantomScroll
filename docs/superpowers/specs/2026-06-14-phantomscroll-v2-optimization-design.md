# PhantomScroll V2 全面优化设计文档

> 日期：2026-06-14
> 状态：已批准，待实施
> 方案：单 spec 分四阶段推进，阶段间设验证闸门（方案 A）
> 前置文档：[2026-06-09-phantomscroll-optimization-design.md](./2026-06-09-phantomscroll-optimization-design.md)（已全部落地）

---

## 概述

在 V1（架构拆分 / SDK 35 / 零 GC / 日志门控 / 单测基础）已全部落地的基础上，对 PhantomScroll 进行第二轮优化，覆盖**性能优化、代码可维护性、产品化设计**三个维度。

**项目现状（V1 完成后）**：Kotlin + Jetpack Compose + Coroutines，约 1837 行主代码 + 2 个单测文件，compileSdk/targetSdk 35，minSdk 26。悬浮窗用 ComposeView 承载；UI 直接读写 `ScrollConfig` 内部 `MutableStateFlow`；核心循环 `ScrollOrchestrator` 与事件接收器零测试覆盖；魔法数字散落。

**目标状态（V2 完成后）**：

- **可维护性**：领域模型 + `SettingsRepository` 单一真相源；持久化解耦为可替换接口；UI 与状态解耦；核心循环与状态机有纯逻辑单测。
- **性能**：悬浮窗改为原生 View，移除阅读时常驻的 Compose 运行时；Baseline Profile 加速冷启动；Compose 稳定性配置减少 `MainScreen` 重组。
- **产品化**：场景预设、运行统计、滚动方向切换、按 App 记忆配置四项功能上线。

---

## 目标与非目标

### 目标

1. 引入 `data/` 层：领域模型（`ScrollSettings` / `AppProfile` / `Preset` / `ScrollStats`）+ `SettingsRepository` 单一真相源 + `ProfileStore` 持久化接口（DataStore 实现，从旧 SharedPreferences 迁移）。
2. 悬浮窗 UI 层从 Compose 重写为原生 View（XML 布局 + Material Components），功能与现状逐项对齐。
3. 上线四项产品化功能：场景预设、运行统计、滚动方向/手势扩展、按 App 记忆配置。
4. 补齐核心循环与状态机的纯逻辑单元测试。
5. 接入 Baseline Profile 与 Compose 稳定性配置；测量并记录性能数据。

### 非目标（明确不做）

- 用户自存预设（本期仅内置"小说/漫画/自定义"）。
- 云同步 / 多设备同步。
- 横向滑动（本应用面向纵向阅读，保持不变）。
- 引入 Room/SQLite（DataStore 足以覆盖当前 schema）。
- 把 `MainActivity`/`MainScreen` 改为原生（Activity 界面用 Compose 是恰当的，仅悬浮窗改原生）。
- 完整的 per-app profile 管理界面（本期仅"忘记当前 App 配置"）。
- "可调滑动起止区"（本期只做方向切换，安全区保持 0.15~0.85 硬编码）。
- GitHub Actions CI（本期不做，专注主线）。

---

## 整体架构

从"UI 直接改 `ScrollConfig` 内部 `MutableStateFlow`"演进为"单一真相源 + 领域模型"分层：

```
UI 层
 ├ 悬浮窗 = 原生 View（Phase 2 重写）       ┐
 └ MainActivity 权限页 = Compose（不变）     ┘ 都只"观察"状态，不直接持有/写内部状态
        ↑ 观察 StateFlow
SettingsRepository  ← 全局唯一真相源
  ├ activeSettings  = 全局 ∪ 按 App 配置 ∪ 当前预设，三者解析后的最终值
  ├ profiles : Map<pkg, AppProfile>
  ├ presets / stats
  └ currentPackage ← 来自 AccessibilityEvent（debounce，仅读 packageName）
        ↓
ProfileStore（接口）→ DataStore 实现（从旧 SharedPreferences 自动迁移）
        ↓
GestureEngine（方向感知）← 读 activeSettings
ScrollOrchestrator → 注入手势 + 累计 stats
```

### 新包结构

```
java/com/phantom/scroll/
├── data/                          # 【新增】领域模型 + 仓库 + 持久化
│   ├── ScrollSettings.kt
│   ├── ScrollDirection.kt
│   ├── AppProfile.kt
│   ├── Preset.kt
│   ├── ScrollStats.kt
│   ├── SettingsRepository.kt      # 单一真相源
│   ├── ProfileStore.kt            # 持久化接口
│   └── DataStoreProfileStore.kt   # DataStore 实现 + SharedPrefs 迁移
├── gesture/GestureEngine.kt       # 增加 direction 参数
├── notification/NotificationHelper.kt
├── service/
│   ├── PhantomScrollService.kt    # 实现 onAccessibilityEvent（per-app 检测）
│   ├── FloatingWindowController.kt# 改为原生 View 编排
│   ├── ScrollOrchestrator.kt      # 读 repo + 累计 stats，失败逻辑委托 FailurePolicy
│   ├── ServiceEventReceiver.kt    # 状态机委托 ScreenStateCoordinator
│   ├── FailurePolicy.kt           # 【新增】纯逻辑：失败计数/自动暂停
│   └── ScreenStateCoordinator.kt  # 【新增】纯逻辑：锁屏暂停/恢复
├── ui/
│   ├── overlay/
│   │   ├── FloatingOverlayView.kt # 【新增】原生自定义视图
│   │   └── PanelState.kt          # 枚举独立成文件
│   ├── screen/MainScreen.kt       # Compose，不变
│   └── theme/...
├── util/PhantomLog.kt
├── MainActivity.kt
└── PhantomScrollApp.kt
res/
├── layout/overlay_*.xml           # 【新增】原生悬浮窗布局
├── drawable/overlay_*.xml         # 【新增】渐变/圆角 shape
└── values/colors.xml              # 【新增】从 Color.kt 同步色值

【删除】config/ScrollConfig.kt（Phase 1 降级为桥接，Phase 2 删除）
【删除】ui/overlay/FloatingPanel.kt（Compose，Phase 2 删除）
【删除】service/OverlayLifecycleOwner.kt（Phase 2 删除，原生 View 不需要）
```

---

## 跨阶段关键决策

| # | 决策点 | 选定方案 | 备选 / 理由 |
|---|--------|----------|------------|
| 1 | 持久化 | **Preferences DataStore**，`SharedPreferencesMigration` 自动搬旧 key | 协程/Flow 原生、非阻塞；schema 将变大（profile/预设/统计）。备选：保留 SharedPrefs 重排 key，更省事但更丑 |
| 2 | 状态来源 | **`SettingsRepository`** 持有全部 `StateFlow`，UI 仅观察 | 替代"UI 直接写 `config.xxx.value`"。备选：Android ViewModel，但悬浮窗是 WindowManager 非 Activity，ViewModel 反而别扭 |
| 3 | 按 App 检测 | 用已有的 `typeWindowStateChanged` 事件读 `event.packageName`；**默认关闭、用户开开关才处理事件** | 关时 `onAccessibilityEvent` 立即 return（零开销，保持现状）；不加权限、不读屏幕内容、隐私故事不变 |
| 4 | 按 App 存储 | `profile.<pkg>.*` 前缀 key 存于 DataStore，无匹配 pkg 回落全局 | 比 Room 轻；用户阅读 App 数量小（<20），无需 SQLite |
| 5 | 悬浮窗原生 UI 技术 | **XML 布局 + Material Components 原生控件**（`Slider`/`MaterialCardView`）+ 自定义 drawable 复刻主题 | 比纯代码堆 View 可读得多。备选：纯程序式 View，更轻但难维护 |
| 6 | 方向/手势扩展 | `GestureEngine.calculateGesturePoints` 加 `direction: UP/DOWN`，纯数学改在 JVM 函数内 | 已是 JVM 纯函数，直接加方向单测。"可调起止区"本期不做 |
| 7 | 运行统计 | `swipeCount` + `elapsedMs` 累计持久化，提供重置；面板/通知可见 | 成品感关键，开销极小（每次成功手势 +1，间隔累加） |
| 8 | OverlayLifecycleOwner | **Phase 2 删除** | 它是给 Compose 的 ViewTree 生命周期用的；原生 View 不需要，重写后控制器更简单 |

---

## Phase 1：可维护性地基（行为零变化）

**目标**：引入领域模型与 `SettingsRepository` 单一真相源，持久化解耦为接口并迁移到 DataStore，核心循环与状态机的纯逻辑抽离并加单测。**对外行为与今天完全一致。**

### 1.1 领域模型（`data/` 包，全部新增）

```kotlin
enum class ScrollDirection {
    /** 手指上滑，内容向上滚动（翻到下一页） */
    UP,
    /** 手指下滑，内容向下滚动（翻到上一页） */
    DOWN
}

data class ScrollSettings(
    val duration: Long,        // 200..1500 ms
    val interval: Long,        // 500..10000 ms
    val distanceRatio: Float,  // 0.30..0.95
    val direction: ScrollDirection = ScrollDirection.UP
)

data class AppProfile(
    val packageName: String,
    val settings: ScrollSettings
)

data class ScrollStats(
    val swipeCount: Long,
    val elapsedMs: Long
)
```

`Preset` 同样建模，内置常量在 Phase 3 填充，Phase 1 先建空壳。

### 1.2 `SettingsRepository`（唯一真相源）

核心暴露：

```kotlin
class SettingsRepository(
    private val store: ProfileStore,
    private val scope: CoroutineScope
) {
    val global: StateFlow<ScrollSettings>            // 可编辑的全局默认
    val profiles: StateFlow<Map<String, AppProfile>> // per-app 配置表
    val perAppEnabled: StateFlow<Boolean>
    val activeSettings: StateFlow<ScrollSettings>    // 派生：解析后的最终生效值
    val stats: StateFlow<ScrollStats>
    val isRunning: StateFlow<Boolean>                // 仅内存，不持久化

    // 写入（suspend，落盘走 debounce）
    suspend fun updateGlobal(s: ScrollSettings)
    suspend fun upsertProfile(pkg: String, s: ScrollSettings)
    suspend fun deleteProfile(pkg: String)
    fun setCurrentPackage(pkg: String?)
    fun setPerAppEnabled(enabled: Boolean)
    fun incrementStats(swipeDelta: Long = 1, elapsedDeltaMs: Long)
    fun resetStats()
}

> [!NOTE]
> `setCurrentPackage`、`incrementStats` 等非 suspend 方法仅在主内存中同步修改 `StateFlow`，其持久化写入会由 repository 在协程作用域内通过 flow 的 debounce(500ms) 机制节流并异步进行。这确保了此类方法从任何线程（或 Binder 线程）调用时都是线程安全且绝对无阻塞的。
```

**`activeSettings` 解析中枢**（按 App 记忆的核心）：

```kotlin
activeSettings = combine(perAppEnabled, currentPackage, profiles, global) {
    enabled, pkg, profiles, global ->
    if (enabled && pkg != null) profiles[pkg]?.settings ?: global else global
}.stateIn(scope, SharingStarted.Eagerly, global.value)
```

**写入节流**：沿用 V1 `.debounce(500)` 思路 —— UI 改内存 `StateFlow` 立即生效（零延迟），后台 collector 节流落盘，避免拖 Slider 高频 I/O。

**`isRunning` 不持久化**：保持现状，避免开机自动滚动。

### 1.3 持久化接口与 DataStore 实现

```kotlin
interface ProfileStore {
    suspend fun loadGlobal(): ScrollSettings
    suspend fun loadProfiles(): Map<String, AppProfile>
    suspend fun loadPerAppEnabled(): Boolean
    suspend fun loadStats(): ScrollStats
    suspend fun saveGlobal(s: ScrollSettings)
    suspend fun saveProfile(p: AppProfile)
    suspend fun deleteProfile(pkg: String)
    suspend fun savePerAppEnabled(enabled: Boolean)
    suspend fun saveStats(stats: ScrollStats)
}

> [!NOTE]
> 鉴于 DataStore 本质是异步 I/O 的，`ProfileStore` 的数据加载接口全部声明为 `suspend` 函数。`SettingsRepository` 将在其主协程作用域内通过结构化并发异步并发读取以完成初始化，从而彻底避免阻塞主线程。
```

`DataStoreProfileStore` 使用 Preferences DataStore + `SharedPreferencesMigration`：

- 新 key：`global.duration` / `global.interval` / `global.distanceRatio` / `global.direction`
- per-app：`profile.<pkg>.duration` / `.interval` / `.distanceRatio` / `.direction`
- `perapp.enabled`、`stats.swipe`、`stats.elapsed`
- 迁移映射：`scroll_duration → global.duration`、`scroll_interval → global.interval`、`scroll_distance_ratio → global.distanceRatio`
- **迁移期内旧 SharedPreferences 不删除**，保证 Phase 1 可回退读回旧值

### 1.4 过渡桥接（避免改注定要删的 Compose 面板）

- `PhantomScrollService` 持有并构造 `SettingsRepository`。
- `ScrollOrchestrator`、`ServiceEventReceiver` 在 Phase 1 **直接切到 repository**（它们跨阶段存活，值得改干净）。
- `ScrollConfig` 降级为 `@Deprecated` 薄适配器，委托给 repository，**仅为让即将被删的 Compose `FloatingPanel` 继续编译**。Phase 2 连同 `FloatingPanel` 一起删除。

### 1.5 纯逻辑抽离与单测

| 抽离 | 来源 | 测试 |
|------|------|------|
| `FailurePolicy` | `ScrollOrchestrator` 的失败计数 / 阈值 / 自动暂停判定 | `FailurePolicyTest`：成功清零、达阈值触发暂停、手动暂停不计失败 |
| `ScreenStateCoordinator` | `ServiceEventReceiver` 的 `wasRunningBeforeScreenOff` 状态机 | `ScreenStateCoordinatorTest`：熄屏前运行→暂停并记忆、亮屏→恢复、未运行不恢复 |
| `SettingsRepository`（用内存 `FakeProfileStore`） | 新建 | `SettingsRepositoryTest`：activeSettings 回落/切换、profile 增删、stats 累加/重置 |

优先"接口 + 假实现"，尽量不引入 Robolectric（除非确需 Android 框架）。

### 1.6 Phase 1 改动文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `data/ScrollSettings.kt`、`data/ScrollDirection.kt`、`data/AppProfile.kt`、`data/Preset.kt`、`data/ScrollStats.kt`、`data/SettingsRepository.kt`、`data/ProfileStore.kt`、`data/DataStoreProfileStore.kt` |
| 新建 | `service/FailurePolicy.kt`、`service/ScreenStateCoordinator.kt` |
| 新建测试 | `SettingsRepositoryTest`、`FailurePolicyTest`、`ScreenStateCoordinatorTest` |
| 修改 | `service/PhantomScrollService.kt`（持有 repo） |
| 修改 | `service/ScrollOrchestrator.kt`（读 repo，失败逻辑委托 `FailurePolicy`） |
| 修改 | `service/ServiceEventReceiver.kt`（状态机委托 `ScreenStateCoordinator`） |
| 修改 | `config/ScrollConfig.kt`（降级为桥接，`@Deprecated`） |
| 修改/迁移测试 | `app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt`（由于原有接口改变，更新该单测以仅验证适配器逻辑，其余测试迁移到 `SettingsRepositoryTest`） |
| 修改 | `app/build.gradle.kts`、`gradle/libs.versions.toml`（加 DataStore 依赖） |

### 1.7 Phase 1 验收

- 全部新增单测通过；`./gradlew test` 绿。
- 手工验证：启动 / 拖 Slider 实时改参 / 播放暂停 / 锁屏暂停/亮屏恢复 / 杀进程重启后配置保留 —— **行为与今天完全一致**。
- **DataStore 迁移及覆盖验证**：
  - **新安装**：验证首选项均读取正确默认值（duration=500ms, interval=2000ms, distanceRatio=0.75）。
  - **版本升级**：在已存有 V1 数据的设备上安装 V2，验证 DataStore 中数据与旧 SharedPreferences 完美一致，不丢失用户参数。
  - **二次启动**：验证升级迁移后第二次启动，不会再次重复迁移覆盖新写入的数据（迁移完成标记判定生效）。
- 旧 `ScrollConfigTest` 已正确升级/重写，且不破坏现有测试套件。

---

## Phase 2：悬浮窗原生 View 重写（功能对齐）

**目标**：用原生 View 重建悬浮窗，功能与今天逐项对齐，**对齐验证通过才进 Phase 3**。删除 `OverlayLifecycleOwner`。Compose 仅保留给 `MainActivity`/`MainScreen`。

### 2.1 视图结构

- 新增自定义视图 `ui/overlay/FloatingOverlayView.kt`：一个 `FrameLayout`，内部按 `PanelState` 切换"手柄 / 完整面板"可见性。**只负责渲染 + 发事件**（Slider 变化、按钮点击、拖拽位移回调），不含业务逻辑。
  - **Flow 收集生命周期管理**：在 `FloatingOverlayView` 中使用其专属的 `CoroutineScope`。在 `onAttachedToWindow()` 时启动收集 `SettingsRepository` 的 StateFlow 并命令式刷新 UI，在 `onDetachedFromWindow()` 时取消该 Scope，保证生命周期安全，防止内存泄漏和 NPE。
- `PanelState` 枚举独立成文件 `ui/overlay/PanelState.kt`。
- `FloatingWindowController` 保留 WindowManager 编排，把 `ComposeView` 换成 `LayoutInflater.inflate(R.layout.overlay_panel_expanded, ...)`；不再设 ViewTreeLifecycleOwner。

### 2.2 交互映射（Compose → 原生）

| 现在（Compose） | Phase 2（原生） |
|------|------|
| `detectDragGestures` + `Animatable.animateTo(tween 250ms)` | `View.OnTouchListener`（ACTION_MOVE 算 delta）+ `ValueAnimator.ofInt(...)` 吸附 250ms |
| `collectAsState` 驱动重组 | repository 的 `StateFlow` 在 service scope 上 `collect` → 命令式刷新 View |
| Compose `Slider` | `com.google.android.material.slider.Slider`（配置 `stepSize = 0f` 以支持连续滑动，且禁用 tooltip label/popup 气泡以匹配原先极简的交互视觉） |
| `Brush.horizontalGradient`（手柄/边框） | `GradientDrawable` shape drawable |
| `collectAsState` 的 `screenWidth/Height` | `service.resources.displayMetrics` + `onConfigurationChanged` 仍负责更新 |

### 2.3 主题保真

- 把 `ui/theme/Color.kt` 的色值同步到 `res/values/colors.xml`（`PhantomCyan` / `PhantomBlue` / `PhantomPurple` / `DarkSurfaceTranslucent` / `SuccessGreen` / `ErrorRed` / `WarningOrange` / `TextSecondary` / `TextTertiary` 等）。
- 渐变用 `res/drawable/` shape 复刻：青蓝→紫的卡片边框、青蓝手柄渐变、暗色半透圆角卡片背景（16dp 圆角）。

### 2.4 依赖

新增 `com.google.android.material:material`（appcompat 已在）。

### 2.5 删除

- `ui/overlay/FloatingPanel.kt`（Compose）
- `service/OverlayLifecycleOwner.kt`
- `config/ScrollConfig.kt`（Phase 1 桥接，面板删除后无消费者）
- `app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt`（删除原桥接与适配器单测，测试已全面转移至 `SettingsRepositoryTest`）

### 2.6 Phase 2 改动文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `ui/overlay/FloatingOverlayView.kt`、`ui/overlay/PanelState.kt` |
| 新建 | `res/layout/overlay_panel_expanded.xml`、`res/layout/overlay_handle_collapsed.xml` |
| 新建 | `res/drawable/overlay_card_bg.xml`、`overlay_card_border.xml`、`overlay_handle_gradient_left.xml`、`overlay_handle_gradient_right.xml` |
| 新建 | `res/values/colors.xml` |
| 修改 | `service/FloatingWindowController.kt`（原生 View inflate + 拖拽/吸附） |
| 修改 | `app/build.gradle.kts`、`gradle/libs.versions.toml`（加 Material Components 依赖） |
| 修改 | `app/proguard-rules.pro`（审查并更新原生 View 混淆规则，确保 Material Slider 等安全） |
| 删除 | `ui/overlay/FloatingPanel.kt`、`service/OverlayLifecycleOwner.kt`、`config/ScrollConfig.kt`、`app/src/test/java/com/phantom/scroll/config/ScrollConfigTest.kt` |

### 2.7 Phase 2 对齐验收清单（进 Phase 3 前必须全过）

- [ ] 边缘出现半透手柄，点击展开完整面板。
- [ ] 3 个 Slider 实时改 scroll 参数，滑动行为随之变化。
- [ ] 播放/暂停按钮切换滑动并联动通知。
- [ ] 拖拽面板 → 松手吸附最近边缘 → 折叠为手柄。
- [ ] 点击面板外部（`ACTION_OUTSIDE`）→ 折叠。
- [ ] 熄屏暂停 / 亮屏恢复（状态正确，不误恢复）。
- [ ] 旋屏后手柄/面板位置正确重定位。
- [ ] 视觉与旧 Compose 面板一致或可接受接近（渐变/圆角/配色）。
- [ ] `dumpsys meminfo` 确认阅读期间不再常驻 Compose 运行时（内存下降）。
- [ ] Slider 拖动时数值平滑变化无明显滞后与跳变（连续值无步进）。
- [ ] 面板展开与边缘折叠吸附的过渡动画时长（250ms）及减速效果与旧版体验一致。

---

## Phase 3：产品化功能

**目标**：上线场景预设、运行统计、滚动方向切换、按 App 记忆配置，全部建在 Phase 1 仓库 + Phase 2 原生悬浮窗上。

### 3.1 场景预设

- 内置预设（在 `data/Preset.kt` 填充常量）：
  - `小说`：duration 700ms / interval 4000ms / distanceRatio 0.55 / UP
  - `漫画`：duration 500ms / interval 3000ms / distanceRatio 0.85 / UP
  - `自定义`：= 当前 global 值（非固定预设）
- 悬浮窗顶部一行 Chip：`小说｜漫画｜自定义`。
  - 点 `小说`/`漫画` → `repo.updateGlobal(preset.settings)`，Slider 跳变。
  - 手动拖任意 Slider → 选择态自动切回 `自定义`。
- `selectedPreset` 由纯逻辑派生：若 global 命中某内置预设则显示其名，否则"自定义"。可单测。

### 3.2 运行统计

- `ScrollOrchestrator` 每次成功手势后：`repo.incrementStats(swipeDelta=1, elapsedDeltaMs=noiseInterval)`（间隔累加 ≈ 运行时长）。
- 面板显示一行：`已翻 1,234 次 · 约 42 分钟`；点击该行 → 重置（带 Toast 确认）。
- 累计持久化（DataStore），跨服务重启保留。
- **时间格式化与换算规则**：时长展示统一折算为分钟，换算公式为 `Math.round(elapsedMs / 60000.0)`，通过四舍五入得出最终显示数值。

### 3.3 滚动方向 / 手势扩展

- `GestureEngine.calculateGesturePoints` 增加 `direction: ScrollDirection` 参数：
  - `UP`（现行）：startY 近底，endY 在上，内容上滚。
  - `DOWN`：镜像 Y 逻辑（startY 近顶，endY 在下，控制点上下翻转），内容下滚。
  - 仍为纯 JVM 函数，新增方向单测（断言 endY 与 startY 的相对关系随方向翻转）。
- 面板加 ↑/↓ 切换按钮 → 写 `global.direction`。
- "可调滑动起止区" **本期不做**，安全区保持 0.15~0.85 硬编码。

### 3.4 按 App 记忆配置

**事件源与过滤防抖**（`PhantomScrollService.onAccessibilityEvent`）：

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!repository.perAppEnabled.value) return          // 关闭时零开销
    val pkg = event?.packageName?.toString() ?: return
    if (pkg == currentPackage) return                    // 仅处理包名变化
    if (pkg in SYSTEM_PACKAGE_DENYLIST || pkg == "com.phantom.scroll") return // 过滤系统/自身包名
    
    // 增加 300ms 快速切换防抖机制，降低高频包名变更对性能和存储的开销
    if (shouldDebounceEvent(pkg)) return
    repository.setCurrentPackage(pkg)
}
```

denylist：`com.android.systemui`、当前输入法、各个系统的 Launcher（启动器）包名以及应用自身包名 `com.phantom.scroll`。

**解析**：Phase 1 的 `activeSettings` 已完成 —— 当前 pkg 有 profile 用 profile，否则回落 global。

**"记忆"语义与动态创建配置**：
- **首次调整自动创建**：per-app 开启时，当前处于未自定义过的 App 前台，配置仍使用 `global`（activeSettings 派生逻辑回落）。若用户在该前台 App 中**拖动任一 Slider 进行了数值修改或切换了方向**，系统将**自动且动态地为该包名创建对应的 AppProfile**（即通过调用 `repo.updateActive(settings)` 写入 `profiles[currentPackage]`，而不影响全局 `global`）。
- **回落与重置**：切到无 profile 的 App 自动回落 global。新增"忘记当前 App 配置"动作（`repo.deleteProfile(currentPackage)`），删除后 activeSettings 再次自动回落至 global。

**UI**：面板加"按 App 记忆"开关；开启且前台 App 已识别时显示小标签 `📖 当前：<App 名>`，此时 Slider 编辑该 App 的 profile。

**隐私**：只读事件已携带的包名，不读窗口内容，`canRetrieveWindowContent="false"` 不变。

### 3.5 Phase 3 测试

- 扩 `GestureEngineTest`：方向翻转断言（UP 时 endY < startY，DOWN 时 endY > startY）。
- 扩 `SettingsRepositoryTest`：`applyPreset` 写 global、`updateActive` 写入目标（profile vs global）随 per-app 状态切换、activeSettings 解析。
- 新增 `PerAppDetectorTest`：把"事件 → 当前包"的过滤（去重/denylist）抽成纯函数 `PerAppDetector` 再测。

### 3.6 Phase 3 改动文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `gesture/GestureEngine.kt`（加 direction 参数） |
| 修改 | `data/SettingsRepository.kt`（applyPreset / updateActive / selectedPreset 派生 / setCurrentPackage / setPerAppEnabled） |
| 修改 | `data/Preset.kt`（填充内置预设常量） |
| 修改 | `service/PhantomScrollService.kt`（onAccessibilityEvent per-app 检测） |
| 新建 | `service/PerAppDetector.kt`（纯逻辑） |
| 修改 | `ui/overlay/FloatingOverlayView.kt` + 布局（预设 Chip / 统计行 / 方向按钮 / per-app 开关与标签 / 重置动作） |
| 新建测试 | 扩 `GestureEngineTest`、扩 `SettingsRepositoryTest`、`PerAppDetectorTest` |

---

## Phase 4：性能收尾 + 工程化（纯增量）

### 4.1 Baseline Profile

- 新增 `:baselineprofile` 宏基准模块（`androidx.benchmark` + `baselineprofile` 插件），跑"启动 MainActivity + 操作悬浮窗"生成 profile。
- 挂到 release 构建（`baselineProfile` 依赖），profile 随 APK 出厂 → 冷启动 / Compose 首帧更快。
- 悬浮窗已原生，主要利好 `MainScreen` 与 App 初始化。

### 4.2 Compose 稳定性配置

- 开启 Compose 编译器 stability / metrics 报告（Kotlin 2.0 Compose 插件 `composeCompiler { reportsDestination = ... }`）。
- 修 `MainScreen` 中不稳定参数（`@Immutable` / `@Stable` 或拆分），减少重组。
- 范围仅 `MainScreen`（唯一剩余 Compose 界面），快赢。

### 4.3 热路径零分配复核

- `onAccessibilityEvent`：per-app 关闭时早退，零分配。
- 统计自增用基本类型计数器，避免装箱。
- 确认滑动手势路径仍零 GC（`reusablePath.reset()` 复用不变）。

### 4.4 测量留痕

- 宏基准：冷启动时间、帧时间 / jank（`MainScreen`）。
- 内存：`dumpsys meminfo` 测阅读时悬浮窗常驻内存（对比 Phase 2 前后，确认 Compose 运行时已消失）。
- 前后数字写入本 spec 与 README。

#### 4.4.1 实测数据（Phase 4 闸门）

> 测量设备：<待真机测量填入：机型 / API / 是否 emulator>
> 测量日期：<待真机测量填入：YYYY-MM-DD>
>
> **状态：本计划实施环境无 Android SDK / adb / 模拟器，profile 生成与宏基准测量均需真机执行。下列为占位，待真机测量后回填。**

| 指标 | 无 Profile (CompilationMode.None) | 有 Profile (CompilationMode.Partial) | 变化 |
|------|-----------------------------------|--------------------------------------|------|
| 冷启动 timeToInitialDisplayMs（中位） | <TBD> ms | <TBD> ms | <TBD −X%> |
| 冷启动 timeToFullDisplayMs（中位） | <TBD> ms | <TBD> ms | <TBD −X%> |

| 阅读期常驻内存 (dumpsys meminfo TOTAL) | Phase 2 前 | Phase 2 后（当前） | 变化 |
|----------------------------------------|-----------|--------------------|------|
| TOTAL PSS | <TBD> MB | <TBD> MB | <TBD −X%> |
| Compose 运行时常驻 | 存在 | **已移除**（Phase 2 原生悬浮窗） | — |

测量命令：
- profile 生成：`./gradlew :baselineprofile:generateReleaseBaselineProfile`
- 宏基准对比：`./gradlew :baselineprofile:connectedReleaseBenchmark`（产物 `app/build/outputs/connected_android_test_additional_output/.../*.json`，含 `startupNoProfile` 与 `startupWithProfile` 两组 `StartupTimingMetric`）
- 内存：服务运行 ~30s 后 `adb shell dumpsys meminfo com.phantom.scroll`，重点看 TOTAL PSS 与是否还有 `androidx.compose.runtime.*` / `androidx.compose.ui.*` 常驻大块。

### 4.5 工程化（本期不做）

GitHub Actions CI、versionCode 策略等本期不做，专注主线。

### 4.6 Phase 4 改动文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `:baselineprofile` 模块（macrobenchmark + baselineprofile 插件配置） |
| 修改 | `app/build.gradle.kts`（接入 `baselineProfile`、`composeCompiler` 报告配置） |
| 修改 | `settings.gradle.kts`（include 新模块） |
| 修改 | `README.md`（补充 V2 架构与性能数据） |

---

## 跨阶段策略

### 测试策略（累计）

- **纯逻辑 JVM 测试**（不引 Robolectric）：`GestureEngine`（含方向）、`FailurePolicy`、`ScreenStateCoordinator`、`SettingsRepository`（用 `FakeProfileStore`）、`PerAppDetector`、`selectedPreset` 派生。
- **手工对齐清单**：Phase 2 / Phase 3 各设闸门清单。
- **宏基准**：Phase 4 测性能。
- 优先"接口 + 假实现"；仅在确需 Android 框架时才考虑 Robolectric。

### 数据迁移

- 一次性 SharedPreferences → DataStore（`SharedPreferencesMigration`），Phase 1 执行。
- 映射：`scroll_duration→global.duration`、`scroll_interval→global.interval`、`scroll_distance_ratio→global.distanceRatio`。
- 新字段（direction / presets / profiles / stats）给默认值。
- **迁移期内旧 prefs 不删**，保证 Phase 1 可回退读回旧值。

### 回退策略

- 每阶段独立 commit / PR，可单独 revert。
- **Phase 1 回退**：旧 prefs 完好（迁移对旧 store 只读）。
- **Phase 2 回退**：回到 Phase 1 的 Compose 面板（桥接保其可用）。
- **Phase 3 回退**：功能可 feature-flag（per-app 默认关；预设/统计不显示即等效回退）。
- **Phase 4 回退**：纯增量，移除 profile / 报告配置即可。

---

## 改动范围汇总

| 阶段 | 新建 | 修改 | 删除 | 风险 | 行为变化 |
|------|------|------|------|------|----------|
| 1 | ~11（data + service 纯逻辑 + 测试） | 5 | 0 | 低 | 无（行为对齐） |
| 2 | ~6（View + layout + drawable + colors） | 2 | 3（FloatingPanel / OverlayLifecycleOwner / ScrollConfig） | 中（UI 重写，靠对齐清单控险） | 无（功能对齐） |
| 3 | 1-2（PerAppDetector + 测试） | 4 | 0 | 低-中（叠加功能，可 flag） | 新增四项功能 |
| 4 | 2（baselineprofile 模块） | 3 | 0 | 低（纯增量） | 性能提升 |

---

## 总体验收标准

1. `./gradlew test` 全绿；新增核心循环 / 状态机 / 仓库 / 方向 / per-app 检测单测覆盖。
2. Phase 2 对齐验收清单全部通过；悬浮窗视觉与交互等价于 V1。
3. 四项产品功能可用：场景预设切换、运行统计显示与重置、方向切换生效、按 App 记忆（开关可控、自动切换、可忘记）。
4. DataStore 迁移零数据丢失（旧用户升级后配置保留）。
5. 阅读期间悬浮窗常驻内存下降（Compose 运行时移除）；冷启动 / 首帧有可测量的改善（Baseline Profile）。
6. `MainActivity`/`MainScreen` 仍用 Compose 且正常工作。

---

## 依赖新增

| 依赖 | 用途 | 阶段 |
|------|------|------|
| `androidx.datastore:datastore-preferences` | 持久化（替代 SharedPreferences） | Phase 1 |
| `com.google.android.material:material` | 原生 Material 控件（Slider / MaterialCardView） | Phase 2 |
| `androidx.benchmark:baseline-profile-gradle-plugin` + `macro-junit4` | Baseline Profile 生成 | Phase 4 |

均在标准 AndroidX / Google 体系内，不引入额外第三方库。
