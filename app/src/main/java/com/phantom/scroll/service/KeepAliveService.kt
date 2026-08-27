package com.phantom.scroll.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.phantom.scroll.notification.NotificationHelper
import com.phantom.scroll.util.PhantomLog

/**
 * Independent foreground service dedicated to keep-alive (mirrors gkd's `StatusService`).
 *
 * **Why this is a SEPARATE service and not done inside [PhantomScrollService]**:
 * An AccessibilityService is a *bound* service whose lifecycle the system controls. When the
 * system re-binds it after a kill, calling `startForeground()` from inside `onServiceConnected()`
 * runs into Android 12+'s "background start of a foreground service" restriction and throws
 * `ForegroundServiceStartNotAllowedException`. gkd sidesteps this by keeping the resident
 * notification in a *separate* started-service (`StatusService`), started from
 * `onServiceConnected()` while the accessibility binding still counts as a foreground component —
 * which qualifies for the foreground-start exemption.
 *
 * So the division of labor is:
 * - [PhantomScrollService] — accessibility + scrolling, and a 1px `TYPE_ACCESSIBILITY_OVERLAY`
 *   window ([KeepAliveWindow]) for the overlay keep-alive layer.
 * - [KeepAliveService] (this) — a plain `Service` that promotes to foreground with a resident
 *   notification, raising the process priority so it survives memory pressure / OEM cleanup, and
 *   giving the user a visible "still alive" signal.
 *
 * The notification (id, content, actions) is built by [NotificationHelper] and shared with the
 * accessibility service so both update the same resident notification in place.
 */
class KeepAliveService : Service() {

    private val TAG = "KeepAliveService"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
    }

    /**
     * Each (re)start re-asserts the foreground notification. Returns START_STICKY so that if the
     * process is reclaimed the system attempts to restart this started-service, after which it
     * re-promotes to foreground (and the accessibility binding, driven by
     * ENABLED_ACCESSIBILITY_SERVICES, reconnects [PhantomScrollService]).
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    private fun promoteToForeground() {
        try {
            // Reflect the REAL running state (the accessibility service, if connected, is
            // initialized before this service starts) instead of hardcoding "paused" — the
            // first frame of the resident notification then never contradicts reality.
            val running = PhantomScrollService.instance?.repository?.isRunning?.value ?: false
            val notification = NotificationHelper.buildNotification(this, running)
            // Pass FOREGROUND_SERVICE_TYPE_MANIFEST (API 29+) so the system reads the type we
            // declared in the manifest (specialUse). This matches gkd's Notif.notifyService() and
            // avoids hardcoding the type bit (which would need an SDK_INT guard on older devices).
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
            } else {
                0
            }
            ServiceCompat.startForeground(this, NotificationHelper.NOTIFICATION_ID, notification, type)
            PhantomLog.d(TAG, "KeepAliveService promoted to foreground (manifest type).")
        } catch (e: Exception) {
            // If promotion fails (e.g. Android 13+ notification permission not granted, or a
            // background-start restriction), log and leave the service running as a plain started
            // service (START_STICKY) so at least START_STICKY-based restart still applies.
            PhantomLog.e(TAG, "startForeground failed: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            PhantomLog.w(TAG, "stopForeground failed: ${e.message}")
        }
        super.onDestroy()
    }

    companion object {
        /** Start (or re-promote) the keep-alive foreground service. Safe to call repeatedly. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                PhantomLog.e("KeepAliveService", "start failed: ${e.message}", e)
            }
        }

        /** Stop the keep-alive foreground service and remove its resident notification. */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                PhantomLog.w("KeepAliveService", "stop failed: ${e.message}")
            }
        }
    }
}
