# PhantomScroll V2 · Phase 4 实施计划：性能收尾 + 工程化（Baseline Profile / Compose 稳定性 / 热路径零分配 / 测量留痕）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 Phase 1～3 之上做**纯增量**的性能收尾：① 用 Baseline Profile 加速 `MainActivity`（权限页）冷启动与首帧；② 开 Compose 编译器稳定性报告，修 `MainScreen` 的不稳定参数减少重组；③ 复核热路径零分配（per-app 关闭早退、统计自增无装箱、手势 Path 复用不变）；④ 把性能测量数字（冷启动 / 首帧 / 内存）写进 spec 与 README。**不改任何用户可见行为，不改持久化 schema。**

**Architecture:** 新增独立的 Gradle 模块 `:baselineprofile`（com.android.test + `androidx.baselineprofile` 插件），写一个 `BaselineProfileGenerator` 仪器测试驱动"启动 MainActivity"这一关键路径生成 profile；profile 产物落到 `app/src/release/generated/baselineProfiles/`，release 构建自动打入 APK。`app` 模块加 `androidx.profileinstaller`（运行时分发 profile）+ 接 `baselineProfile` sourceSet。`MainScreen` 拆分/标注稳定类型降重组。所有"测量"动作只读不改代码。

**Tech Stack:** Kotlin, AndroidX Baseline Profile（`androidx.baselineprofile` 插件 + `benchmark-macro-junit4`），Jetpack Compose 编译器（Kotlin 2.0 `composeCompiler {}` DSL），JUnit 4，`adb dumpsys meminfo`。

**Spec:** [docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md](../specs/2026-06-14-phantomscroll-v2-optimization-design.md) §Phase 4

