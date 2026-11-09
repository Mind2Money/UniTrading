package com.unitrading.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.unitrading.app.*
import com.unitrading.broker.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** Walks every screen on synthetic data and writes real-density PNGs to artifacts/ui-tour for visual review. */
@OptIn(ExperimentalTestApi::class)
class UiScreenshotTour {
    private lateinit var compose: ComposeUiTest
    private val folder = File("../artifacts/ui-tour").also { it.mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(folder, "$name.png"))
    }
    private fun shotAll(name: String) {
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { index ->
            ImageIO.write(compose.onAllNodes(isRoot())[index].captureToImage().toAwtImage(), "png", File(folder, "$name-$index.png"))
        }
    }
    private fun tap(tag: String) { compose.onNodeWithTag(tag).performClick(); settle() }
    private fun scrollTap(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick(); settle() }
    private fun settle() { repeat(3) { compose.mainClock.advanceTimeBy(400); compose.waitForIdle() } }

    private fun runtime(theme: ThemePreset = ThemePreset.DARK): TradingRuntime {
        val runtime = TradingRuntime(MemorySnapshotStore(), includeDemoBrokers = true).apply { paused.value = true }
        runBlocking { runtime.theme.apply(ThemeSettings(theme)) }
        val plugin = runtime.registry.plugins.first()
        val session = plugin.openSession(plugin.accounts.first().id)
        val instrument = plugin.instruments.first()
        val quote = plugin.market.value.quotes.getValue(instrument.symbol)
        runBlocking {
            session.placeOrder("tour-1", OrderRequest(instrument.symbol, Side.BUY, OrderKind.MARKET, 100, null,
                quote.bid - 2000 * instrument.tickSize, quote.ask + 3000 * instrument.tickSize))
            session.placeOrder("tour-2", OrderRequest(instrument.symbol, Side.SELL, OrderKind.LIMIT, 50, quote.ask + 1500 * instrument.tickSize))
        }
        return runtime
    }

    private fun phone(block: ComposeUiTest.(TradingRuntime) -> Unit, theme: ThemePreset = ThemePreset.DARK) =
        runDesktopComposeUiTest(width = 824, height = 1830) {
            compose = this
            val runtime = runtime(theme)
            setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.requiredSize(412.dp, 915.dp)) { TradingScreen(runtime) }
            } }
            settle()
            block(runtime)
        }

    private fun desktop(width: Int, height: Int, block: ComposeUiTest.(TradingRuntime) -> Unit, theme: ThemePreset = ThemePreset.DARK) =
        runDesktopComposeUiTest(width = width, height = height) {
            compose = this
            val runtime = runtime(theme)
            setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Box(Modifier.requiredSize(width.dp, height.dp)) { TradingScreen(runtime) }
            } }
            settle()
            block(runtime)
        }

    @Test fun phoneChartAndOrder() = phone({ _ ->
        shot("phone-01-chart")
        tap("open-order")
        shot("phone-02-order-market")
        tap("order-kind-LIMIT")
        tap("order-sl-enabled")
        tap("order-tp-enabled")
        shot("phone-03-order-limit-protected")
        compose.onNodeWithTag("order-estimates-toggle").performScrollTo().performClick(); settle()
        shot("phone-04-order-estimates")
        tap("review-order")
        shot("phone-05-confirm-order")
        compose.onNodeWithText("取消").performClick(); settle()
        tap("minimize-order")
        shot("phone-06-order-minimized")
    })

    @Test fun phoneMarket() = phone({ _ ->
        tap("nav-0")
        shot("phone-10-market")
        tap("market-edit")
        shot("phone-11-market-edit")
        tap("market-edit-done")
        tap("market-add")
        shot("phone-12-market-add")
        compose.onNodeWithTag("symbol-search").performTextReplacement("E"); settle()
        shot("phone-13-market-search")
    })

    @Test fun phoneTradeAndSettings() = phone({ _ ->
        tap("nav-2")
        shot("phone-20-trade")
        compose.onNodeWithTag("trade-terminal").performScrollToKey("section-history"); settle()
        shot("phone-21-trade-history")
        tap("nav-3")
        shot("phone-30-settings")
        compose.onNodeWithTag("settings-page").performTouchInput { swipeUp() }; settle()
        shot("phone-31-settings-scrolled")
        scrollTap("open-theme")
        shot("phone-32-theme")
        tap("theme-cancel")
        scrollTap("settings-indicators")
        shot("phone-33-indicators")
        tap("add-indicator-RSI")
        shot("phone-33b-indicators-rsi")
        tap("indicators-cancel")
        scrollTap("settings-connect")
        shot("phone-34-tradovate")
        compose.onNodeWithTag("cancel-login").performClick(); settle()
        scrollTap("settings-connect-rithmic")
        shot("phone-35-rithmic")
        compose.onNodeWithTag("cancel-rithmic-login").performClick(); settle()
        scrollTap("open-activity")
        shot("phone-36-activity")
    })

    @Test fun phoneLight() = phone({ _ ->
        shot("phone-40-light-chart")
        tap("nav-0")
        shot("phone-41-light-market")
        tap("nav-3")
        shot("phone-42-light-settings")
    }, ThemePreset.LIGHT)

    @Test fun phoneTradingV() = phone({ _ ->
        shot("phone-45-tradingv-chart")
        tap("nav-3")
        shot("phone-46-tradingv-settings")
    }, ThemePreset.TRADINGV)

    @Test fun phoneLandscape() = runDesktopComposeUiTest(width = 1830, height = 824) {
        compose = this
        val runtime = runtime()
        setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) {
            Box(Modifier.requiredSize(915.dp, 412.dp)) { TradingScreen(runtime) }
        } }
        settle()
        shot("phone-70-landscape-chart")
        tap("open-order")
        shot("phone-71-landscape-order")
        tap("close-order")
        tap("nav-2")
        shot("phone-72-landscape-trade")
    }

    @Test fun desktopWide() = desktop(1600, 1000, { _ ->
        shot("desktop-50-wide")
        tap("open-settings")
        shotAll("desktop-51-settings")
        compose.onNodeWithTag("open-theme").performScrollTo().performClick(); settle()
        shotAll("desktop-52-theme")
    })

    @Test fun desktopNarrow() = desktop(1100, 760, { _ ->
        shot("desktop-55-narrow")
        tap("open-order")
        shotAll("desktop-56-order-dialog")
    })

    @Test fun desktopLight() = desktop(1600, 1000, { _ -> shot("desktop-58-light") }, ThemePreset.LIGHT)

    @Test fun desktopTradingV() = desktop(1920, 1080, { _ ->
        shot("desktop-64-tradingv")
        tap("open-settings")
        shotAll("desktop-65-tradingv-settings")
        compose.onNodeWithTag("open-theme").performScrollTo().performClick(); settle()
        shotAll("desktop-66-tradingv-theme")
    }, ThemePreset.TRADINGV)

    /** The sidebar shows fewer rows than the list holds, which is where a drag has to scroll. */
    @Test fun desktopWatchListDrag() = desktop(1440, 900, { runtime ->
        val symbols = (1..16).map { "WATCH$it" }
        runtime.marketWatch.update(runtime.registry.plugins.first().descriptor.id, symbols)
        settle()
        tap("market-edit")
        shot("desktop-59a-market-edit")
        compose.onNodeWithTag("market-row-WATCH2").performMouseInput { moveTo(center); press(); moveBy(Offset(0f, height * 2.7f)) }
        settle()
        shot("desktop-59b-market-edit-dragging")
        compose.onNodeWithTag("market-row-WATCH2").performMouseInput { release() }
        settle()
        shot("desktop-59c-market-edit-dropped")
        check(runtime.marketWatch.lists.value.values.single() == symbols.toMutableList().apply { add(4, removeAt(1)) })
    })

    @Test fun tabletPortrait() = runDesktopComposeUiTest(width = 800, height = 1200) {
        compose = this
        val runtime = runtime()
        setContent { CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.requiredSize(800.dp, 1200.dp)) { TradingScreen(runtime) }
        } }
        settle()
        shot("tablet-75-chart")
        tap("nav-2")
        shot("tablet-76-trade")
    }

    @Test fun emptyCatalog() = runDesktopComposeUiTest(width = 824, height = 1830) {
        compose = this
        val demo = runtime().registry.plugins.first()
        val empty = object : BrokerPlugin by demo {
            override val descriptor = BrokerDescriptor("empty", "Tradovate Demo", "Server data", false, BrokerEnvironment.REMOTE_DEMO)
            override val catalog = kotlinx.coroutines.flow.MutableStateFlow(BrokerCatalog(emptyList(), emptyList()))
            override val instruments = emptyList<Instrument>()
            override val accounts = emptyList<AccountInfo>()
            override val market = kotlinx.coroutines.flow.MutableStateFlow(MarketSnapshot(connected = true, tradingReady = false, statusMessage = "正在加载账户与合约"))
        }
        val runtime = TradingRuntime(MemorySnapshotStore(), listOf(empty))
        setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) {
            Box(Modifier.requiredSize(412.dp, 915.dp)) { TradingScreen(runtime) }
        } }
        settle()
        shot("gate-62-empty-catalog")
    }

    @Test fun gates() {
        runDesktopComposeUiTest(width = 824, height = 1830) {
            compose = this
            val runtime = TradingRuntime(MemorySnapshotStore())
            setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.requiredSize(412.dp, 915.dp)) { TradingScreen(runtime) }
            } }
            settle()
            shot("gate-60-login")
        }
        runDesktopComposeUiTest(width = 824, height = 1830) {
            compose = this
            val theme = ThemeController(MemorySnapshotStore())
            val license = LicenseController(TestLicenseStore(), OfflineLicenseVerifier(listOf(testLicenseKey("tour", "tour-key"))))
            setContent { CompositionLocalProvider(LocalDensity provides Density(2f)) {
                Box(Modifier.requiredSize(412.dp, 915.dp)) { LicenseGate(license, theme) {} }
            } }
            settle()
            shot("gate-61-license")
        }
    }
}
