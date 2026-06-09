# PhantomScroll 全面优化设计文档

> 日期：2026-06-09
> 状态：已批准，待实施
> 方案：渐进式分层优化（方案 A）

---

## 概述

对 PhantomScroll Android 自动阅读翻页工具进行全面优化，涵盖安全、构建、架构、性能、可靠性、体验和健壮性七个维度。采用四阶段分层推进策略，每阶段可独立验证和回退。

**项目现状**：Kotlin + Jetpack Compose + Coroutines，约 22 个源文件，零测试覆盖，compileSdk/targetSdk 34。

**目标状态**：安全构建流程、清晰架构分层、高性能低 I/O、可靠状态管理、良好用户体验。

---

## Phase 1：安全与构建优化

### 1.1 签名密码外置

**问题**：`app/build.gradle.kts` 明文硬编码签名密码 `phantom123`，存在于 Git 历史中。

**方案**：

1. 创建 `keystore.properties`（根目录，加入 `.gitignore`）：
   ```properties
   storeFile=phantomscroll.jks
   storePassword=phantom123
   keyAlias=phantomscroll
   keyPassword=phantom123
   ```

2. 创建 `keystore.properties.example`（提交到 Git，不含真实密码）：
   ```properties
   storeFile=phantomscroll.jks
   storePassword=YOUR_KEYSTORE_PASSWORD
   keyAlias=phantomscroll
   keyPassword=YOUR_KEY_PASSWORD
   ```

3. 修改 `app/build.gradle.kts` 的 `signingConfigs` 块，从文件读取：
   ```kotlin
   val keystoreProps = java.util.Properties()
   val propsFile = rootProject.file("keystore.properties")
   if (propsFile.exists()) keystoreProps.load(propsFile.inputStream())

   signingConfigs {
       create("release") {
           storeFile = file(keystoreProps.getProperty("storeFile", "phantomscroll.jks"))
           storePassword = keystoreProps.getProperty("storePassword", "")
           keyAlias = keystoreProps.getProperty("keyAlias", "phantomscroll")
           keyPassword = keystoreProps.getProperty("keyPassword", "")
       }
   }
   ```

4. 更新 `.gitignore` 添加 `keystore.properties`。

### 1.2 开启 R8 混淆 + 创建 ProGuard 规则

**问题**：Release 构建未启用代码缩减和混淆；`proguard-rules.pro` 文件不存在。

**方案**：

1. `app/build.gradle.kts` release 构建类型修改：
   ```kotlin
   release {
       isMinifyEnabled = true
       isShrinkResources = true
       proguardFiles(
           getDefaultProguardFile("proguard-android-optimize.txt"),
           "proguard-rules.pro"
       )
       signingConfig = signingConfigs.getByName("release")
   }
   ```

2. 创建 `app/proguard-rules.pro`：
   ```proguard
   # PhantomScroll ProGuard Rules

   # Keep AccessibilityService (system reflection)
   -keep class com.phantom.scroll.service.PhantomScrollService extends android.accessibilityservice.AccessibilityService { *; }

   # Keep Application class
   -keep class com.phantom.scroll.PhantomScrollApp extends android.app.Application { *; }

   # Keep Compose-related (usually handled by BOM, but ensure safety)
   -keep class * extends androidx.compose.runtime.Composable { *; }

   # Keep data classes used across process boundaries
   -keep class com.phantom.scroll.config.ConfigSnapshot { *; }

   # Kotlin coroutines
   -keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
   -keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

   # General Android
   -keepclassmembers class * {
       public <init>(...);
   }
   -keepattributes *Annotation*
   -keepattributes SourceFile,LineNumberTable
   ```

### 1.3 升级 SDK 到 Android 15 (API 35)

**方案**：

1. `app/build.gradle.kts` 更新：
   ```kotlin
   compileSdk = 35
   defaultConfig {
       targetSdk = 35
   }
   ```