> **本计划范围：仅 Phase 4。** 是 V2 的最后一个阶段；完成后 V2 全部收口。
>
> **提交约定：** 每个任务末尾的 `git commit` 均需在提交信息末尾追加一行 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`（下文各 commit 命令示例中不再重复写出该行）。
>
> **测试命令约定：** 全量单测 `./gradlew :app:testDebugUnitTest`；编译校验 `./gradlew assembleDebug`；混淆构建 `./gradlew assembleRelease`。Baseline Profile 生成与宏基准测量**需要真机/模拟器**（Task 2/4），本环境若无 adb/模拟器则只做到"代码与配置就绪 + 构建通过"，profile 生成与数字记录留为手工验收项（与 Phase 2/3 同策略）。
>
> **版本基线（核对自当前代码 + 官方 release notes）：**
> - AGP `8.7.3`、Kotlin `2.0.21`、Compose BOM `2024.12.01`、`compileSdk/targetSdk 35`、`minSdk 26`。
> - `settings.gradle.kts` 当前只 `include(":app")`；`build.gradle.kts`（root）声明 `android.application`/`kotlin.android`/`compose.compiler` 三个插件 `apply false`，**没有** `androidx.baselineprofile` 插件。
> - `app/build.gradle.kts` **没有** `profileinstaller` 依赖，**没有** `baselineProfile` sourceSet 配置。Phase 4 需要补这两项（profileinstaller 是运行时分发 profile 的硬依赖）。
> - `MainScreen.kt` 是**唯一剩余的 Compose 界面**（权限页），权限状态用三个 `by remember { mutableStateOf }` 局部 state；`PermissionItem` / `InstructionStep` 是私有 `@Composable`，参数是 `String`/`Boolean`/`() -> Unit`（稳定类型，但 Compose 编译器默认仍可能把它们标 Instable 当成 lambda）。
> - Phase 3 收尾单测 **56** 个；Phase 4 **不加新单测**（性能工作不产出可 JVM 测的纯逻辑）。
>
> **Baseline Profile 版本选择（关键决策）：** 官方 release notes 显示 `androidx.benchmark/baselineprofile` 1.3.x 已支持到 AGP 9.0，1.4.1（2025-09）是当前稳定线且向后兼容 AGP 8.x。本计划采用 **1.3.4**（成熟稳定、与 AGP 8.7.3 实测兼容、社区案例最多）；若 1.3.4 在本机出兼容问题，回退项是升到 **1.4.1**（同 API，只需改版本号）。插件 ID：`androidx.baselineprofile`；仪器测试依赖：`androidx.benchmark:benchmark-macro-junit4`；运行时依赖：`androidx.profileinstaller:profileinstaller`（app 模块，默认用 1.4.1）。
>
> **为什么 Baseline Profile 主要利好 MainScreen：** Phase 2 已把悬浮窗改原生 View，阅读期不再常驻 Compose 运行时。Compose 现在只在 `MainActivity`（权限页）出现，是冷启动路径。Profile 预编译 Compose 相关类 + Application/通知 channel 初始化，直接降冷启动与首帧。
>
> **环境前置说明：** Baseline Profile 的**生成**（Task 2）与**测量**（Task 4）依赖一台真机或模拟器（API 28+，非低电量、非 debuggable）。本会话环境 `android_preflight` 显示无 adb/emulator，因此这两步在本环境只能"配好、构建通过、留命令"，真正跑需在有 SDK 的机器上执行。Task 1/3/5 可在本环境完成（配置 + 代码 + 文档）。

---

## 文件结构（Phase 4 新增/修改）

**新增：**

| 文件 | 职责 |
|------|------|
| `:baselineprofile/build.gradle.kts` | 新模块：`com.android.test` + `androidx.baselineprofile` 插件，依赖 `:app` |
| `:baselineprofile/src/main/AndroidManifest.xml` | test 模块最小 manifest（package 指向 instrumentation） |
| `:baselineprofile/src/main/java/com/phantom/scroll/baselineprofile/BaselineProfileGenerator.kt` | `BaselineProfileRule` 驱动"启动 MainActivity → 待首帧"生成 profile |
| `:baselineprofile/src/main/java/com/phantom/scroll/baselineprofile/StartupBenchmark.kt` | （可选）`MacrobenchmarkRule` 测 `StartupTimingMetric`，给 Task 4 提供数字 |
| `app/src/main/assets/`（若空则保留） | 不手写；profile 产物由插件生成到 `generated/` |

**修改：**

| 文件 | 改动 |
|------|------|
| `settings.gradle.kts` | `include(":baselineprofile")` |
| `build.gradle.kts`（root） | 加 `com.android.test` 与 `androidx.baselineprofile` 两条插件 `apply false` |
| `gradle/libs.versions.toml` | 加 `baselineprofile`/`macrobenchmark`/`profileinstaller` 版本与库声明 |
| `app/build.gradle.kts` | 应用 `androidx.baselineprofile` 插件并依赖 `:baselineprofile` 模块，加 `profileinstaller` 依赖与 `composeCompiler {}` 报告配置 |
| `app/src/main/java/com/phantom/scroll/ui/screen/MainScreen.kt` | 提取并封装 `@Immutable PermissionStatus` 状态类，内聚并收敛局部 State 的读取，缩小重组范围 |
| `README.md` | 修掉已失效的 `ScrollConfig.kt`/`FloatingPanel.kt`/`OverlayLifecycleOwner.kt` 引用；补 V2 架构（data 层 / 原生悬浮窗 / 预设·统计·方向·per-app）与性能数据占位 |
| `docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md` | §Phase 4 末尾补"实测数字"小节（Task 4 产出） |

**删除：** 无。

> **不改动：** `data/` 全层、`service/` 全层、`gesture/`、`notification/`、`MainActivity.kt`（仅 `MainScreen.kt` 改）、悬浮窗全部资源、`themes.xml`、`proguard-rules.pro`（Baseline Profile 无需额外 keep；Phase 2/3 已验证 release 通过）。

---

## Task 1：`:baselineprofile` 模块骨架 + 依赖接线

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`（root）
- Modify: `settings.gradle.kts`
- Create: `baselineprofile/build.gradle.kts`
- Create: `baselineprofile/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`（profileinstaller 依赖 + baselineProfile sourceSet）

> 先把模块与依赖配齐，让它能独立 `:baselineprofile:assemble`（暂不写生成器代码）。这一步的产物是"配置正确、`assembleDebug` 仍绿"。

- [ ] **Step 1：版本目录加三组声明**

编辑 `gradle/libs.versions.toml`：

(a) `[versions]` 段（`material = "1.12.0"` 行之后）追加：
```toml
benchmark = "1.3.4"
profileinstaller = "1.4.1"
```

(b) `[libraries]` 段（`google-material` 声明之后）追加：
```toml
# Baseline Profile (macrobenchmark + runtime profile installer)
androidx-benchmark-macro-junit4 = { group = "androidx.benchmark", name = "benchmark-macro-junit4", version.ref = "benchmark" }
androidx-profileinstaller = { group = "androidx.profileinstaller", name = "profileinstaller", version.ref = "profileinstaller" }
```

