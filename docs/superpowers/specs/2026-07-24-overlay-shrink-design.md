# 悬浮窗瘦身 + 移除统计功能 设计文档

> 日期：2026-07-24
> 状态：已批准，待实施
> 背景：真机测试发现悬浮窗面板占屏宽 ~80% / 屏高 ~35%，明显偏大（240dp 固定宽在高密度屏上渲染过宽）。用户决定：面板宽度改为按屏幕宽 40% 动态计算，并彻底移除滑动次数/时长的统计功能。

---

## 目标与非目标

### 目标
1. **面板宽度动态化**：从固定 `240dp` 改为 `屏幕宽度 × 0.40`，设上下限（min 140dp, max 180dp）防止极端屏幕。根治"换手机变大/变小"。
2. **移除统计功能**（彻底）：删面板显示 + 删 `incrementStats`/`resetStats` 调用 + 删 `stats` StateFlow + 删 `ScrollStats` 模型与持久化 + 删重置按钮。删干净，不留死代码。
3. **整体瘦身**：宽度缩小后，内部 padding/字号按比例协调下调（保持视觉比例，不破坏信息密度）。

### 非目标
- 不改悬浮窗的交互逻辑（拖拽/吸附/折叠/展开/方向/per-app）。
- 不改主题配色。
- 不改 `MainActivity`/`MainScreen`。
- 不改 DataStore 已有 key 的语义（统计 key `stats.swipe`/`stats.elapsed` 自然废弃，不做迁移清理——旧值留在磁盘无害）。

---

## 第 1 节：宽度动态化

### OverlayGeometry 改动
- 删除 `PANEL_WIDTH_DP = 240` 常量。
- 新增：
  ```kotlin
  const val PANEL_WIDTH_RATIO = 0.40f        // 面板宽 = 屏幕宽 × 0.40
  const val PANEL_WIDTH_MIN_DP = 140          // 下限（小屏防过窄）
  const val PANEL_WIDTH_MAX_DP = 180          // 上限（大屏防过宽）
  /** 根据屏幕宽(px)与 density 计算面板宽(px)。纯函数，可单测。 */
  fun panelWidthPx(screenWidthPx: Int, density: Float): Int {
      val ratioBased = (screenWidthPx * PANEL_WIDTH_RATIO).toInt()
      val minPx = (PANEL_WIDTH_MIN_DP * density).toInt()
      val maxPx = (PANEL_WIDTH_MAX_DP * density).toInt()
      return ratioBased.coerceIn(minPx, maxPx)
  }
  ```
- 保留 `COLLAPSED_WIDTH_DP = 20`（气泡宽度，与面板宽无关，不变）。

### FloatingOverlayView 适配
- 当前 `panelWidthPx()` 返回固定 `(PANEL_WIDTH_DP * density)`。改为 `OverlayGeometry.panelWidthPx(repo.screenWidth.value, density)`。
- 屏幕宽度变化（旋屏）时面板宽自动重算（现有 `combine(screenWidth, screenHeight).collect { repositionToBounds }` 已驱动重定位，需确认它也触发宽度重算 + 重新 `layoutParams.width`）。

### 布局适配
- `overlay_panel.xml` 根 `LinearLayout` 的 `android:layout_width="240dp"` 改为 `wrap_content`（实际宽度由 FloatingOverlayView 在代码里设置 `layoutParams.width`）。**注意**：需确认 WindowManager 的 LayoutParams 与内部根 View 宽度协同——面板根用 wrap_content，由 controller 设外层 width。

> 实现注意：WindowManager 的 `LayoutParams.width` 控制窗口宽；内部根 LinearLayout 用 wrap_content 自适应。需在 FloatingOverlayView 里设 `panelRoot.layoutParams.width = panelWidthPx()` 并在屏幕变化时更新。具体边界在实现时验证。

---

## 第 2 节：移除统计功能