2. Android 15 变更适配：
   - **Edge-to-edge 强制开启**：App 已使用暗黑全屏主题，无需额外适配。
   - **前台服务类型**：`dataSync` 在 Android 15 上仍可用，但需验证是否需要声明 `FOREGROUND_SERVICE_SPECIAL_USE` 权限。当前场景（持续后台手势注入）最匹配 `specialUse` 类型。如果改为 `specialUse`，需在 `AndroidManifest.xml` 中添加 `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="auto_scroll_accessibility_gesture_injection"/>` 并声明 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>`。**决策：保持 `dataSync` 不变，Android 15 上 `dataSync` 仍然合法且不需要额外权限。**
   - **依赖更新**：Compose BOM 升级到兼容 API 35 的最新稳定版本，Lifecycle 库同步更新。

3. 更新 `gradle/libs.versions.toml`（如使用版本目录）或 `build.gradle.kts` 中的依赖版本号。

### 1.4 .gitignore 更新

添加以下条目：
```
keystore.properties
.codegraph/
```

### Phase 1 改动文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `app/build.gradle.kts` |
| 修改 | `.gitignore` |
| 新建 | `keystore.properties` |
| 新建 | `keystore.properties.example` |
| 新建 | `app/proguard-rules.pro` |
| 可能修改 | `gradle/libs.versions.toml`（依赖版本） |

---

## Phase 2：架构拆分

### 2.1 拆分 PhantomScrollService

**问题**：`PhantomScrollService` 是 God Class（~430行），混合 5 种职责。

**目标架构**：

```
PhantomScrollService (薄协调层 ~80行)
│   职责：注册/注销子模块、生命周期转发
│
├── FloatingWindowController (~150行)
│   ├── setupWindow()        — 创建 ComposeView、WindowManager 添加
│   ├── removeWindow()       — 安全移除、生命周期清理
│   ├── updatePosition()     — 更新悬浮窗坐标
│   ├── updateLayoutParams() — 根据面板状态动态更新 flags
│   └── startPermissionPolling() — 覆盖权限轮询（含超时）
│
├── ScrollOrchestrator (~120行)
│   ├── start() / stop()     — 控制滑动循环
│   ├── scrollingLoop()      — 从 Service 原样搬入的滑动循环
│   ├── 手势分发             — dispatchGesture + GestureResultCallback
│   └── 失败重试             — consecutiveFailures 计数与自动暂停
│   持有：GestureEngine 实例
│   接收：ScrollConfig 引用
│
└── ServiceEventReceiver (~60行)
    ├── register()   — 注册 SCREEN_OFF / USER_PRESENT / 通知动作
    ├── unregister() — 安全注销
    ├── screenReceiver    — 锁屏暂停 / 解锁恢复
    └── notificationReceiver — 通知栏快捷操作
```

### 2.2 模块间通信

**原则**：子模块之间不互相引用，全部通过 `PhantomScrollService` 协调。

**通信方式**：
- 读取共享状态：子模块持有 `ScrollConfig` 引用，读取 `StateFlow`（如 `config.isRunning`）
- 状态变更：子模块直接修改 `ScrollConfig` 的 `MutableStateFlow`（与现有方式一致）
- 生命周期通知：`PhantomScrollService` 在 `onServiceConnected`/`onDestroy` 中调用子模块的初始化/销毁方法

**接口设计**：

```kotlin
// FloatingWindowController
class FloatingWindowController(
    private val service: PhantomScrollService,
    private val config: ScrollConfig,
    private val panelStateFlow: MutableStateFlow<PanelState>
) {
    fun setup()
    fun remove()
    fun updatePosition(x: Int, y: Int)
    fun updateLayoutParams(state: PanelState)
}

// ScrollOrchestrator
class ScrollOrchestrator(
    private val service: PhantomScrollService,
    private val config: ScrollConfig,
    private val scope: CoroutineScope
) {
    private val gestureEngine = GestureEngine()
    fun startLoop()
    fun stopLoop()
}

