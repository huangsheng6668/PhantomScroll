package com.phantom.scroll.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phantom.scroll.MainActivity
import com.phantom.scroll.R

object NotificationHelper {
    const val CHANNEL_ID = "phantom_scroll_service_channel_v3"
    const val NOTIFICATION_ID = 4777

    /**
     * Process-wide cache of the notification's large icon. The notification is rebuilt on
     * every isRunning change (toggle / screen off-on restore / auto-pause), and decoding the
     * full-density launcher bitmap each time churned ~100-400KB on the main thread per update.
     * The system copies the bitmap when parcelling the notification, so reuse is safe.
     * Benign race: callers are all on the main thread; a stale race would at worst decode twice.
     */
    @Volatile
    private var cachedLargeIcon: Bitmap? = null

    private fun largeIcon(context: Context): Bitmap? = cachedLargeIcon ?: run {
        val icon = try {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
        } catch (e: Exception) {
            rasterizeAppIcon(context)
        }
        cachedLargeIcon = icon
        icon
    }

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
            .setLargeIcon(largeIcon(context))
            .setColor(ContextCompat.getColor(context, R.color.overlay_accent))
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, actionText, togglePendingIntent)
            .addAction(0, "停止服务", stopPendingIntent)
            .build()
    }

    /**
     * Draws the app's launcher icon (an adaptive icon on API 26+) onto a 48dp ARGB_8888 bitmap.
     * PackageManager.getApplicationIcon returns the *resolved* drawable (background + foreground
     * layers flattened for an adaptive icon), so the result matches the home-screen icon exactly.
     */
    private fun rasterizeAppIcon(context: Context): Bitmap {
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val size = (48 * context.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp
    }
}
