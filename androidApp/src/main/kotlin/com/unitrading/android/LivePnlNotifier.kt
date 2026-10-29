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
import com.unitrading.app.LivePnlPresenter
import com.unitrading.app.LivePnlUpdate

/**
 * Ongoing notification with the displayed account's floating P&L. On Android 16+ it asks to be promoted to a
 * Live Update (P&L in the status-bar chip, pinned at the top of the shade); older versions show a plain ongoing
 * notification. The notice expires by itself when no refresh arrives, so a killed process never leaves a stale
 * number behind.
 */
class LivePnlNotifier(private val context: Context) : LivePnlPresenter {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    /** Set by the foreground Activity; invoked once per process when a notice cannot be posted without POST_NOTIFICATIONS. */
    @Volatile var requestPermission: (() -> Unit)? = null
    private var permissionRequested = false

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "实时盈亏", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "当前账户浮动盈亏的常驻通知"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }

    fun permitted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun show(update: LivePnlUpdate) {
        if (!permitted()) {
            if (!permissionRequested) { permissionRequested = true; requestPermission?.invoke() }
            return
        }
        val color = when { update.trend > 0 -> PROFIT; update.trend < 0 -> LOSS; else -> NEUTRAL }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_live_pnl)
            .setContentTitle(update.title)
            .setContentText("${update.pnlText}  ·  ${update.detail}")
            .setStyle(Notification.BigTextStyle().bigText("浮动盈亏 ${update.pnlText}\n${update.detail}"))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(color)
            .setShowWhen(true)
            .setWhen(update.timeMs)
            .setTimeoutAfter(TIMEOUT_MS)
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 Live Update: promoted ongoing notification with the P&L as the status-bar chip text.
            builder.setRequestPromotedOngoing(true).setShortCriticalText(update.pnlText)
        }
        manager.notify(ID, builder.build())
    }

    override fun clear() { manager.cancel(ID) }

    companion object {
        const val CHANNEL = "live-pnl"
        const val ID = 1001
        /** A notice older than this without a refresh is dropped by the system. */
        const val TIMEOUT_MS = 90_000L
        private const val PROFIT = 0xFF089981.toInt()
        private const val LOSS = 0xFFF23645.toInt()
        private const val NEUTRAL = 0xFF2962FF.toInt()
    }
}
