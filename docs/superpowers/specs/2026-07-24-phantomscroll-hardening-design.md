# PhantomScroll 健壮性与可测试性加固设计文档

> 日期：2026-07-24
> 状态：已批准，待实施
> 前置文档：[2026-06-14-phantomscroll-v2-optimization-design.md](./2026-06-14-phantomscroll-v2-optimization-design.md)（V2 Phase 1-3 已落地）
> 范围：B+C —— 健壮性/质量加固（系统性，非补丁）+ 可测试性补强 + 技术债清理

---

## 概述

V2 Phase 1-3 已落地（data 层、原生 overlay、PerAppDetector、Presets、统计、方向切换）。但 `per-app` 相关的 race condition 已被反复打补丁至少 3 次（`6d95e25` / `349feea` / `ededb56` / `f430d93`），CoroutineScope 生命周期取消 bug（`dcc9d88`），以及一系列审计到的技术债（主线程 `runBlocking` ANR 风险、绕过日志门控的调试日志、setter 副作用、死代码、死逻辑）。

**根因**：`SettingsRepository` 同时承担三种职责且边界模糊——**状态持有**、**状态派生**、**持久化编排**（3 路并存）。每种职责并发模型不同，混在一起产生竞态；per-app 一致性规则散落在 6+ 个直接改 StateFlow 的方法里，无单一归属。

**本次目标**：把 repository 重构为**清晰三层 + 单一持久化路径**，把目前不可测的 Service 编排和 repository 并发行为变成可回归测试的纯逻辑/受控并发，并清理审计到的全部技术债。用回归测试锁死历史 bug 不复发。

**非目标（明确不做）**：
- 不引 Robolectric（Overlay 的 View 依赖靠手工对齐验证，不 instrumented test）。
- 不碰 gesture/WindowManager 真实硬件交互路径（`dispatchGesture` 不 mock）。
- 不改持久化 schema（DataStore key 不变，数据零迁移风险）。
- 不做 V2 Phase 4 的 Baseline Profile / Compose stability（独立工作，不在本次范围）。
- 不新增产品功能。

---

## 整体策略：三层数据流

```
┌─ StateHolder（纯内存）──────────────────────────────────┐
│  6 个 StateFlow：global / profiles / perAppEnabled /     │
│  currentPackage / stats / isRunning + 屏幕尺寸            │
│  职责：只持有与暴露，零持久化、零副作用                    │
│  并发：StateFlow 原子更新，所有写经统一入口                │
└──────────────────────────────────────────────────────────┘
          │                                      ▲
          ▼ apply(变更意图)                      │ observe
┌─ SettingsReducer（纯逻辑·可单测）─────────────────────────┐
│  无状态纯函数：输入(当前状态快照, 变更意图) → 变更增量     │
│  per-app 一致性规则的唯一归属地，零 I/O                    │
└──────────────────────────────────────────────────────────┘
          │ 经 repository.apply() 原子提交
          ▼
┌─ PersistedStateCoordinator（单一持久化路径）──────────────┐
│  1× debounce(500ms) collector：观察 4 个可持久化 StateFlow │
│  → 变化即落盘。onDestroy/onTrimMemory 触发非阻塞 flush     │
│  init 加载用显式 join，消除"加载 vs 首次写"竞态            │
└──────────────────────────────────────────────────────────┘
```

**三条关键原则**：
1. **单一持久化路径**：删除 periodic saver 和所有即时 `scope.launch` 落盘，只保留 debounce collector + 关键时机 flush。
2. **状态变更必经 reducer**：消除 setter 副作用，per-app 三态联动（currentPackage/perAppEnabled/global）在 reducer 原子完成。
3. **加载竞态消除**：init 异步加载完成后才启动持久化 collector，避免默认值被 debounce 写回覆盖磁盘真实值。

---

## 第 1 节：SettingsReducer —— 纯逻辑意图模型

所有"状态如何变"的决策抽成无状态纯函数，让 per-app 一致性规则有唯一归属、可单测、并发下可安全重放。

### 意图（sealed interface）

作为变更的唯一入口，替代当前散落的直接改 StateFlow 方法：