// ServiceEventReceiver
class ServiceEventReceiver(
    private val service: PhantomScrollService,
    private val config: ScrollConfig,
    private val scope: CoroutineScope
) {
    fun register()
    fun unregister()
}
```

### 2.3 包结构

```
service/
├── PhantomScrollService.kt       # 薄协调层（重写）
├── FloatingWindowController.kt    # 新建
├── ScrollOrchestrator.kt          # 新建
├── ServiceEventReceiver.kt        # 新建
└── OverlayLifecycleOwner.kt       # 不变
```

### 2.4 不改动的文件

以下文件在 Phase 2 中零改动：
- `GestureEngine.kt`
- `ScrollConfig.kt`
- `FloatingPanel.kt`
- `MainScreen.kt`
- `MainActivity.kt`
- `NotificationHelper.kt`
- `PhantomScrollApp.kt`
- UI Theme 相关文件

### Phase 2 改动文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `app/.../service/FloatingWindowController.kt` |
| 新建 | `app/.../service/ScrollOrchestrator.kt` |
| 新建 | `app/.../service/ServiceEventReceiver.kt` |
| 重写 | `app/.../service/PhantomScrollService.kt` |

---

## Phase 3：性能与可靠性优化

### 3.1 SharedPreferences 写入节流

**问题**：Slider 每次拖动触发数十次 `prefs.edit().apply()`，产生高频磁盘 I/O。

**方案**：使用 `debounce(500)` + `drop(1)` 优化三个 Flow 收集器：

```kotlin
// ScrollConfig.kt — 修改 init 块
init {
    scope.launch {
        scrollDuration
            .drop(1)
            .debounce(500)
            .collect { duration ->
                prefs.edit().putLong("scroll_duration", duration).apply()
            }
    }
    scope.launch {
        scrollInterval
            .drop(1)
            .debounce(500)
            .collect { interval ->
                prefs.edit().putLong("scroll_interval", interval).apply()
            }
    }
    scope.launch {
        scrollDistanceRatio
            .drop(1)
            .debounce(500)
            .collect { ratio ->
                prefs.edit().putFloat("scroll_distance_ratio", ratio).apply()
            }
    }
}
```

**效果**：运行时值通过 `StateFlow` 实时传递（零延迟），仅磁盘持久化被延迟至停止调整后 500ms。

### 3.2 锁屏状态恢复修复

**问题**：熄屏前若用户已手动暂停，亮屏后会错误地自动恢复滑动。

**方案**：在 `ServiceEventReceiver` 中引入 `wasRunningBeforeScreenOff` 标记：

```kotlin
private var wasRunningBeforeScreenOff = false

// ACTION_SCREEN_OFF 处理
wasRunningBeforeScreenOff = config.isRunning.value
config.isRunning.value = false

// ACTION_USER_PRESENT 处理
if (wasRunningBeforeScreenOff) {
    config.isRunning.value = true
}
wasRunningBeforeScreenOff = false
```

### 3.3 GestureEngine 去除 synchronized

**问题**：`generateGesturePath` 使用 `synchronized(reusablePath)` 保护路径对象池，但调用链保证串行访问。

**方案**：移除 `synchronized` 块，直接操作 `reusablePath`。

**安全论证**：`startScrollingLoop` 是单协程顺序循环 → `generateGesturePath` 是 `suspend` 函数 → `withContext(Dispatchers.Default)` 中的代码在 `await` 完成前不会再次进入 → 不存在并发访问 `reusablePath` 的可能。

### 3.4 移除冗余 NotificationChannel 创建

**问题**：`NotificationHelper.createChannel()` 在 `PhantomScrollApp.onCreate()` 和 `PhantomScrollService.onServiceConnected()` 中重复调用。

**方案**：移除 `PhantomScrollService`（Phase 2 重写后）中的 `createChannel` 调用，仅保留 `PhantomScrollApp` 中的初始化。

### 3.5 MainScreen 权限状态自动刷新

**问题**：从系统设置返回后权限状态不更新，需手动点击刷新按钮。

**方案**：使用 `LifecycleEventObserver` 在 `ON_RESUME` 时自动刷新：

```kotlin
val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) checkPermissions()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

### Phase 3 改动文件清单

| 操作 | 文件 |
|------|------|
| 修改 | `app/.../config/ScrollConfig.kt` |
| 修改 | `app/.../service/ServiceEventReceiver.kt`（Phase 2 新建） |
| 修改 | `app/.../gesture/GestureEngine.kt` |
| 修改 | `app/.../service/PhantomScrollService.kt`（Phase 2 重写版） |
| 修改 | `app/.../ui/screen/MainScreen.kt` |

---

## Phase 4：体验与健壮性提升

### 4.1 日志框架化

**问题**：约 15 处 `Log.d`/`Log.w` 调用在 Release 构建中仍然输出。

**方案**：创建 `util/PhantomLog.kt`，通过 `BuildConfig.DEBUG` 门控：

```kotlin
package com.phantom.scroll.util

import com.phantom.scroll.BuildConfig

object PhantomLog {
    private const val PREFIX = "PhantomScroll"

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("$PREFIX:$tag", msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) android.util.Log.w("$PREFIX:$tag", msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e("$PREFIX:$tag", msg, t)
    }
}
```

