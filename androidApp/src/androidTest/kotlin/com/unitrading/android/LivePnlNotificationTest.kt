package com.unitrading.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.unitrading.app.LivePnlUpdate
import com.unitrading.broker.AccountInfo
import com.unitrading.broker.AccountSnapshot
import com.unitrading.broker.BrokerDescriptor
import org.junit.Assert.*
import org.junit.Test

/** Posts a synthetic P&L notice through the real NotificationManager; no account, network or trading is involved. */
class LivePnlNotificationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private fun posted() = manager.activeNotifications.firstOrNull { it.id == LivePnlNotifier.ID }
    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(50)
        }
    }

    @Test fun ongoingNoticeCarriesTheAccountPnlAndClears() {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val notifier = LivePnlNotifier(context)
        notifier.clear()
        await("previous notice did not clear") { posted() == null }
        assertTrue(notifier.permitted())
        val update = LivePnlUpdate.from(BrokerDescriptor("test", "Synthetic", "device test broker"), AccountInfo("acc-1", "测试账户"),
            AccountSnapshot("acc-1", balanceMicros = 10_000_000_000, equityMicros = 10_128_500_000), connected = true, nowMs = System.currentTimeMillis())
        notifier.show(update)
        await("notice was not posted") { posted() != null }
        val active = posted()!!
        assertTrue(active.isOngoing)
        assertEquals("+128.50 USD", active.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertTrue(active.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("Synthetic · 测试账户"))
        assertEquals(notifier.promotionAllowed(), active.notification.extras.getBoolean("android.requestPromotedOngoing"))
        if (Build.VERSION.SDK_INT >= 36) assertEquals("+128.50 USD", active.notification.shortCriticalText)
        notifier.clear()
        await("notice did not clear") { posted() == null }
    }
}