```kotlin
sealed interface SettingsIntent {
    /** 用户/系统切换了前台 App（来自 PerAppDetector 决策） */
    data class PackageSwitched(val pkg: String?) : SettingsIntent
    /** 用户拨动 per-app 开关 */
    data class PerAppToggled(val enabled: Boolean) : SettingsIntent
    /** 用户编辑了某个参数（Slider/方向按钮）——写入 active 目标 */
    data class SettingEdited(val transform: (ScrollSettings) -> ScrollSettings) : SettingsIntent
    /** 应用预设到 global */
    data class PresetApplied(val settings: ScrollSettings) : SettingsIntent
    /** 忘记当前 App 配置 */
    data object ForgetActiveApp : SettingsIntent
    /** 屏幕尺寸变化（不再带改 distanceRatio 的副作用） */
    data class ScreenSizeChanged(val width: Int, val height: Int) : SettingsIntent
}
```

### reducer 签名

```kotlin
object SettingsReducer {
    /** 输入当前可变状态的只读快照 + 意图，返回需要变更的字段增量。 */
    fun reduce(state: SettingsSnapshot, intent: SettingsIntent): SettingsDelta

    /** 首次加载：把磁盘值与默认值合并（distanceRatio 屏幕适配仅在此处应用一次）。 */
    fun reconcileInitial(loaded: LoadedState, screenHeight: Int): SettingsDelta
}
```

`SettingsSnapshot` 是 6 个 StateFlow 当前值的不可变快照（reducer 只读输入）；`SettingsDelta` 描述"哪些字段变了"（避免 reducer 越权改不该改的字段）。repository 的 `apply()` 是唯一实际改 StateFlow 的地方，保证原子性边界。

### 关键一致性规则（从 repository 现有逻辑提炼，集中到此）

| 规则 | 当前散落位置 | 重构后归属 |
|------|------------|-----------|
| `PackageSwitched(pkg)` → 有 profile 则 perAppEnabled=true，否则=false | `setCurrentPackage:234-238` | reducer |
| `PackageSwitched` **不再重置 global**（消除隐式副作用，bug 来源） | `setCurrentPackage:237` | **删除该行为** |
| `PerAppToggled(true)` → 从 global 克隆创建当前 pkg profile | `setPerAppEnabled:247-249` | reducer |
| `PerAppToggled(false)` → 删除当前 pkg profile | `setPerAppEnabled:259` | reducer |
| `SettingEdited` → perApp 开启且有 pkg 写 profile，否则写 global | `updateActive:197-203` | reducer |
| `ForgetActiveApp` → 删 profile + perAppEnabled=false（原子） | `forgetActiveProfile:208-211` | reducer |
| distanceRatio 默认值随屏幕高度推导（仅首次加载/无用户值） | `setScreenHeight:73-82` + `getDefaultSettings:84` | `reconcileInitial` |

### 行为变化（用户可见，需真机确认）

**删除"切 App 重置 global"**（`setCurrentPackage:237`，由 commit `193b9cb` 引入）：当前切到无 profile 的 App 时会把 `_global` 重置为默认值，直接污染全局默认——用户为全局调好的参数会被前台 App 切换悄悄覆盖，是 per-app 行为混乱的来源之一。重构后：切 App 只影响 `currentPackage`/`perAppEnabled`，**绝不碰 global**。distanceRatio 屏幕适配仅在首次加载且用户从未改过时算一次。

> 这是消除 race 的关键之一，但属用户可见行为变化，需在真机验证：切到新 App 时全局参数保持不变。

### 为什么用 Delta 而非返回新 Snapshot

reducer 不持有可变状态，返回 Delta 让 `apply()` 成为唯一改 StateFlow 的入口，原子性边界清晰，并发下 reducer 可被任意重放而无副作用。

---

## 第 2 节：单一持久化路径与并发模型

把 3 路并存（periodic saver / 即时 launch / 阻塞 flush）收敛为**单一 debounce collector + 关键时机 flush**，受控并发消除加载竞态。

### repository 启动时序（消除加载竞态）

```kotlin
init {
    scope.launch(ioDispatcher) {
        val loaded = store.loadAll()                       // 显式加载，阻塞本协程不阻塞主线程
        applyDelta(reducer.reconcileInitial(loaded, screenHeight))  // 合并默认值与屏幕适配
        startPersistenceCollector()                        // 加载完成后才启动 collector
        _initialized.value = true                          // 供测试 await
    }
}
```

关键：当前 bug 是异步加载与"用户已开始改参数"之间存在窗口。重构后 collector 在加载完成后才启动，保证磁盘真实值先就位，不会被默认值覆盖。

### 单一持久化 collector

