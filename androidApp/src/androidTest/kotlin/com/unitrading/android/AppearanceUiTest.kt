package com.unitrading.android

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingRuntime
import com.unitrading.ui.TradingScreen
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Only runs in the isolated credentialtest package with synthetic chart/account data. */
class AppearanceUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private fun idle() { instrumentation.waitForIdleSync(); automation.waitForIdle(300, 5000) }
    private fun awaitText(text: String) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            if (node(text) != null) return
            SystemClock.sleep(50)
        }
        error("UI did not render: $text")
    }
    private fun awaitChartChange(previous: String?): String? {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            val current = node("拖动图表平移", true)?.stateDescription?.toString()
            if (current != null && current != previous) return current
            SystemClock.sleep(50)
        }
        screenshot("android-gesture-failure")
        error("Chart gesture did not change viewport")
    }
    private fun node(text: String, description: Boolean = false, last: Boolean = false): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        var match: AccessibilityNodeInfo? = null
        automation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val item = queue.removeFirst()
            if (if (description) item.contentDescription?.toString()?.contains(text) == true else item.text?.toString() == text) {
                if (!last) return item
                match = item
            }
            for (i in 0 until item.childCount) item.getChild(i)?.let(queue::add)
        }
        return match
    }
    private fun tap(text: String, last: Boolean = false) {
        val item = node(text, last = last) ?: error("Missing UI: $text")
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
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { check(automation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)) }
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
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            idle()
            assertNotNull(node("登录交易账户"))
            assertNull(node("Atlas Demo"))
            screenshot("android-login-v114")
            tap("外观与主题")
            screenshot("android-theme-v114")
            assertNotNull(node("浅色"))
            tap("浅色")
            tap("保存主题")
            assertNotNull(node("登录交易账户"))
            screenshot("android-login-light-v114")
            val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
            scenario.onActivity { activity ->
                activity.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
                activity.setContent { TradingScreen(runtime) }
            }
            awaitText("图表")
            idle()
            assertNotNull(node("图表")); assertNotNull(node("行情")); assertNotNull(node("交易")); assertNotNull(node("账户"))
            screenshot("android-navigation-v114")
            val chart = node("拖动图表平移", description = true) ?: error("Chart missing")
            val bounds = Rect().also(chart::getBoundsInScreen)
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val previous = chart.stateDescription?.toString()
            swipe(bounds.right - 42f * density, bounds.top + bounds.height() * .7f, bounds.right - 42f * density, bounds.top + bounds.height() * .3f)
            val zoomed = awaitChartChange(previous)
            assertNotEquals(previous, zoomed)
            swipe(bounds.left + bounds.width() * .25f, bounds.bottom - 15f * density, bounds.left + bounds.width() * .6f, bounds.bottom - 15f * density)
            assertNotEquals(zoomed, awaitChartChange(zoomed))
            screenshot("android-chart-zoom-v114")
            tap("账户", last = true)
            awaitText("交易商与账户")
            if (node("外观与主题") == null) {
                val screen = Rect().also { automation.rootInActiveWindow.getBoundsInScreen(it) }
                swipe(screen.exactCenterX(), screen.height() * .7f, screen.exactCenterX(), screen.height() * .35f)
            }
            awaitText("外观与主题")
            screenshot("android-account-v114")
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
