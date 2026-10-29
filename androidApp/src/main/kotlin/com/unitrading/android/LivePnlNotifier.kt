package com.unitrading.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.unitrading.app.LivePnlPresenter
import com.unitrading.app.LivePnlUpdate

/**
 * Live P&L notice shaped like a Live Update: the signed P&L is the headline and the status-bar chip text, the
 * notice is silent, ongoing and public on a low-importance channel, and promotion to a Live Update (Android 16
 * Live Updates, HyperOS 超级岛) is requested whenever the system allows the app to post promoted notifications.
 * The request travels as the `android.requestPromotedOngoing` extra, which Android 16.0 already honours.
 *
 * The first notice starts [LivePnlService] so the process keeps refreshing in the background; later notices
 * update the same notification id. When a foreground service cannot be started (the app is in the background
 * after the service timed out), the notice is posted as a plain ongoing notification that expires on its own.
 */
class LivePnlNotifier(private val context: Context) : LivePnlPresenter {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    /** Set by the foreground Activity; invoked once per process when a notice cannot be posted without POST_NOTIFICATIONS. */
    @Volatile var requestPermission: (() -> Unit)? = null
    private var permissionRequested = false

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "实时盈亏", NotificationManager.IMPORTANCE_LOW).apply {
            description = "当前账户浮动盈亏的常驻通知"
            setSound(null, null)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    fun permitted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Whether the system lets this app post Live Updates (user-controllable from Android 16 on). */
    fun promotionAllowed(): Boolean = Build.VERSION.SDK_INT >= 36 && manager.canPostPromotedNotifications()

    override fun show(update: LivePnlUpdate) {
        if (!permitted()) {
            if (!permissionRequested) { permissionRequested = true; requestPermission?.invoke() }
            return
        }
        if (LivePnlService.running) {
            manager.notify(ID, build(update, expires = false))
            return
        }
        val notification = build(update, expires = true)
        val start = Intent(context, LivePnlService::class.java).setAction(LivePnlService.ACTION_SHOW).putExtra(LivePnlService.EXTRA_NOTIFICATION, notification)
        try {
            ContextCompat.startForegroundService(context, start)
        } catch (_: Exception) {
            // Background start refused (Android 12+) or the service type rejected: keep the notice without the service.
            manager.notify(ID, notification)
        }
    }

    override fun clear() {
        context.stopService(Intent(context, LivePnlService::class.java))
        manager.cancel(ID)
    }

    /** [expires] guards the plain notice: without a service, a dead process must not leave a stale number behind. */
    private fun build(update: LivePnlUpdate, expires: Boolean): Notification {
        val color = when { update.trend > 0 -> PROFIT; update.trend < 0 -> LOSS; else -> NEUTRAL }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_live_pnl)
            .setContentTitle(update.pnlText)
            .setContentText("${update.title} · ${update.detail}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${update.title}\n${update.detail}"))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setColor(color)
            .setShowWhen(false)
            // Android 12+ otherwise hides a foreground service notification for ~10 s after the service starts.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (Build.VERSION.SDK_INT >= 36) builder.setShortCriticalText(update.pnlText)
        if (promotionAllowed()) builder.setRequestPromotedOngoing(true)
        if (expires) builder.setTimeoutAfter(TIMEOUT_MS)
        return builder.build()
    }

    companion object {
        const val CHANNEL = "live-pnl-status"
        const val ID = 1001
        /** A plain notice older than this without a refresh is dropped by the system. */
        const val TIMEOUT_MS = 90_000L
        private const val PROFIT = 0xFF089981.toInt()
        private const val LOSS = 0xFFF23645.toInt()
        private const val NEUTRAL = 0xFF2962FF.toInt()
    }
}