```kotlin
private fun startPersistenceCollector() {
    scope.launch(ioDispatcher) {
        combine(_global, _profiles, _perAppEnabled, _stats) { g, p, e, s ->
            PersistableSnapshot(g, p, e, s)
        }.debounce(PERSIST_DEBOUNCE_MS)         // 复活 PERSIST_DEBOUNCE_MS=500L，真正接入
         .collect { snapshot -> store.saveAll(snapshot) }
    }
}
```

**删除项**：
- `init` 里的 5 分钟 periodic saver 整块（`:122-159`）
- `setPerAppEnabled` 的即时 `scope.launch` 落盘（`:245-272`）
- `forgetActiveProfile` 的直接 `store.deleteProfile`/`saveAllProfiles`（`:212-219`）

所有持久化只经此 collector。

### 关键时机 flush（替代 onDestroy 阻塞）

当前 `onDestroy` 的 `runBlocking` + `withTimeout(1000ms)` 有 ANR 风险。重设计为非阻塞：

```kotlin
// repository 内：非阻塞 flush，立即触发（跳过 debounce）并 await
suspend fun flush() {
    // cancel 挂起的 debounce，触发一次即时落盘并等待完成
}

// Service.onDestroy：在 IO 线程 await，不阻塞主线程
override fun onDestroy() {
    repository.stopRunning()
    serviceScope.launch(Dispatchers.IO) {
        runCatching { withTimeout(1500L) { repository.flush() } }
        cleanupComponents()
        serviceScope.cancel()
    }
}
```

实施细节（即时 flush 如何插队跳过 debounce）在实现时确定（`Channel` + `select`，或"cancel collector 后直接 `store.saveAll` 一次再 await"）。核心保证：flush 非阻塞、可 await、有超时保护、flush 完成前不销毁 scope。

### 并发安全保证

- 所有 StateFlow 写只发生在 `applyDelta()`（单一入口，StateFlow 原子更新）。
- reducer 无状态可安全重放。
- 加载完成前 collector 未启动 → 无"默认值覆盖磁盘"。
- `isRunning`/屏幕尺寸不进持久化 collector（非持久化字段），互不干扰。

### isRunning API 收敛（T7）

删除 4 个 mutator（`setRunning`/`stopRunning`/`startRunning`/`toggleRunning`）+ `isRunningMutable` 双风格，统一为：
```kotlin
var isRunning: Boolean   // backing 到 _isRunning，setter 即赋值
fun toggleRunning()
```
ScreenStateCoordinator 用 `isRunning =` 赋值。语义不变，API 面收缩。

### onDestroy 时序的行为变化

onDestroy 改为"IO 协程 await flush 完成后再 cancel"会让 Service 销毁略延迟（最多 1.5s 超时）。替代方案是接受偶尔丢最后 500ms 内的改动——本设计选择前者以保证零数据丢失。

---

## 第 3 节：Service / Overlay 可测试性接口

### 3.1 事件过滤纯逻辑抽取

`PhantomScrollService.onAccessibilityEvent:102-128` 混了 4 件事：事件类型过滤、包名提取、高频事件跳过、per-app 决策。抽取前三者为纯函数 `PackageChangeExtractor`（无 Android 依赖）：

```kotlin
object PackageChangeExtractor {
    /**
     * 从无障碍事件中提取"是否应处理 + 包名"。纯逻辑，输入为已剥离 AccessibilityEvent 的原始字段。
     * @return 需要送 PerAppDetector 的包名，或 null 表示该事件应忽略。
     */
    fun extract(eventType: Int, eventPackage: String?, currentPackage: String?): String?
}
```

逻辑（从 Service 现有代码逐行搬移）：
- 仅处理 `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`，其余返回 null。
- `TYPE_WINDOW_CONTENT_CHANGED` 且包名未变 → null（高频事件跳过）。
- 否则返回包名（可能为 null）。

