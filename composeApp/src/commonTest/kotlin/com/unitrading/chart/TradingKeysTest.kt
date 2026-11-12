package com.unitrading.chart

import androidx.compose.ui.input.key.Key
import com.unitrading.app.AccountBrackets
import com.unitrading.app.TradingKeys
import com.unitrading.app.TradingPreferences
import com.unitrading.broker.Instrument
import com.unitrading.broker.Side
import com.unitrading.ui.keyboardTradingSummary
import kotlin.test.*

/** Which keys trade under each pair of trading keys, and what the interface says about them. */
class TradingKeysTest {
    private val futures = Instrument("TESTZ6", "Test", "Test", 2, 25, 1_250_000, quantityUnit = "张", quantityDecimals = 0)

    @Test fun onlyTheChosenPairTradesAndNeverWithAModifier() {
        assertEquals(Side.BUY, TradingKeys.LETTERS.side(Key.W, modified = false))
        assertEquals(Side.SELL, TradingKeys.LETTERS.side(Key.S, modified = false))
        assertEquals(Side.BUY, TradingKeys.ARROWS.side(Key.DirectionUp, modified = false))
        assertEquals(Side.SELL, TradingKeys.ARROWS.side(Key.DirectionDown, modified = false))
        for (key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.A, Key.D, Key.Enter, Key.Spacebar)) assertNull(TradingKeys.LETTERS.side(key, false), "$key under the letter keys")
        for (key in listOf(Key.W, Key.S, Key.DirectionLeft, Key.DirectionRight, Key.Enter)) assertNull(TradingKeys.ARROWS.side(key, false), "$key under the arrow keys")
        for (keys in TradingKeys.entries) for (key in listOf(Key.W, Key.S, Key.DirectionUp, Key.DirectionDown)) assertNull(keys.side(key, modified = true), "Ctrl, Alt, Shift or Meta with $key")
    }

    @Test fun theLegendHintSaysWhatAKeyDoesOnceTheChartHasTheFocus() {
        assertEquals("键盘下单：点一下图表后 W / S 生效", tradingKeysHint(TradingKeys.LETTERS, live = false, futures, 200, instant = true))
        assertEquals("W 市价买入 · S 市价卖出 · 2 张 · 按键后确认", tradingKeysHint(TradingKeys.LETTERS, live = true, futures, 200, instant = false))
        assertEquals("↑ 市价买入 · ↓ 市价卖出 · 2 张 · 按键即成交", tradingKeysHint(TradingKeys.ARROWS, live = true, futures, 200, instant = true))
        assertEquals("W 市价买入 · S 市价卖出 · 数量无效 · 按键后确认", tradingKeysHint(TradingKeys.LETTERS, live = true, futures, null, instant = false))
    }

    @Test fun theLegendHintFollowsTheBracketsOfTheAccount() {
        // Cash amounts add their protection to what a key sends, and no other step shows it when confirmations are skipped.
        assertEquals("W 市价买入 · S 市价卖出 · 2 张 · 开仓自动附加 SL/TP · 按键即成交",
            tradingKeysHint(TradingKeys.LETTERS, live = true, futures, 200, instant = true, attached = "SL/TP"))
        // An account that sizes its orders from a stop has none to send from a key: the key opens the order panel.
        val sizing = tradingKeysHint(TradingKeys.ARROWS, live = true, futures, null, instant = true, sizing = true)
        assertEquals("↑ 买入 · ↓ 卖出 · 打开下单栏填写 SL，不直接下单", sizing)
        assertFalse("市价" in sizing || "成交" in sizing)
        assertEquals("键盘下单：点一下图表后 ↑ / ↓ 生效", tradingKeysHint(TradingKeys.ARROWS, live = false, futures, null, instant = true, sizing = true))
    }

    @Test fun theSettingsSummaryFollowsTheChosenKeysAndTheConfirmationSetting() {
        val asking = keyboardTradingSummary(TradingPreferences(keyboardTrading = true))
        assertContains(asking, "按 W 市价买入、按 S 市价卖出")
        assertContains(asking, "按键后先弹确认框")
        assertContains(asking, "不带止损止盈")
        val instant = keyboardTradingSummary(TradingPreferences(skipConfirmation = true, keyboardTrading = true, tradingKeys = TradingKeys.ARROWS))
        assertContains(instant, "按 ↑ 市价买入、按 ↓ 市价卖出")
        assertContains(instant, "按键即成交")
        assertFalse("确认框" in instant)
    }

    @Test fun theSettingsSummaryFollowsTheBracketsOfTheSelectedAccount() {
        val instant = TradingPreferences(skipConfirmation = true, keyboardTrading = true)
        val cash = keyboardTradingSummary(instant, AccountBrackets(loss = 100_000_000))
        assertContains(cash, "按 W 市价买入、按 S 市价卖出")
        assertContains(cash, "开仓单按当前子账户的金额设置自动附加止损止盈")
        assertFalse("不带止损止盈" in cash)
        // Under sizing a key trades nothing, whatever the confirmation setting: the summary may not promise a fill.
        val sizing = keyboardTradingSummary(instant, AccountBrackets(sizingRisk = 100_000_000, sizingMaxLots = 500))
        assertContains(sizing, "按 W 或 S 打开下单栏并选好买入或卖出，不会直接下单")
        assertFalse("按键即成交" in sizing || "市价" in sizing)
    }
}
