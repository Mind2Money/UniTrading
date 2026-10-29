package com.unitrading.android

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that owns the live P&L notification so the process, the broker connection and the
 * notice keep running while the app is in the background. [LivePnlNotifier] starts it with the first
 * notice, refreshes the same notification id through NotificationManager afterwards and stops the service
 * when the notice clears. Android 15+ caps dataSync services at six hours a day; [onTimeout] then removes
 * the notice until the app is opened again.
 */
class LivePnlService : Service() {
    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = intent?.takeIf { it.action == ACTION_SHOW }?.notification()
        if (notification == null) {
            stop()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 29) startForeground(LivePnlNotifier.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(LivePnlNotifier.ID, notification)
        return START_NOT_STICKY
    }

    /** Android 15+: the daily dataSync allowance ran out; a stale number must not stay on screen. */
    override fun onTimeout(startId: Int, fgsType: Int) { stop() }

    override fun onDestroy() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun Intent.notification(): Notification? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(EXTRA_NOTIFICATION)

    companion object {
        const val ACTION_SHOW = "com.unitrading.android.livepnl.SHOW"
        const val EXTRA_NOTIFICATION = "notification"
        /** True between onCreate and onDestroy; while it holds, refreshes go through NotificationManager.notify. */
        @Volatile var running = false
            private set
    }
}