### 删除清单（主代码）
| 文件 | 删除内容 |
|------|---------|
| `data/ScrollStats.kt` | **整个文件删除** |
| `data/SettingsRepository.kt` | `_stats`/`stats` StateFlow、`incrementStats()`、`resetStats()`、`PersistableSnapshot.stats` 字段、init 里 `store.loadStats()`、`savePersistable` 里 `store.saveStats()` |
| `data/SettingsReducer.kt` | `LoadedState.stats` 字段、`reconcileInitial` 的 stats 入参/赋值 |
| `data/ProfileStore.kt` | `loadStats()`、`saveStats()` 接口方法 |
| `data/DataStoreProfileStore.kt` | `Keys.STATS_SWIPE`/`STATS_ELAPSED`、`loadStats()`、`saveStats()` 实现 |
| `service/ScrollOrchestrator.kt:122` | `repository.incrementStats(...)` 调用（`delay(noiseInterval)` 保留，滑动循环不受影响） |
| `ui/overlay/FloatingOverlayView.kt` | `metricCount`/`metricElapsed` 字段、findViewById、`repo.stats.collect`、`applyStats()` 方法、重置按钮 `resetBtn` 及其点击监听（`:165-167`）、import `ScrollStats` |
| `res/layout/overlay_panel.xml` | 整个 "Metric section"（`:129-199`）、重置按钮 `reset_btn`（`:314-330`） |

### 删除清单（测试）
| 文件 | 删除内容 |
|------|---------|
| `data/FakeProfileStore.kt` | `stats` 字段、`loadStats()`、`saveStats()` |
| `data/SettingsReducerTest.kt` | `LoadedState` 构造里的 `ScrollStats.ZERO` 参数（2 处：`:125,135`） |
| `data/SettingsRepositoryTest.kt` | `stats_increment_and_reset`、`stats_persist_after_flush` 两个测试方法 |

### 保留
- `reset_btn` 的图标按钮删除后，操作栏只剩"开始/暂停滑动"主按钮，改为占满整行（`layout_weight=1` 已是，去掉 reset 后自然占满，去掉 `marginStart` 间距）。
- `ProfileKeyParsingTest.kt:27` 的 `profilePackageFromKey("stats.swipe")` 断言——它测的是"stats.swipe 不是 profile key"的解析逻辑，统计 key 废弃后这个断言仍成立（解析器仍需拒绝非 profile 前缀的 key），**保留**。

---

## 第 3 节：整体瘦身（内部尺寸协调）

面板从 ~240dp 缩到 ~160dp（约 1/3 缩减），内部按比例下调：
- 根 padding：6dp → 5dp
- Header 图标：32dp → 26dp；标题 14sp → 13sp
- 状态行字号 10.5sp → 10sp
- 参数 cell padding：9dp → 7dp；cell 内字号 11sp/12sp → 10sp/11sp
- per-app 行 padding 9dp → 7dp；字号 12sp → 11sp
- 操作按钮高 40dp → 36dp；字号 13sp → 12sp；cornerRadius 11dp → 9dp

> 这些是经验性微调，实施后在真机看效果再细调。核心是宽度动态化 + 删统计行（直接减少高度）。

---

## 第 4 节：测试

- **新增 `OverlayGeometryTest` 扩展**：`panelWidthPx` 在不同 screenWidth/density 下的计算 + 上下限 clamp（如 1080px/3.0density → 432 → clamp 到 180dp=540px？需核算：0.40×1080=432, min=140×3=420, max=180×3=540 → 432 落在区间内 = 432px ≈ 144dp）。
- 现有 `OverlayGeometryTest` 的 `PANEL_WIDTH_DP` 引用需更新（如果有）。
- 删除统计相关测试（见第 2 节）。
- 全量 `:app:testDebugUnitTest` 绿。

---

## 验收

1. `:app:testDebugUnitTest` 全绿。
2. `:app:assembleRelease` 绿。
3. 真机：面板宽度约占屏宽 40%，高度因删统计行明显下降；换不同密度设备比例稳定。
4. 无统计相关死代码（grep `ScrollStats`/`incrementStats`/`resetStats`/`metric_count` 为空）。
5. 滑动循环、方向、per-app、拖拽/吸附/折叠 交互不受影响。

---

## 风险与回退

- **风险**：宽度动态化涉及 WindowManager LayoutParams 与内部 View 宽度协同，可能在某些设备上首次布局闪动。缓解：旋屏重定位逻辑已存在，复用即可。
- **回退**：单 commit，可整体 revert。统计 key 废弃但不清理，无数据风险。
