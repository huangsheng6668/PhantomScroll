package com.phantom.scroll.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phantom.scroll.MainActivity
import com.phantom.scroll.R

object NotificationHelper {
    const val CHANNEL_ID = "phantom_scroll_service_channel"
    const val NOTIFICATION_ID = 4777

    // Broadcast actions for notification control buttons
    const val ACTION_TOGGLE = "com.phantom.scroll.ACTION_TOGGLE"
    const val ACTION_STOP = "com.phantom.scroll.ACTION_STOP"

    fun createChannel(context: Context) {
        // NotificationChannel is required on Android 8.0+ (API 26+)
        // Our minSdk is 26, so the version check is always true but kept for clarity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    fun showNotification(context: Context, isRunning: Boolean) {
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
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, actionText, togglePendingIntent)
            .addAction(0, "停止服务", stopPendingIntent)
            .build()
    }
}