**替换规则**：
- `Log.d(TAG, msg)` → `PhantomLog.d("Service", msg)`
- `Log.w(TAG, msg)` → `PhantomLog.w("Service", msg)`
- `Log.e(TAG, msg, e)` → `PhantomLog.e("Service", msg, e)`
- `android.util.Log.d("FloatingPanel", msg)` → `PhantomLog.d("FloatingPanel", msg)`

**保留 `e` 级别始终输出**：用于异常排查和崩溃分析。

### 4.2 基础单元测试

**方案**：为两个纯逻辑模块添加 JUnit 单元测试。

**GestureEngineTest**：
- `addBioNoise` 返回值在 `baseValue * (1 ± ratio * 2)` 范围内
- `addBioNoise` 多次调用返回不同值（随机性验证）
- `generateGesturePath` 返回非空 GestureResult
- `generateGesturePath` 返回的 duration 在 `[200, 1500]` 范围内

**ScrollConfigTest**：
- `snapshot()` 正确捕获当前三个配置值
- 初始值从 SharedPreferences 正确加载（需 mock Context）

**测试依赖**（添加到 `app/build.gradle.kts`）：
```kotlin
testImplementation("junit:junit:4.13.2")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
testImplementation("org.mockito:mockito-core:5.12.0")
testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
```

**目录结构**：
```
app/src/test/java/com/phantom/scroll/
├── gesture/
│   └── GestureEngineTest.kt
└── config/
    └── ScrollConfigTest.kt
```

### 4.3 覆盖权限轮询超时保护

**问题**：`startOverlayPermissionPolling` 无限循环，若用户始终不授权则协程永远运行。

**方案**：在 `FloatingWindowController` 中添加 5 分钟（300 次 × 1s）超时：

```kotlin
private fun startPermissionPolling() {
    scope.launch {
        var attempts = 0
        val maxAttempts = 300
        while (!isWindowAdded && attempts < maxAttempts) {
            delay(1000)
            attempts++
            if (Settings.canDrawOverlays(service)) {
                setup()
                break
            }
        }
        if (attempts >= maxAttempts) {
            PhantomLog.w("FloatingWindow", "Overlay permission polling timed out")
        }
    }
}
```

### 4.4 连续失败自动暂停的用户反馈

**问题**：连续 3 次手势失败自动暂停后，用户无感知。

**方案**：在 `ScrollOrchestrator` 的失败处理中添加 Toast 通知：

```kotlin
if (consecutiveFailures >= 3) {
    config.isRunning.value = false
    consecutiveFailures = 0
    withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(
            service,
            "⚠ 连续手势失败，已自动暂停",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}
```

### Phase 4 改动文件清单

| 操作 | 文件 |
|------|------|
| 新建 | `app/.../util/PhantomLog.kt` |
| 新建 | `app/src/test/.../gesture/GestureEngineTest.kt` |
| 新建 | `app/src/test/.../config/ScrollConfigTest.kt` |
| 修改 | `app/.../service/FloatingWindowController.kt`（Phase 2 新建） |
| 修改 | `app/.../service/ScrollOrchestrator.kt`（Phase 2 新建） |
| 修改 | `app/.../service/ServiceEventReceiver.kt`（Phase 2 新建） |
| 修改 | `app/.../service/PhantomScrollService.kt` |
| 修改 | `app/.../gesture/GestureEngine.kt` |
| 修改 | `app/.../ui/overlay/FloatingPanel.kt` |
| 修改 | `app/build.gradle.kts`（测试依赖） |

---

## 总改动范围汇总

| 阶段 | 新建 | 修改 | 风险 |
|------|------|------|------|
| Phase 1 | 3 文件 | 2 文件 | 低 |
| Phase 2 | 3 文件 | 1 文件（重写） | 中 |
| Phase 3 | 0 | 5 文件 | 低 |
| Phase 4 | 3 文件 | 5 文件 | 低 |

## 不涉及的改动

- 不改动 UI 主题和视觉样式
- 不改动 `OverlayLifecycleOwner`
- 不改动 `PanelState` 枚举
- 不改动 `NotificationHelper` 的通知内容
- 不引入新的第三方库（仅添加标准测试依赖）
- 不改动 `AndroidManifest.xml` 中的权限声明和 Service 声明（SDK 升级除外）
