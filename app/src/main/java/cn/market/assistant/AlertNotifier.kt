package cn.market.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.market.assistant.domain.alert.AlertEvent

/**
 * Posts alert notifications. Android 16 promotes an eligible ongoing progress notification to a status
 * bar Live Update automatically, so the same notification works on older releases; the native
 * `Notification.ProgressStyle` API needs compileSdk 36 and stays a follow-up.
 */
object AlertNotifier {
    const val CHANNEL_ID = "market_alerts"
    const val CHANNEL_NAME = "行情与持仓提醒"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "阈值提醒：只陈述事实与来源时点，不提供买卖建议"
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun post(context: Context, event: AlertEvent, progressPct: Int? = null, ongoing: Boolean = false) {
        if (!hasPermission(context)) return
        val notification = build(context, event, progressPct, ongoing)
        runCatching {
            NotificationManagerCompat.from(context).notify(event.ruleId.hashCode(), notification)
        }
    }

    fun cancel(context: Context, ruleId: String) {
        NotificationManagerCompat.from(context).cancel(ruleId.hashCode())
    }

    private fun build(
        context: Context,
        event: AlertEvent,
        progressPct: Int?,
        ongoing: Boolean
    ): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            event.ruleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setWhen(event.firedAtMillis)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShortcutId("market-alerts")
        progressPct?.let { pct ->
            builder.setProgress(100, pct.coerceIn(0, 100), false)
            builder.setSubText("阈值进度 $pct%")
        }
        return builder.build()
    }
}
