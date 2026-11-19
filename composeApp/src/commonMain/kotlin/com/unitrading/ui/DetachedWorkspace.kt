package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

private enum class DetachedSidePanel { MARKET, DOM, ORDER }

/** A compact copy of the desktop workspace, with an independent rail for each detached window. */
@Composable
internal fun DetachedWorkspace(
    id: String,
    chart: @Composable (Modifier) -> Unit,
    market: @Composable (Modifier, close: () -> Unit) -> Unit,
    order: @Composable (Modifier, close: () -> Unit) -> Unit,
    terminal: @Composable (Modifier) -> Unit,
    terminalSummary: String,
    dom: (@Composable (Modifier, close: () -> Unit) -> Unit)?,
    orderRequest: Int,
    onDomOpenChange: (Boolean) -> Unit,
    onActivate: () -> Unit,
) {
    var side by remember(id) { mutableStateOf<DetachedSidePanel?>(DetachedSidePanel.MARKET) }
    var tradeOpen by remember(id) { mutableStateOf(false) }
    LaunchedEffect(orderRequest) { if (orderRequest > 0) side = DetachedSidePanel.ORDER }
    val latestDomChange by rememberUpdatedState(onDomOpenChange)
    LaunchedEffect(side) { latestDomChange(side == DetachedSidePanel.DOM) }
    DisposableEffect(id) { onDispose { latestDomChange(false) } }
    fun toggle(panel: DetachedSidePanel) {
        onActivate()
        side = if (side == panel) null else panel
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("detached-workspace-$id")) {
        val sideWidth = minOf(320.dp, maxWidth * .42f)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                chart(Modifier.weight(1f).fillMaxWidth())
                if (tradeOpen) {
                    DividerLine()
                    Column(Modifier.heightIn(min = 150.dp, max = 240.dp).fillMaxWidth().weight(.55f, fill = false)
                        .background(TradingColors.Surface).testTag("detached-terminal-$id")) {
                        PanelHeader("交易", Modifier.background(TradingColors.Surface), terminalSummary.ifBlank { null }) {
                            ActionIcon(AppIcons.ExpandMore, "收起交易面板", "detached-terminal-close-$id") { tradeOpen = false }
                        }
                        terminal(Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    DividerLine()
                    PanelHeader("交易", Modifier.background(TradingColors.Surface), terminalSummary.ifBlank { null }) {
                        ActionIcon(AppIcons.ExpandLess, "展开交易面板", "detached-terminal-open-$id") {
                            onActivate(); tradeOpen = true
                        }
                    }
                }
            }
            if (side != null) {
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.width(sideWidth).fillMaxHeight().testTag("detached-side-panel-$id")) {
                    when (side) {
                        DetachedSidePanel.MARKET -> market(Modifier.fillMaxSize()) { side = null }
                        DetachedSidePanel.ORDER -> order(Modifier.fillMaxSize()) { side = null }
                        DetachedSidePanel.DOM -> dom?.invoke(Modifier.fillMaxSize()) { side = null }
                        null -> Unit
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NavigationRail(Modifier.width((PANEL_RAIL - 1f).dp).fillMaxHeight().testTag("detached-rail-$id"),
                windowInsets = WindowInsets(0, 0, 0, 0), header = { Spacer(Modifier.height(8.dp)) }) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    RailToggle(AppIcons.TrendingUp, "行情", "detached-rail-market-$id", side == DetachedSidePanel.MARKET) { toggle(DetachedSidePanel.MARKET) }
                    if (dom != null) RailToggle(AppIcons.OrderBook, "DOM", "detached-rail-dom-$id", side == DetachedSidePanel.DOM) { toggle(DetachedSidePanel.DOM) }
                    RailToggle(AppIcons.OrderEntry, "下单", "detached-rail-order-$id", side == DetachedSidePanel.ORDER) { toggle(DetachedSidePanel.ORDER) }
                    RailToggle(if (tradeOpen) AppIcons.ReceiptLongFilled else AppIcons.ReceiptLong, "交易", "detached-rail-terminal-$id", tradeOpen) {
                        onActivate(); tradeOpen = !tradeOpen
                    }
                }
            }
        }
    }
}
