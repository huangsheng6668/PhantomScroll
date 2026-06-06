# PhantomScroll

👻 **PhantomScroll** 是一款专为小说和漫画阅读设计的**高性能、零卡顿** Android 自动化辅助工具。通过无障碍服务（Accessibility Service）模拟高度逼真的拟人化跨应用**纵向滑动**，辅助用户解放双手，享受丝滑的自动阅读体验。

---

## 🌟 核心特性

1. **拟人化滑动引擎 (Human-like Gesture Engine)**
   - **二阶贝塞尔曲线**：在起点与终点之间引入带随机水平偏移的控制点，模拟真实手指滑动的微小弧度。
   - **非对称速度曲线**：使用自定义三次贝塞尔插值器 `cubic-bezier(0.25, 0.1, 0.25, 1.0)`，实现“加速快而急促、减速长而平缓”的真实人类手指运动特征。
   - **生物拟人化噪声 (Bio-Noise)**：滑动的总距离、持续时间以及两次滑动之间的间隔时间，均在用户设定值的基础上动态加入 $\pm 5\% \sim \pm 10\%$ 的正态分布随机浮动；轨迹采样点间加入像素级微抖动，完美规避防挂机检测。

2. **零 GC 消耗与极限性能优化**
   - **主/后台线程完全隔离**：UI 线程（`Dispatchers.Main.immediate`）仅负责悬浮窗的拖拽手势响应与吸附动画；贝塞尔曲线轨迹计算、随机噪声及插值完全异步在 `Dispatchers.Default` 进行。
   - **对象池复用 (Zero GC)**：在滑动循环中复用同一个 `Path` 对象（每次计算前强制调用 `reset()`），预分配 `FloatArray` 存储采样点坐标，避免频繁垃圾回收（GC）导致 UI 掉帧（Jank）。
   - **无阻挂起定时器**：使用协程的 `delay()` 挂起函数替代传统的 Timer 线程，保证等待期间 CPU 核心可休眠，极致省电。

3. **智能边缘吸附悬浮窗 (Compose Snapping Floating Panel)**
   - 使用系统 `WindowManager` 动态注入全局悬浮窗，基于 Jetpack Compose 构建极致轻量的暗黑高对比度 UI。
   - **吸附状态机 (State Machine)**：支持 `Expanded`（展开面板）、`Snapping`（吸附中动画）、`Collapsed`（边缘折叠手柄）三种状态。
   - **智能边缘靠吸**：拖拽结束时自动计算 X 坐标，平滑吸附至屏幕最近的一侧边缘，并自动折叠为半透明迷你手柄，最大程度减少对阅读内容的遮挡。

4. **前台通知与服务保活**
   - 适配 Android 8.0+ 的 `NotificationChannel` 与 Android 14 前台服务类型。
   - 通知栏实时显示当前状态（`● 滑动中` / `○ 已暂停`），并提供快捷操作按钮，支持一键“暂停/恢复”或“退出服务”。

5. **生命周期与锁屏管理**
   - 注册 BroadcastReceiver 监听 `ACTION_SCREEN_OFF` 和 `ACTION_USER_PRESENT`。
   - 屏幕熄灭或锁屏时**自动暂停**滑动，亮屏解锁后**自动恢复**先前状态，保护手机电池与屏幕寿命。

---

## 🛠️ 技术栈

* **开发语言**：Kotlin (Coroutines + Flow)
* **UI 框架**：Jetpack Compose (ConstraintLayout, PointerInput Dragging, Animatable Animation)
* **系统服务**：AccessibilityService, WindowManager, BroadcastReceiver, Foreground Service
* **兼容规范**：
  - Compile SDK: `34` (Android 14)
  - Target SDK: `34` (Android 14)
  - Min SDK: `26` (Android 8.0)

---

## 📂 项目结构

```
app/src/main/java/com/phantom/scroll/
│
├── MainActivity.kt          # 引导式权限检查与使用说明主界面
├── PhantomScrollApp.kt      # Application 初始化入口
│
├── config/
│   └── ScrollConfig.kt      # 全局滑动配置流 (StateFlow) 与持久化存储
│
├── gesture/
│   └── GestureEngine.kt     # 手势计算核心：贝塞尔曲线、自定义插值器、Bio-Noise、对象池
│
├── notification/
│   └── NotificationHelper.kt# 前台通知通道与通知栏快捷控制器
│
├── service/
│   ├── PhantomScrollService.kt # 核心无障碍服务，合并了 WindowManager 与滑动状态分发
│   └── OverlayLifecycleOwner.kt# 悬浮窗 Compose ViewTree 生命周期持有者
│
└── ui/
    ├── overlay/
    │   └── FloatingPanel.kt # 悬浮窗 Compose UI，手势拦截、吸附状态机实现
    ├── screen/
    │   └── MainScreen.kt    # 权限引导与主应用界面 Compose UI
    └── theme/
        ├── Color.kt
        ├── Theme.kt         # 极致暗黑透明阅读主题
        └── Type.kt
```

---

## 🚀 核心算法剖析

### 1. 拟人化二阶贝塞尔曲线
在滑动起点 $P_0(\text{startX}, \text{startY})$ 和终点 $P_2(\text{startX}, \text{endY})$ 之间，引入带有高斯分布随机水平偏移的控制点 $P_1(\text{controlX}, \text{controlY})$ 进行二次插值：
$$B(t) = (1-t)^2 P_0 + 2t(1-t) P_1 + t^2 P_2, \quad t \in [0, 1]$$
使得滑动轨迹呈现人类手指操作时的自然弧度，而不是机器般生硬的绝对直线。

### 2. 非对称速度插值器 (Cubic Bezier Solver)
传统 Android 插值器多为对称速度变化。本系统内置了 Newton-Raphson 求解器，实现了 `cubic-bezier(0.25, 0.1, 0.25, 1.0)` 贝塞尔曲线插值：
- 前 25% 的时间快速释放手指冲力，模拟手指刚触碰屏幕并快速向下滑动的阶段。
- 后 75% 的时间进行超长的缓和减速，完全符合人手物理摩擦力自然停滞的特征。

---

## 📲 使用说明与权限引导

为了使 PhantomScroll 正常运作，需要授予以下系统权限（已在 `MainActivity` 中提供向导）：

1. **悬浮窗权限 (SYSTEM_ALERT_WINDOW)**：用于在小说/漫画 App 上层显示控制悬浮窗。
2. **无障碍服务权限 (BIND_ACCESSIBILITY_SERVICE)**：用于向系统注入自动模拟滑动事件。**注意**：本服务仅用于注入手势，声明 `canRetrieveWindowContent="false"`，绝对不读取或保存任何用户的屏幕隐私内容。
3. **通知权限 (POST_NOTIFICATIONS)**：用于显示保活通知栏以及快捷切挂起/恢复状态。
4. **忽略电池优化**（可选推荐）：防止系统在后台休眠无障碍服务，保障翻页的连续稳定性。

---

## 📦 编译与运行

1. 克隆本项目到本地。
2. 使用 Android Studio 打开。
3. 连接 Android 8.0 (API 26) 以上的真机，并开启开发者调试。
4. 编译并运行 `app` 模块。
5. 按照 App 界面引导开启权限，在悬浮窗中调整参数，点击 `▶` 开始自动丝滑阅读。
