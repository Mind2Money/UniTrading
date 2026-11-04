package com.unitrading.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.ProductionLicenseKeys
import com.unitrading.app.TradingRuntime
import com.unitrading.ui.TradingScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only runs in the isolated credentialtest package with synthetic chart/account data. */
class AppearanceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    // CI emulators render with SwiftShader, where the first Compose frame alone can take several seconds, and the
    // system accessibility service stays contended for ~15 s after the test APKs are installed.
    private val uiTimeoutMs = 60_000L
    private fun idle() { instrumentation.waitForIdleSync(); automation.waitForIdle(300, 5000) }
    private fun awaitNode(text: String, description: Boolean = false, last: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + uiTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            node(text, description, last)?.let { return it }
            SystemClock.sleep(50)
        }
        error("UI did not render: $text; on screen: ${visibleTexts()}")
    }
    private fun awaitText(text: String) { awaitNode(text) }
    private fun firstEditableNode(): AccessibilityNodeInfo {
        if (android.os.Build.VERSION.SDK_INT >= 35) automation.clearCache()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (item.isEditable && item.isVisibleToUser) return item
            for (i in 0 until item.childCount) item.getChild(i)?.let(queue::add)
        }
        error("No editable order quantity on screen")
    }
    private fun awaitKeyboard(visible: Boolean) {
        val deadline = SystemClock.uptimeMillis() + uiTimeoutMs
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            val shown = automation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (shown != visible) stableSince = 0L
            else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            else if (SystemClock.uptimeMillis() - stableSince >= 500) { idle(); return }
            SystemClock.sleep(50)
        }
        error("Keyboard visibility did not become $visible")
    }
    /** Diagnostics for a failed wait: which window answers and what it shows. */
    private fun visibleTexts(): String {
        val root = automation.rootInActiveWindow ?: return "<no active window>"
        val texts = mutableListOf<String>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && texts.size < 40) {
            val item = queue.removeFirst()
            (item.text ?: item.contentDescription)?.toString()?.takeIf { it.isNotBlank() }?.let(texts::add)
            for (i in 0 until item.childCount) item.getChild(i)?.let(queue::add)
        }
        return "${root.packageName}: $texts"
    }
    private fun awaitChartChange(previous: String?): String? {
        val deadline = SystemClock.uptimeMillis() + uiTimeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val current = node("拖动图表平移", true)?.stateDescription?.toString()
            if (current != null && current != previous) return current
            SystemClock.sleep(50)
        }
        screenshot("android-gesture-failure")
        error("Chart gesture did not change viewport")
    }
    private fun node(text: String, description: Boolean = false, last: Boolean = false): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 35) automation.clearCache()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        var match: AccessibilityNodeInfo? = null
        automation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (item.isVisibleToUser && (if (description) item.contentDescription?.toString()?.contains(text) == true else item.text?.toString() == text)) {
                if (!last) return item
                match = item
            }
            for (i in 0 until item.childCount) item.getChild(i)?.let(queue::add)
        }
        return match
    }
    private fun scrollTicketTo(text: String) {
        repeat(6) {
            if (node(text) != null) return
            val screen = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
            swipe(screen.exactCenterX(), screen.height() * .76f, screen.exactCenterX(), screen.height() * .56f)
        }
        awaitText(text)
    }
    private fun tap(text: String, last: Boolean = false, description: Boolean = false) {
        val item = awaitNode(text, last = last, description = description)
        tapNode(item)
    }
    private fun tapNode(item: AccessibilityNodeInfo) {
        val bounds = Rect().also(item::getBoundsInScreen)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true)); event.recycle()
            SystemClock.sleep(40)
        }
        SystemClock.sleep(350)
        idle()
    }
    private fun screenshot(name: String) {
        // Diagnostics only: app-specific external storage can be briefly unwritable right after a reinstall.
        runCatching {
            val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
            file.outputStream().use { check(automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }.onFailure { Log.w("AppearanceUiTest", "Screenshot $name was not saved", it) }
    }
    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 20 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x1 + (x2 - x1) * step / 20, y1 + (y2 - y1) * step / 20, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(automation.injectInputEvent(event, true)); event.recycle()
            SystemClock.sleep(16)
        }
        idle()
    }

    @Test fun loginThemeAndMaterialNavigationWithChartGestures() {
        check(instrumentation.targetContext.packageName.endsWith(".credentialtest"))
        val license = AndroidCredentialStore(instrumentation.targetContext, AndroidCredentialStore.LICENSE_FILE, AndroidCredentialStore.LICENSE_KEY_ALIAS)
        runBlocking { license.clear() }
        // Without an activation the app opens on the license gate, with no login page or account behind it.
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitText("输入授权码"); idle()
            assertNull(node("登录交易账户"))
            screenshot("android-license-gate")
        }
        // The test holds no production key; it installs the device-bound record a verified key leaves behind.
        runBlocking { license.write("""{"activation":{"keyId":"${ProductionLicenseKeys.first().id}","activatedAtMs":0}}""") }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The title reads "正在恢复已保存的登录" until saved-login restoration settles.
            awaitText("登录交易账户"); idle()
            assertNull(node("Atlas Demo"))
            screenshot("android-login-v114")
            tap("外观与主题")
            awaitText("浅色")
            screenshot("android-theme-v114")
            tap("浅色")
            tap("保存主题")
            // node() only searches the active window, so this also waits for the dialog to close.
            awaitText("登录交易账户")
            screenshot("android-login-light-v114")
            val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
                activity.setContent { TradingScreen(runtime) }
            }
            awaitText("图表")
            idle()
            assertNotNull(node("图表")); assertNotNull(node("行情")); assertNotNull(node("交易")); assertNotNull(node("设置"))
            screenshot("android-navigation-v114")
            val chart = awaitNode("拖动图表平移", description = true)
            val bounds = Rect().also(chart::getBoundsInScreen)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val previous = chart.stateDescription?.toString()
            swipe(bounds.right - 42f * density, bounds.top + bounds.height() * .7f, bounds.right - 42f * density, bounds.top + bounds.height() * .3f)
            val zoomed = awaitChartChange(previous)
            assertNotEquals(previous, zoomed)
            swipe(bounds.left + bounds.width() * .25f, bounds.bottom - 15f * density, bounds.left + bounds.width() * .6f, bounds.bottom - 15f * density)
            assertNotEquals(zoomed, awaitChartChange(zoomed))
            screenshot("android-chart-zoom-v114")
            // Standard sheet shares the chart window; IME must not dismiss the editor.
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            tap("下单")
            awaitNode("最小化下单面板", description = true)
            assertNull("The active ticket replaces bottom navigation", node("图表"))
            awaitText("检查并模拟买入")
            tapNode(firstEditableNode())
            awaitKeyboard(true)
            awaitText("检查并模拟买入")
            screenshot("android-order-keyboard")
            check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitKeyboard(false)
            idle()
            assertNotNull("Back dismisses the keyboard before minimizing the ticket", node("最小化下单面板", description = true))
            tap("增加数量", description = true)
            screenshot("android-order-panel")
            val updatedQuantity = firstEditableNode().text.toString()
            assertTrue("Quantity after increment: $updatedQuantity; UI: ${visibleTexts()}", updatedQuantity.contains("0.11"))
            val slider = awaitNode("下单数量滑动条", description = true)
            val sliderBounds = Rect().also(slider::getBoundsInScreen)
            swipe(sliderBounds.left + sliderBounds.width() * .1f, sliderBounds.exactCenterY(),
                sliderBounds.left + sliderBounds.width() * .6f, sliderBounds.exactCenterY())
            val slidQuantity = firstEditableNode().text.toString()
            assertNotEquals("A touch drag must update quantity", updatedQuantity, slidQuantity)
            val quantityArgs = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "0.10") }
            check(firstEditableNode().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, quantityArgs))
            idle()
            scrollTicketTo("止损 SL")
            tap("止损 SL")
            tap("止盈 TP")
            awaitText("价格")
            screenshot("android-order-protection-controls")
            tap("止损 SL")
            tap("止盈 TP")
            assertNull("Unchecked protections hide their price inputs", node("价格"))
            check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitNode("展开下单面板", description = true)
            assertNull(node("检查并模拟买入"))
            assertNotNull(node("SL")); assertNotNull(node("TP"))
            assertNotNull(node("市场执行"))
            assertNotNull(node("拖动图表平移", description = true))
            screenshot("android-order-minimized")
            tap("检查并下单", description = true)
            awaitText("确认模拟下单")
            tap("取消")
            awaitNode("展开下单面板", description = true)
            assertNull(node("检查并模拟买入"))
            tap("关闭并放弃本次下单", description = true)
            val beforeNotice = Rect().also(awaitNode("拖动图表平移", description = true)::getBoundsInScreen)
            tap("下单")
            tap("检查并模拟买入")
            awaitText("确认模拟下单")
            tap("确认")
            awaitText("操作提示")
            awaitText("图表")
            val withNotice = Rect().also(awaitNode("拖动图表平移", description = true)::getBoundsInScreen)
            assertEquals("Floating notices must not resize the chart", beforeNotice, withNotice)
            screenshot("android-floating-notice")
            tap("关闭操作提示", description = true)
            assertNull(node("操作提示"))
            tap("设置", last = true)
            awaitText("账户与连接")
            if (node("外观与主题") == null) {
                val screen = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
                swipe(screen.exactCenterX(), screen.height() * .7f, screen.exactCenterX(), screen.height() * .35f)
            }
            awaitText("外观与主题")
            screenshot("android-account-v114")
            tap("跳过交易确认")
            assertTrue(runtime.tradingPreferences.preferences.value.skipConfirmation)
            screenshot("android-skip-confirmation")
            val directSession = runtime.registry.plugins.first().openSession(runtime.registry.plugins.first().accounts.first().id)
            val beforeDirectOrder = directSession.state.value.positions.size
            tap("图表", last = true)
            tap("下单")
            awaitText("模拟买入")
            tap("最小化下单面板", description = true)
            tap("立即下单", description = true)
            awaitText("下单")
            assertNull(node("确认模拟下单"))
            assertEquals(beforeDirectOrder + 1, directSession.state.value.positions.size)
            tap("设置", last = true)
            tap("跳过交易确认")
            assertFalse(runtime.tradingPreferences.preferences.value.skipConfirmation)
        }
        // Reopen the actual Activity to verify the persisted theme can be loaded after teardown.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitText("登录交易账户"); idle()
            scenario.onActivity { activity ->
                val model = androidx.lifecycle.ViewModelProvider(activity)[TradingRuntimeModel::class.java]
                assertEquals(com.unitrading.app.ThemePreset.LIGHT, model.runtime.theme.settings.value.preset)
            }
            screenshot("android-theme-restored-v114")
        }
    }
}