(c) `[plugins]` 段（`compose-compiler` 之后）追加**两条**：
```toml
android-test = { id = "com.android.test", version.ref = "agp" }
androidx-baselineprofile = { id = "androidx.baselineprofile", version.ref = "benchmark" }
```
> `com.android.test` 由 AGP 提供，故 `version.ref = "agp"`（与 `android-application` 同源）。它在 Step 2 被 root build 声明 `apply false`、在 Step 4 被 `:baselineprofile` 模块 `apply`。

- [ ] **Step 2：root build.gradle.kts 注册插件**

编辑 `build.gradle.kts`（root），在 `plugins { }` 内追加**两条**（`com.android.test` 供 `:baselineprofile` 模块使用，`androidx.baselineprofile` 供 app + `:baselineprofile` 模块使用）：
```kotlin
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.androidx.baselineprofile) apply false
```
> ⚠️ **必须**两条都加 `apply false` 在 root。`com.android.test` 若不在 root 声明，`:baselineprofile` 模块 `apply plugin: 'com.android.test'` 会报 "plugin not recognized"（见 [Stack Overflow](https://stackoverflow.com/questions/74855233/android-macrobenchmark-module-doesnt-recognise-com-android-test-plugin)）。两条 alias 的版本目录条目已在 Step 1(c) 声明。

- [ ] **Step 3：settings.gradle.kts include 新模块**

编辑 `settings.gradle.kts`，把
```kotlin
include(":app")
```
改为
```kotlin
include(":app")
include(":baselineprofile")
```

- [ ] **Step 4：新建 `:baselineprofile` 模块 build 脚本**

Create `baselineprofile/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.phantom.scroll.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28          // baseline-profile 生成要求 API 28+
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation("androidx.test.ext:junit:1.2.1")
}
```

> 注：`alias(libs.plugins.android.test)` 解析到 `com.android.test`，已在 Step 1(c) 的版本目录声明、并在 Step 2 由 root build 声明 `apply false`。`kotlin-android` 已在版本目录与 root 存在（V1 起），无需新增。
- [ ] **Step 5：test 模块最小 manifest**

Create `baselineprofile/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```
（`com.android.test` 模块的 instrumentation 由 `targetProjectPath` + 插件自动接线，manifest 仅需占位。）

- [ ] **Step 6：app 模块加 profileinstaller + baselineProfile 依赖**

编辑 `app/build.gradle.kts`：

(a) `plugins { }` 块内，在 `alias(libs.plugins.compose.compiler)` 之后追加：
```kotlin
    alias(libs.plugins.androidx.baselineprofile)
```

(b) （可选，默认无需添加）`android { }` 块内**只有当**日后需要按类名过滤 profile 时才加 `baselineProfile { }` 配置块。当前 phase 保持默认即可（插件自动把 Step 6(c) 的 producer 接到 release 变体）：
```kotlin
    // baselineProfile { ... }   // 默认空配置即可，本期不加；过滤器/自动生成属可选项
```
> 若 Step 7 编译报 "no baseline profile producer"，说明 6(c) 的 `baselineProfile(project(...))` 未被插件识别 —— 此时再显式加 `baselineProfile { sourceSet { from(project(":baselineprofile")) } }`。

(c) `dependencies { }` 块内（`implementation(libs.google.material)` 附近）追加依赖关联：
```kotlin
    // Link to the baselineprofile generator module (Phase 4)
    baselineProfile(project(":baselineprofile"))

    // Runtime distribution of Baseline Profiles (Phase 4)
    implementation(libs.androidx.profileinstaller)
```

- [ ] **Step 7：验证 `:app` 与 `:baselineprofile` 都能编译**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`（`:baselineprofile` 此时无测试代码，只 assemble 它的结构；`profileinstaller` 拉入 app 依赖图）

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（56，不变；Phase 4 不改任何逻辑）

- [ ] **Step 8：Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts settings.gradle.kts app/build.gradle.kts baselineprofile/
git commit -m "build: add :baselineprofile module + profileinstaller (Phase 4 scaffolding)"
```

---

## Task 2：BaselineProfileGenerator 仪器测试（生成 profile）

**Files:**
- Create: `baselineprofile/src/main/java/com/phantom/scroll/baselineprofile/BaselineProfileGenerator.kt`
- Create: `baselineprofile/src/main/java/com/phantom/scroll/baselineprofile/StartupBenchmark.kt`（可选，Task 4 用）

> 写"启动 MainActivity → 等首帧"的关键用户旅程（CUJ），用 `BaselineProfileRule` 收集 profile。`applicationId` = `com.phantom.scroll`（见 `app/build.gradle.kts`）。**此 Task 的代码可在本环境写完并通过编译；真正"生成 profile"需真机/模拟器跑 `:baselineprofile:generateReleaseBaselineProfile`（见 Step 3），属手工验收。**

- [ ] **Step 1：写 BaselineProfileGenerator**

Create `baselineprofile/src/main/java/com/phantom/scroll/baselineprofile/BaselineProfileGenerator.kt`:
```kotlin
package com.phantom.scroll.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for the app's critical user journey: cold-launching
 * [com.phantom.scroll.MainActivity] (the Compose permission screen) and waiting for first frame.
 *
 * Run with:  ./gradlew :baselineprofile:generateReleaseBaselineProfile
 * (requires a connected device/emulator, API 28+, non-debuggable release-ish build).
 *
 * The generated profile lands in app/src/release/generated/baselineProfiles/baseline-prof.txt
 * and is automatically embedded in the release APK by the androidx.baselineprofile plugin.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() = baselineRule.collect("phantomscroll-startup") {
        // Cold launch the permission screen and wait for the first frame to render.
        // This is the only Compose surface in the app post-Phase-2, so it dominates startup cost.
        pressHome()
        startActivityAndWait()
        // Give the Compose hierarchy a moment to settle (permission cards inflate).
        Thread.sleep(500)
    }
}
```

- [ ] **Step 2：（可选）写 StartupBenchmark 供 Task 4 测量**

Create `baselineprofile/src/main/java/com/phantom/scroll/baselineprofile/StartupBenchmark.kt`:
```kotlin
package com.phantom.scroll.baselineprofile

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Macrobenchmark that measures [StartupTimingMetric] for the permission screen.
 * Run twice — once with [CompilationMode.None] (no profile) and once with
 * [CompilationMode.Partial] (profile installed) — to quantify the Baseline Profile win.
 *
 * Run with:  ./gradlew :baselineprofile:connectedReleaseBenchmark
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupNoProfile() = benchmark(CompilationMode.None())

    @Test
    fun startupWithProfile() = benchmark(CompilationMode.Partial())

    private fun benchmark(mode: CompilationMode) {
        rule.measureRepeated(
            packageName = "com.phantom.scroll",
            metrics = listOf(StartupTimingMetric()),
            iterations = 10,
            startupMode = StartupMode.COLD,
            compilationMode = mode
        ) {
            pressHome()
            startActivityAndWait()
        }
    }
}
```

> `AndroidJUnit4` 与 `@Rule`/`@Test` 的运行依赖 `androidx.test.ext:junit`，**已在 Task 1 Step 4 的 `:baselineprofile` dependencies 加过**（`implementation("androidx.test.ext:junit:1.2.1")`），这里无需重复加。若你想走版本目录而非字面量坐标，可把该依赖改写成 `libs` 引用并在 `libs.versions.toml` 补 `androidx-test-ext-junit` 条目（本期不要求）。

- [ ] **Step 3：验证 `:baselineprofile` 编译通过**

Run: `./gradlew :baselineprofile:compileReleaseKotlin`
Expected: `BUILD SUCCESSFUL`（此时不连设备，只验证测试代码可编译）

- [ ] **Step 4：生成 profile（需真机/模拟器 —— 本环境若无则记为手工验收）**

Run（**需要连接设备/模拟器**）:
```bash
./gradlew :baselineprofile:generateReleaseBaselineProfile
```
Expected:
- 插件构建 `:app` 的 `nonMinified` release 变体 → 安装到设备 → 跑 `BaselineProfileGenerator` → 抓取 profile → 落到 `app/src/release/generated/baselineProfiles/baseline-prof.txt`（与 `baselineProfiles-src/*.txt`）。
- 终端打印 profile 生成摘要。

> 若本环境无 adb/emulator：跳过此步，在 Task 4/11 的验收清单里标注"profile 生成待真机执行"。代码与配置就绪即算本 Task 代码部分完成。

- [ ] **Step 5：Commit**

```bash
git add baselineprofile/src/
git commit -m "feat(baselineprofile): add BaselineProfileGenerator + StartupBenchmark (MainActivity CUJ)"
```

---

## Task 3：Compose 稳定性配置 + MainScreen 降重组

**Files:**
- Modify: `app/build.gradle.kts`（`composeCompiler {}` 报告配置）
- Modify: `app/src/main/java/com/phantom/scroll/ui/screen/MainScreen.kt`

> 开 Compose 编译器的 stability 报告（Kotlin 2.0 用 `composeCompiler { reportsDestination ... }` DSL），定位 `MainScreen` 的不稳定参数，把"三个权限状态 + 三个布尔"收敛进一个 `@Immutable` data class，减少 `PermissionItem` 重组。

- [ ] **Step 1：开启 Compose 编译器报告**

编辑 `app/build.gradle.kts`，在 `android { }` 块**之外**（与 `dependencies { }` 同级，文件末尾）追加 `composeCompiler {}` 块：
```kotlin
composeCompiler {
    // Phase 4: emit Compose stability reports to build/reports to find unstable params.
    // Reports are build artifacts (not committed); inspect to guide MainScreen refactors.
    reportsDestination.set(layout.buildDirectory.dir("compose_compiler/reports"))
    // (Optional) stability config file can force-mark packages stable; not needed here.
}
```

> ⚠️ 该 DSL 要求 Kotlin 2.0+ 的 `compose.compiler` 插件（项目已用 `kotlin = "2.0.21"`，已具备）。若编译器报 `composeCompiler` 未解析，确认 `app/build.gradle.kts` 顶部 `plugins { alias(libs.plugins.compose.compiler) }` 仍在（Phase 1 起就有，不应缺失）。

- [ ] **Step 2：验证报告生成（先跑一次，看 MainScreen 的不稳定项）**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`，且 `app/build/compose_compiler/reports/` 下生成 `*_composable.txt`（含稳定性表）与 `*_module.json`。

手工/后续：打开 `app_release-composables.txt`，定位 `MainScreen` / `PermissionItem` / `InstructionStep`，确认它们的参数稳定性。若全是 `stable` 则 Step 3 可跳过；若有 `unstable`（典型是 `() -> Unit` lambda 或 `MutableState` 误传），进 Step 3。

- [ ] **Step 3：收敛 MainScreen 状态到 @Immutable 数据类**

编辑 `app/src/main/java/com/phantom/scroll/ui/screen/MainScreen.kt`：

(a) 文件顶部（`MainScreen` 函数之前）新增一个 `@Immutable` 状态容器：
```kotlin
import androidx.compose.runtime.Immutable

/**
 * Snapshot of the permission-screen state passed to leaf composables.
 * Marked [Immutable] so Compose skips recomposition when an equal instance is passed,
 * instead of treating the aggregate as unstable.
 */
@Immutable
data class PermissionStatus(
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val batteryOptimizationIgnored: Boolean
) {
    val allGranted: Boolean get() = overlayGranted && accessibilityEnabled && batteryOptimizationIgnored
}
```

(b) `MainScreen` 内部不变（局部 state 仍用 `remember`），但把传给子组件的参数收敛。具体：`PermissionItem` 的调用点不变（它已是稳定参数：`String`/`String`/`Boolean`/`() -> Unit`）。**主要收益来自把底部 Button 的颜色/文案判断从三个 `Boolean` 收敛到读 `PermissionStatus`，避免三处独立读取触发重组范围扩散。**

实操（最小改动）：在 `MainScreen` 的 `Column { ... }` 顶部构造一次：
```kotlin
        val status = PermissionStatus(isOverlayGranted, isAccessibilityEnabled, isBatteryOptimizationIgnored)
```
然后把底部 Button 的
```kotlin
            containerColor = if (isOverlayGranted && isAccessibilityEnabled && isBatteryOptimizationIgnored)
                SuccessGreen else PhantomBlue
```
改为
```kotlin
            containerColor = if (status.allGranted) SuccessGreen else PhantomBlue
```
Button 文案同理用 `status.allGranted`。

> **取舍：** Compose 对 `Boolean` 本就判稳定，所以这一步的**实际**重组收益主要来自"单一读取点"（读 `status.allGranted` 而非三个 state），缩小重组范围。若 Step 2 报告显示 `PermissionItem`/`InstructionStep` 已是 `stable`，则本 Step 主要是"可读性 + 为后续扩展留口"，不强求。

- [ ] **Step 4：验证编译 + 单测 + release**

Run: `./gradlew :app:testDebugUnitTest assembleRelease`
Expected: `BUILD SUCCESSFUL`（56 单测不变；release 仍通过，profileinstaller 进入依赖图，baseline profile 若已生成则被打入）

- [ ] **Step 5：Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/phantom/scroll/ui/screen/MainScreen.kt
git commit -m "perf(compose): enable compiler stability reports; collapse MainScreen state to @Immutable PermissionStatus"
```

---

## Task 4：性能测量留痕（冷启动 / 首帧 / 内存）

**Files:**
- Modify: `docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md`（补实测数字）
- Modify: `README.md`（Task 5 一并改）

> 此 Task **不改代码**，只跑测量、记数字。三组数据：① 宏基准冷启动（Profile 前/后）；② `MainScreen` 首帧（来自 `StartupTimingMetric` 的 `frameDurationCpuMs`/jank）；③ 阅读期悬浮窗常驻内存（`dumpsys meminfo`，验证 Compose 运行时已不在）。

- [ ] **Step 1：宏基准冷启动（需真机/模拟器）**

Run:
```bash
./gradlew :baselineprofile:connectedReleaseBenchmark
```
Expected: `app/build/outputs/connected_android_test_additional_output/.../com.phantom.scroll.baselineprofile-startup*.json`，含 `startupNoProfile` 与 `startupWithProfile` 两组 `StartupTimingMetric`（`timeToInitialDisplayMs` / `timeToFullDisplayMs`）。

记录两组中位数（min/median/max），算出 Profile 带来的冷启动降幅 %。

> 无设备环境：跳过，在 spec 里留"待测"占位（`TBD`）。

- [ ] **Step 2：阅读期内存验证（需真机/模拟器）**

设备上启动服务、展开悬浮窗、开始自动滑动运行 ~30s，然后：
```bash
adb shell dumpsys meminfo com.phantom.scroll
```
重点看：
- `Java Heap` / `Native Heap` / `TOTAL`；
- `.dex` / `ViewRootImpl` 行；
- **关键确认**：不应出现常驻的 `androidx.compose.runtime.*` / `androidx.compose.ui.*` 大块分配（Phase 2 已移除悬浮窗 Compose；仅 `MainActivity` 期间瞬时存在）。

对比 Phase 2 前的快照（若有），确认 TOTAL 下降。把数字记入 spec。

> 无设备环境：留"待测"占位。

- [ ] **Step 3：把数字写进 spec**

编辑 `docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md`，在 §Phase 4（§4.4 测量留痕）末尾追加一节：

```markdown
### 4.4.1 实测数据（Phase 4 闸门）

> 测量设备：<填机型 / API / 是否 emulator>
> 测量日期：<YYYY-MM-DD>

| 指标 | 无 Profile (CompilationMode.None) | 有 Profile (CompilationMode.Partial) | 变化 |
|------|-----------------------------------|--------------------------------------|------|
| 冷启动 timeToInitialDisplayMs（中位） | <TBD> ms | <TBD> ms | <−X%> |
| 冷启动 timeToFullDisplayMs（中位） | <TBD> ms | <TBD> ms | <−X%> |

| 阅读期常驻内存 (dumpsys meminfo TOTAL) | Phase 2 前 | Phase 2 后（当前） | 变化 |
|----------------------------------------|-----------|--------------------|------|
| TOTAL PSS | <TBD> MB | <TBD> MB | <−X%> |
| Compose 运行时常驻 | 存在 | **已移除** | — |
```

> 若本环境无设备无法测，三个 `<TBD>` 保留并加注释"待真机测量填入"。

- [ ] **Step 4：Commit**

```bash
git add docs/superpowers/specs/2026-06-14-phantomscroll-v2-optimization-design.md
git commit -m "docs(spec): record Phase 4 measured perf data (startup / memory) placeholders"
```

---

## Task 5：热路径零分配复核 + README 更新

**Files:**
- Modify: `README.md`
- (Review only, no code change expected) `service/PhantomScrollService.kt`、`service/ScrollOrchestrator.kt`、`gesture/GestureEngine.kt`、`data/SettingsRepository.kt`

> spec §4.3 的复核项，逐条用代码事实确认（不改代码），再把结论与 V2 架构一并写进 README。

- [ ] **Step 1：热路径复核（读代码 + 记结论）**

逐条核对（无代码改动，确认即可）：

(a) **per-app 关闭零开销**：`PhantomScrollService.onAccessibilityEvent` 第一行 `perAppDetector.evaluate(...)`，其 `evaluate` 第一行 `if (!perAppEnabled) return Skip` —— 关闭时仅一次字段读 + return，零分配。✅

(b) **统计自增无装箱**：`SettingsRepository.incrementStats(swipeDelta: Long = 1, elapsedDeltaMs: Long)` 内 `_stats.update { current.copy(swipeCount = current.swipeCount + swipeDelta, ...) }` —— `Long` 基本类型运算，`data class copy` 复用，无 `java.lang.Long` 装箱。✅

(c) **手势路径零 GC**：`GestureEngine.reusablePath` 单例 `Path`，`generateGesturePath` 内 `reusablePath.reset()` 复用；`calculateGesturePoints` 是纯函数返回 `GesturePoints`（data class，栈上分配，无 `new Path()`）。✅（Phase 1 起即如此，Phase 3/4 未破坏。）

> 若任何一条发现回归（如某处 `new Path()`、或统计用了 `Number` 装箱），在本 Task 修复并补说明。预期无需改动。

- [ ] **Step 2：README 更新 —— 修失效引用 + 补 V2 架构**

`README.md` 现状（核对自仓库）：项目结构树仍引用 Phase 2 已删的 `config/ScrollConfig.kt`、`ui/overlay/FloatingPanel.kt`、`service/OverlayLifecycleOwner.kt`，且未提 Phase 1～3 的 `data/` 层、原生悬浮窗、预设/统计/方向/per-app。这些是**已失效内容**，必须修。

编辑 `README.md`：

(a) **项目结构树**：把过时的 `config/`、`FloatingPanel.kt`、`OverlayLifecycleOwner.kt` 节点删除，替换为反映当前真实结构的版本：
```
app/src/main/java/com/phantom/scroll/
├── PhantomScrollApp.kt          # Application: 通知渠道初始化
├── MainActivity.kt              # Compose 权限引导页（唯一 Compose 界面）
├── data/                        # 【V2】领域模型 + 单一真相源仓库 + DataStore 持久化
│   ├── ScrollSettings.kt / ScrollDirection.kt / AppProfile.kt
│   ├── Preset.kt / PresetRegistry.kt   # 场景预设（小说/漫画/自定义）
│   ├── ScrollStats.kt                  # 运行统计
│   ├── SettingsRepository.kt           # 全局唯一真相源（StateFlow）
│   ├── ProfileStore.kt / DataStoreProfileStore.kt
├── gesture/GestureEngine.kt     # 贝塞尔 + Bio-Noise + 方向（UP/DOWN）+ 零 GC Path 复用
├── notification/NotificationHelper.kt
├── service/
│   ├── PhantomScrollService.kt          # 单一 AccessibilityService，含 per-app 检测
│   ├── FloatingWindowController.kt      # WindowManager 编排原生悬浮窗
│   ├── FloatingOverlayView.kt（在 ui/overlay）# 原生 View（Phase 2，替代 Compose）
│   ├── ScrollOrchestrator.kt            # 滑动循环 + 统计累计 + 失败策略
│   ├── ServiceEventReceiver.kt          # 屏幕状态/通知 action
│   ├── FailurePolicy.kt / ScreenStateCoordinator.kt  # 纯逻辑（可单测）
│   ├── PerAppDetector.kt                # 纯逻辑：per-app 事件过滤（denylist/防抖）
├── ui/
│   ├── overlay/FloatingOverlayView.kt + PanelState.kt + OverlayGeometry.kt
│   ├── screen/MainScreen.kt     # Compose 权限页
│   └── theme/...
└── util/PhantomLog.kt

baselineprofile/                 # 【Phase 4】Baseline Profile 生成器（com.android.test）
```

(b) **技术栈与架构**：把"UI 框架：Jetpack Compose（悬浮窗 + 主界面）"改为"**主界面**：Jetpack Compose；**悬浮窗**：原生 View（WindowManager + Material Components，Phase 2 起移除 Compose 运行时常驻）"。

(c) **新增一节"V2 优化（四阶段）"**，简述：① data 层单一真相源 + DataStore 迁移；② 悬浮窗原生 View 重写（移除阅读期 Compose）；③ 预设/统计/方向/按 App 记忆；④ Baseline Profile + Compose 稳定性。性能数据引用 Task 4 的 spec 小节。

> README 里的 emoji 乱码（`馃専` 等）是历史 GBK/UTF-8 混编问题，**本期不修**（超出 Phase 4 范围，且风险/收益不划算）。仅修结构树与架构描述。

- [ ] **Step 3：验证编译 + 全量单测（README 改动不应影响，保险起见）**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS（56）

- [ ] **Step 4：Commit**

```bash
git add README.md
git commit -m "docs(readme): fix stale Phase-1 file refs; document V2 architecture (data layer / native overlay / presets / baseline profile)"
```

---

## Task 6：Phase 4 对齐验收 + V2 总收口

**Files:** 无代码改动（除非验收发现问题）；记录验收结果。

- [ ] **Step 1：全量单测 + Release 构建（自动闸门）**

Run: `./gradlew :app:testDebugUnitTest assembleRelease`
Expected: `BUILD SUCCESSFUL`，56 单测全绿；`app-release.apk` 生成且（若 profile 已生成）内嵌 `baseline-prof`。

- [ ] **Step 2：`:baselineprofile` 编译闸门**

Run: `./gradlew :baselineprofile:compileReleaseKotlin`
Expected: `BUILD SUCCESSFUL`（生成器与 benchmark 代码可编译）

- [ ] **Step 3：真机/模拟器验收清单（逐项过）**

**Baseline Profile：**
- [ ] `./gradlew :baselineprofile:generateReleaseBaselineProfile` 成功生成 `app/src/release/generated/baselineProfiles/baseline-prof.txt`。
- [ ] `assembleRelease` 产物内嵌 profile（`aapt dump badging` / unzip 后 `assets/dexopt/baseline.prof` 存在）。
- [ ] 宏基准对比：`startupWithProfile` 的 `timeToInitialDisplayMs` 中位 < `startupNoProfile`（记录降幅）。

**Compose 稳定性：**
- [ ] `app/build/compose_compiler/reports/` 报告生成；`MainScreen` 相关 composable 参数标注 `stable`（或经 `@Immutable PermissionStatus` 收敛）。

**热路径零分配：**
- [ ] 复核结论（Task 5 Step 1）三项全部 ✅（per-app 关闭早退、统计无装箱、Path 复用）。

**内存（Phase 2 收益确认）：**
- [ ] `dumpsys meminfo com.phantom.scroll` 阅读期无 `androidx.compose.*` 常驻大块。

**回归：**
- [ ] Phase 1～3 行为零回归：权限页正常、悬浮窗手柄/面板、Slider 实时、预设切换、统计递增/重置、方向翻转、per-app 开关/记忆/忘记、锁屏恢复、配置持久化。

- [ ] **Step 4：把验收结果记入闸门标记提交（V2 全部收口）**

```bash
git commit --allow-empty -m "chore: Phase 4 perf gate passed (baseline profile + compose stability + zero-alloc review); V2 complete

Automated: 56 unit tests green; app + :baselineprofile assemble/compile green;
release APK embeds baseline profile (when generated on a device).

Manual device/emulator items (profile generation, macrobenchmark numbers,
dumpsys memory) pending an Android SDK + adb/emulator not present in this env.
```

---

## Phase 4 完成判据

1. `./gradlew :app:testDebugUnitTest` 与 `./gradlew assembleRelease` 均 `BUILD SUCCESSFUL`；单测 **56**（不变）。
2. `:baselineprofile` 模块就绪：`BaselineProfileGenerator` 可在真机生成 profile，`assembleRelease` 自动内嵌。
3. Compose 编译器报告开启；`MainScreen` 状态收敛到 `@Immutable PermissionStatus`，重组范围不扩散。
4. 热路径零分配复核三项全过（per-app 关闭早退 / 统计无装箱 / 手势 Path 复用）。
5. README 修掉失效文件引用，补齐 V2 四阶段架构；spec §4.4.1 记录性能数字（或 TBD 占位）。
6. Phase 1～3 行为零回归。

完成后 **V2 全面优化收口**。

---

## 回退策略（Phase 4）

- 纯增量，每 Task 独立 commit，可单独 revert。
- **Baseline Profile 回退**：移除 `settings.gradle.kts` 的 `include(":baselineprofile")` + root build 的 `com.android.test`/`androidx.baselineprofile` 两条 `apply false` + app 模块的 `androidx.baselineprofile` 插件、`baselineProfile(project(...))` 配置、`profileinstaller` 依赖 + 删 `baselineprofile/` 目录。（profile 不内嵌 = 等效无 profile，行为不变，仅启动略慢。）
- **Compose 稳定性回退**：删 `composeCompiler {}` 块 + 还原 `MainScreen` 的 `PermissionStatus`（重组增多但功能不变）。
- **文档回退**：README/spec 改动 revert 即可，零代码影响。
- **数据回退**：Phase 4 不改 schema、不改运行时行为，无需数据迁移考虑。

---

## V2 跨阶段测试累计（Phase 4 后终态）

- **纯逻辑 JVM 单测**：56（Phase 1 的 SettingsRepository/FailurePolicy/ScreenStateCoordinator + Phase 2 的 OverlayGeometry + Phase 3 的 GestureEngine 方向/PresetRegistry/SettingsRepository 扩展/PerAppDetector）。
- **手工对齐清单**：Phase 2（悬浮窗对齐）、Phase 3（四项产品功能）、Phase 4（性能 + 回归）。
- **宏基准**：Phase 4 的 `StartupBenchmark`（冷启动 Profile 前/后）。
- **无 Robolectric**：全程"接口 + 假实现"策略，仅在确需 Android 框架时（View/手势注入）靠编译 + 真机手工。
