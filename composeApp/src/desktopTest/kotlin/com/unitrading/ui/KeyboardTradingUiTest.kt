package com.unitrading.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.unitrading.app.MemorySnapshotStore
import com.unitrading.app.TradingKeys
import com.unitrading.app.TradingRuntime
import com.unitrading.broker.OrderKind
import com.unitrading.broker.OrderRequest
import com.unitrading.broker.Side
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

/** With keyboard trading on, the chosen pair of keys places market orders while the chart has the keyboard focus. */
@OptIn(ExperimentalTestApi::class)
class KeyboardTradingUiTest {
    @get:Rule val compose = createComposeRule()
    private val broker = OfflineOrderBroker()
    private lateinit var runtime: TradingRuntime

    /** [keys] null leaves keyboard trading off, as it is until the user turns it on. */
    private fun render(keys: TradingKeys? = TradingKeys.LETTERS, skipConfirmation: Boolean = false) {
        runtime = TradingRuntime(MemorySnapshotStore(), listOf(broker), includeDemoBrokers = true).apply { paused.value = true }
        runBlocking {
            if (keys != null) { assertTrue(runtime.tradingPreferences.setKeyboardTrading(true)); assertTrue(runtime.tradingPreferences.setTradingKeys(keys)) }
            if (skipConfirmation) assertTrue(runtime.tradingPreferences.setSkipConfirmation(true))
        }
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(.6f)) { TradingScreen(runtime) } }
        compose.onNodeWithTag("quick-login-picker").performClick()
        compose.onNodeWithTag("quick-login-${broker.descriptor.id}").performClick()
        compose.waitForIdle()
    }
    private fun chart() = compose.onNodeWithTag("trading-chart")
    private fun hint() = compose.onNodeWithTag("trading-keys-hint")
    private fun confirm() = compose.onNodeWithTag("confirm-action")
    /** A click on the plot gives the chart the keyboard focus, as in the app. */
    private fun clickChart() {
        val size = chart().fetchSemanticsNode().size
        chart().performMouseInput { moveTo(Offset(size.width * .7f, size.height * .75f)); press(); release() }
        compose.waitForIdle()
    }
    /** Key events go to whatever has the focus; the last root is the dialog while one is open. */
    private fun keys(block: KeyInjectionScope.() -> Unit) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput(block)
        compose.waitForIdle()
    }
    private fun press(key: Key, heldMs: Long = 50) = keys { pressKey(key, heldMs) }
    private fun market(side: Side, lots: Long = 100) = OrderRequest("TESTZ6", side, OrderKind.MARKET, lots)

    @Test fun theKeysDoNothingUntilKeyboardTradingIsTurnedOn() {
        render(keys = null)
        clickChart()
        hint().assertDoesNotExist()
        for (key in listOf(Key.W, Key.S, Key.DirectionUp, Key.DirectionDown)) press(key)
        confirm().assertDoesNotExist()
        assertTrue(broker.placed.isEmpty())
    }

    @Test fun theLetterKeysAskForTheConfirmationWhichEnterAnswers() {
        render()
        hint().assertTextEquals("键盘下单：点一下图表后 W / S 生效")
        press(Key.W)
        confirm().assertDoesNotExist()
        clickChart()
        hint().assertTextEquals("W 市价买入 · S 市价卖出 · 1 张 · 按键后确认")
        val folder = File("../artifacts").also { it.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(folder, "keyboard-trading-live.png"))
        press(Key.DirectionUp)
        confirm().assertDoesNotExist()

        press(Key.W)
        compose.onNodeWithText("确认 Tradovate Demo 下单").assertExists()
        compose.onNodeWithText("TESTZ6 · 买入市价 · 1 张").assertExists()
        compose.onNodeWithTag("confirm-keys-hint").assertExists()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "keyboard-trading-confirm.png"))
        assertTrue(broker.placed.isEmpty(), "Nothing reaches the broker before the confirmation")
        confirm().assertIsFocused()
        press(Key.Enter)
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(market(Side.BUY), broker.placed.single(), "the panel's quantity, without protection")
        confirm().assertDoesNotExist()

        // The chart has the keys again without another click.
        hint().assertTextEquals("W 市价买入 · S 市价卖出 · 1 张 · 按键后确认")
        press(Key.S)
        compose.onNodeWithText("TESTZ6 · 卖出市价 · 1 张").assertExists()
        // Escape is handled by the dialog's window layer, which injected keys do not pass through; Cancel takes the same path.
        compose.onNodeWithText("取消").performClick()
        confirm().assertDoesNotExist()
        assertEquals(1, broker.placed.size, "a dismissed confirmation sends nothing")
    }

    @Test fun theArrowKeysTradeAtOnceWithConfirmationsSkippedAndAHeldKeyTradesOnce() {
        render(TradingKeys.ARROWS, skipConfirmation = true)
        clickChart()
        hint().assertTextEquals("↑ 市价买入 · ↓ 市价卖出 · 1 张 · 按键即成交")
        press(Key.W)
        press(Key.S)
        assertTrue(broker.placed.isEmpty(), "the other pair of keys does not trade")
        // Two seconds are long enough for the key to repeat many times.
        press(Key.DirectionUp, heldMs = 2_000)
        compose.waitUntil(5000) { broker.placed.isNotEmpty() }
        assertEquals(listOf(market(Side.BUY)), broker.placed.toList(), "a held key trades once")
        press(Key.DirectionDown)
        compose.waitUntil(5000) { broker.placed.size == 2 }
        assertEquals(market(Side.SELL), broker.placed.last())
        press(Key.DirectionUp)
        compose.waitUntil(5000) { broker.placed.size == 3 }
        assertEquals(market(Side.BUY), broker.placed.last(), "released and pressed again, the key trades again")
        confirm().assertDoesNotExist()
    }

    @Test fun typingInAFieldNeverTradesAndTheHintSaysTheKeysAreNotLive() {
        render(skipConfirmation = true)
        clickChart()
        hint().assertTextEquals("W 市价买入 · S 市价卖出 · 1 张 · 按键即成交")
        compose.onNodeWithTag("order-quantity").performClick()
        compose.waitForIdle()
        hint().assertTextEquals("键盘下单：点一下图表后 W / S 生效")
        press(Key.W)
        press(Key.S)
        assertTrue(broker.placed.isEmpty())
        // The panel's quantity is what a key order carries.
        compose.onNodeWithTag("order-quantity").performTextReplacement("3")
        clickChart()
        hint().assertTextEquals("W 市价买入 · S 市价卖出 · 3 张 · 按键即成交")
        press(Key.S)
        compose.waitUntil(5000) { broker.placed.size == 1 }
        assertEquals(market(Side.SELL, 300), broker.placed.single())
    }

    @Test fun aKeyHeldWithAModifierAndAnOrderTheBrokerRefusesNeverGoOut() {
        render(skipConfirmation = true)
        clickChart()
        keys { withKeyDown(Key.ShiftLeft) { pressKey(Key.W) }; withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
        assertTrue(broker.placed.isEmpty(), "Shift + W and Ctrl + S are not trading keys")
        broker.refusal = "交易连接未就绪"
        press(Key.W)
        compose.onNodeWithTag("trade-notice").assertTextEquals("交易连接未就绪")
        assertTrue(broker.placed.isEmpty())
    }
}
