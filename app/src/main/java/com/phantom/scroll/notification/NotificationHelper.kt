package com.phantom.scroll.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phantom.scroll.MainActivity
import com.phantom.scroll.R

object NotificationHelper {
    const val CHANNEL_ID = "phantom_scroll_service_channel"
    const val NOTIFICATION_ID = 4777

    // Broadcast actions for notification control buttons
    const val ACTION_TOGGLE = "com.phantom.scroll.ACTION_TOGGLE"
    const val ACTION_STOP = "com.phantom.scroll.ACTION_STOP"

    fun createChannel(context: Context) {
        val name = "PhantomScroll 服务"
        val descriptionText = "PhantomScroll 自动滑动的状态通知"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            setShowBadge(false)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    fun showNotification(context: Context, isRunning: Boolean) {
        // After the service calls startForeground(id, notification), refreshing with the SAME id
        // via notify() updates the resident notification in place (no flicker) while keeping the
        // service's foreground priority intact. The id MUST match the one passed to startForeground.
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(context, isRunning))
    }

    fun cancelNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun buildNotification(context: Context, isRunning: Boolean): Notification {
        // PendingIntent: click notification → open MainActivity
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent: toggle play/pause via broadcast
        // AccessibilityService cannot be started via startService(), so we use broadcasts instead
        val toggleIntent = Intent(ACTION_TOGGLE).apply {
            setPackage(context.packageName)
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // PendingIntent: stop service via broadcast
        val stopIntent = Intent(ACTION_STOP).apply {
            setPackage(context.packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) "● 滑动中" else "○ 已暂停"
        val actionText = if (isRunning) "暂停" else "开始"

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("PhantomScroll")
            .setContentText("状态: $statusText")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.overlay_accent))
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, actionText, togglePendingIntent)
            .addAction(0, "停止服务", stopPendingIntent)
            .build()
    }
}