`onAccessibilityEvent` 瘦身为：

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (!repository.perAppEnabled.value) return          // 关闭时零开销（补 spec §3.4 遗漏）
    val pkg = PackageChangeExtractor.extract(
        event?.eventType ?: return,
        event.packageName?.toString(),
        repository.currentPackage.value
    ) ?: return
    val decision = perAppDetector.evaluate(pkg, repository.currentPackage.value, System.currentTimeMillis())
    if (decision is PerAppDecision.Handle) repository.apply(SettingsIntent.PackageSwitched(decision.packageToSet))
}
```

> 补 `perAppEnabled` 早退：原 Service 开头无此早退（spec §3.4 要求"关闭时零开销"），属遗漏，纯增量补上。

### 3.2 repository 并发回归测试

用 `kotlinx-coroutines-test`（coroutines 标配，非新框架）锁死历史 race。基础设施：

```kotlin
class SettingsRepositoryConcurrencyTest {
    private lateinit var fakeStore: FakeProfileStore   // 现有，扩为可记录调用顺序
    private lateinit var repo: SettingsRepository
    // StandardTestDispatcher + runTest 虚拟时间控制
}
```

**关键回归测试用例**（每个对应一次历史 race）：

| 用例 | 锁死的历史 bug | 对应 commit |
|------|--------------|------------|
| 加载完成前不写盘：init 期间多次改 global，加载完成后落盘的是磁盘值而非默认值 | 加载竞态覆盖 | `6d95e25` |
| per-app 开关竞态：快速 toggle 多次，最终 profile/perAppEnabled 状态一致 | toggle 竞态 | `349feea` |
| 切 App 与编辑交错：切 App 的瞬间编辑参数，写入目标是当前而非旧 App | 切换瞬间写错目标 | `6d95e25` |
| flush 不阻塞主线程：flush 在 IO 线程完成，主线程不挂 | runBlocking ANR（T2） | 本次 |
| debounce 合并高频写：滑动 Slider 高频改值，500ms 内只落盘一次 | 高频 I/O | `ededb56` |
| onDestroy flush 保证：scope 取消前 flush 完成，不丢最后改动 | 销毁丢数据 | 本次 |
| forgetActive 原子性：忘记 App 时 profile 删除与 perAppEnabled=false 原子 | 双重写路径竞态 | `f430d93` |

### 3.3 Overlay 可测性边界

`FloatingOverlayView` 深度依赖 Android 框架，本次不强求 instrumented test（避免 Robolectric 重量）。Overlay 的问题主要是技术债（日志/资源泄漏）而非逻辑 bug，第 4 节用清理解决。Overlay 的纯逻辑（`OverlayGeometry`/`ParamSteps`）已测；状态绑定（`applySettings`/`applyRunning`）本质是"StateFlow → View 属性"命令式映射，逻辑薄，靠手工对齐验证（沿用 V2 Phase 2 清单）。

---

## 第 4 节：技术债逐项处理

| # | 技术债 | 处理 | 落点 |
|---|--------|------|------|
| T1 | 6 处 `android.util.Log.e` 调试日志（绕过 PhantomLog 门控，来自 `d584980`） | 删除 | FloatingOverlayView |
| T2 | `runBlocking`+`withTimeout(1000ms)` 主线程阻塞（ANR 风险） | IO 协程非阻塞 await flush（第 2 节） | PhantomScrollService.onDestroy + SettingsRepository.flush |
| T3 | `init:117` 死逻辑（加载时 currentPackage 必为 null → perAppEnabled 恒 false） | 删除，perAppEnabled 初值随加载的 profiles 由 reducer 决定 | SettingsRepository |
| T4 | `setScreenHeight` 副作用改 global.distanceRatio（setter 隐含业务规则） | 抽为 reducer 的 `reconcileInitial`，仅首次加载应用 | SettingsReducer |
| T5 | `PERSIST_DEBOUNCE_MS=500L` 死常量（已无人用） | 复活，真正接入 debounce collector | SettingsRepository |
| T7 | `setRunning`/`stopRunning`/`startRunning`/`toggleRunning` + `isRunningMutable` 双风格 | 收敛为 `var isRunning` + `toggleRunning()` | SettingsRepository + 消费方 |
| T8 | 多处 `catch { PhantomLog.e }` 一刀切吞异常 | 分层：I/O 失败（flush/落盘）记录不抛（非致命，下次重试）；reducer 失败不应发生（纯逻辑）抛 AssertionError 促暴露 | SettingsRepository |

> T6（当前工作区未提交改动）已于本设计前单独 commit 收尾（`5ed6023` refactor(overlay): replace count badge with pure mascot icon bubble）。

---

## 第 5 节：测试矩阵

### 纯逻辑 JVM 测试（不引 Robolectric）

- 现有 12 个测试文件全部保留。
- **新增 `SettingsReducerTest`**：每个 intent 的 reduce 行为、per-app 一致性规则、distanceRatio 屏幕适配仅首次加载、切 App 不碰 global。
- **新增 `PackageChangeExtractorTest`**：事件类型/包名变化/高频跳过/null 边界。
- **新增 `SettingsRepositoryConcurrencyTest`**（`kotlinx-coroutines-test`）：第 3.2 节的 7 个并发回归用例。
- **扩 `SettingsRepositoryTest`**：`apply(intent)` 单一入口、`flush()` 非阻塞语义、isRunning 新 API。

### 手工对齐验证（真机）

沿用现状验证清单——切 App profile 切换/回落、per-app 开关克隆/删除、forget、Slider 实时生效、锁屏恢复、杀进程重启配置保留。

**新增（行为变化验证）**：确认切 App 不再重置全局参数。

---

## 第 6 节：文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `data/SettingsIntent.kt`（sealed interface） |
| 新建 | `data/SettingsSnapshot.kt` + `SettingsDelta.kt`（reducer I/O 类型） |
| 新建 | `data/SettingsReducer.kt`（无状态纯逻辑 + `reconcileInitial`） |
| 新建 | `service/PackageChangeExtractor.kt`（纯逻辑） |
| 新建测试 | `SettingsReducerTest`、`PackageChangeExtractorTest`、`SettingsRepositoryConcurrencyTest` |
| 修改 | `data/SettingsRepository.kt`（三层重构） |
| 修改 | `service/PhantomScrollService.kt`（onAccessibilityEvent + onDestroy） |
| 修改 | `ui/overlay/FloatingOverlayView.kt`（删 6 处调试日志 + API 适配） |
| 修改 | `service/ScreenStateCoordinator.kt`（`isRunningMutable` → `isRunning =`） |
| 修改 | `service/ScrollOrchestrator.kt`（isRunning 调用适配） |
| 修改 | `service/FloatingWindowController.kt`（若有 isRunning/profiles 消费适配） |
| 修改测试 | `SettingsRepositoryTest`（适配新 API + apply 入口） |
| 依赖 | `gradle/libs.versions.toml` + `app/build.gradle.kts`：确认/添加 `kotlinx-coroutines-test` |

**SettingsRepository.kt 重构明细**：
- 删除：5 分钟 periodic saver 整块、`setPerAppEnabled`/`forgetActiveProfile` 即时 launch 落盘、`init:117` 死逻辑、`setScreenHeight` 副作用、`updateGlobal`/`updateActive`/`upsertProfile`/`deleteProfile`/`setCurrentPackage`/`setPerAppEnabled`/`forgetActiveProfile` 散落方法（合并为 `apply(intent)`）、`setRunning`/`stopRunning`/`startRunning`/`toggleRunning`/`isRunningMutable`（收敛）。
- 新增：`apply(intent: SettingsIntent)` 单一入口、`applyDelta(delta)`、`startPersistenceCollector()`、非阻塞 `flush()`、`var isRunning`、分层错误处理。

> 不删除任何现有源文件（无 FloatingPanel/ScrollConfig 遗留——V2 Phase 2 已清）。不引 Robolectric/Material 新依赖。DataStore key 不变，数据零迁移风险。

---

## 第 7 节：验收标准

1. `./gradlew test` 全绿，含 3 个新测试文件 + 扩展的现有测试。
2. 7 个并发回归测试覆盖历史 race（每条对应一次 commit 的 bug）。
3. 真机手工对齐：per-app 切换/回落/开关/forget/Slider/锁屏/重启配置保留 全部正常。
4. **行为变化已验证**：切 App 不再重置全局参数（用户确认可接受）。
5. 无 `android.util.Log` 直调（全部走 PhantomLog）；无 `runBlocking` 在主线程。
6. 无死代码（periodic saver / 即时 launch / T3 死逻辑 / 未用常量已删）。
7. isRunning API 收敛为 2 个入口；reducer 成为 per-app 一致性规则唯一归属。

---

## 第 8 节：风险与回退

- **风险**：repository 重构面较大，触及 Service/Overlay/Orchestrator 多个消费方。
- **缓解**：reducer 是无状态纯逻辑，行为可逐条用单测锁死；消费方改动是机械的 API 适配（`isRunning =`、`apply(intent)`）。
- **回退**：单次 commit/PR，可整体 revert；DataStore key 不变，数据零风险。
- **行为变化风险**：仅"切 App 不重置 global"一项用户可见变化，已单独列出需真机确认；onDestroy 销毁略延迟（≤1.5s 超时）以保证零数据丢失。
