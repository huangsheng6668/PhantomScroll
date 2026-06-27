package com.phantom.scroll.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.phantom.scroll.util.PhantomLog

/**
 * 1×1 像素 `TYPE_ACCESSIBILITY_OVERLAY` 保活窗（参考 gkd / 李跳跳）。
 *
 * **原理**：AccessibilityService 专属的 overlay 窗口类型（type 2032）让进程始终拥有一个
 * "可见窗口"，从而降低 [oom_adj]，规避 LowMemoryKiller 与国产 ROM 的后台清理。它通过
 * AccessibilityService 的 context 调用 [WindowManager.addView] 即可添加，**无需**
 * `SYSTEM_ALERT_WINDOW` 悬浮窗权限，也不依赖任何前台服务通知。
 *
 * **与 [FloatingWindowController] 的关系**：完全独立。交互式控制面板仍用
 * `TYPE_APPLICATION_OVERLAY`（需要悬浮窗权限、有拖拽/手势逻辑），本类只负责一个零感知的
 * 保活窗口，二者互不感知、互不影响。
 *
 * **关键 flags 取值理由**（规避已知副作用）：
 * - 尺寸用 1px 而非 0px：0px 窗口会让系统没有 FocusWindow，导致 key 事件无法分发（见小米
 *   澎湃 OS 0 像素窗口适配说明）。
 * - [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE]：让本窗**不**成为 TouchedWindow，
 *   绝不拦截或影响其他窗口的触摸，规避"1 像素窗口抢触摸"的副作用。
 * - [WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS]：允许 1px 窗口落在屏幕角落，避免
 *   被状态栏裁掉而丢失。
 */
class KeepAliveWindow(private val context: Context) {

    private val TAG = "KeepAliveWindow"

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var keepAliveView: View? = null
    private var isAdded = false

    /** 添加保活窗。可重复调用，已存在时为空操作。失败仅记日志，绝不抛出。 */
    fun show() {
        if (isAdded) return
        try {
            val view = View(context).apply {
                // 全透明背景，视觉上完全不存在。
                background = ColorDrawable(Color.TRANSPARENT)
            }
            val params = WindowManager.LayoutParams(
                1, // width: 1px
                1, // height: 1px
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            windowManager.addView(view, params)
            keepAliveView = view
            isAdded = true
            PhantomLog.d(TAG, "Keep-alive overlay window added (TYPE_ACCESSIBILITY_OVERLAY).")
        } catch (e: Exception) {
            // 保活只是"尽力而为"：即使添加失败也不阻塞服务的其它功能。
            PhantomLog.e(TAG, "Failed to add keep-alive overlay window: ${e.message}", e)
        }
    }

    /** 兜底重添加：用于 onConfigurationChanged 等系统偶发移除窗口的场景。 */
    fun ensureShown() {
        if (!isAdded) show()
    }

    /** 移除保活窗。可重复调用，不存在时为空操作。失败仅记日志，绝不抛出。 */
    fun hide() {
        val view = keepAliveView ?: return
        try {
            if (isAdded) windowManager.removeView(view)
        } catch (e: Exception) {
            PhantomLog.e(TAG, "Failed to remove keep-alive overlay window: ${e.message}", e)
        } finally {
            keepAliveView = null
            isAdded = false
        }
    }
}
