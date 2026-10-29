package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.chart.Chevron
import com.unitrading.chart.TradeButton

/**
 * Collapsed order panel under the phone chart, after MT5's trade sheet: Bid/Ask buttons pick a side and open the
 * ticket, the account's floating P&L links to the trade tab, and the handle opens the panel by tap or upward swipe.
 */
@Composable
internal fun MobileTradeBar(
    instrument: Instrument,
    quote: Quote?,
    account: AccountSnapshot,
    onOpen: () -> Unit,
    onSide: (Side) -> Unit,
    onPositions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    Column(modifier.fillMaxWidth().background(colors.Surface).testTag("trade-bar")) {
        DividerLine()
        PanelHandle(open = false) { if (it) onOpen() }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TradeButton(Side.SELL, quote?.let { instrument.formatPrice(it.bid) } ?: "—", colors.Down, Modifier.testTag("quick-sell")) { onSide(Side.SELL) }
            Text(quote?.let { instrument.formatPrice(it.ask - it.bid) } ?: "—", fontSize = 11.sp, color = colors.Muted)
            TradeButton(Side.BUY, quote?.let { instrument.formatPrice(it.ask) } ?: "—", colors.Up, Modifier.testTag("quick-buy")) { onSide(Side.BUY) }
            val known = account.equityKnown && account.balanceKnown
            Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onPositions)
                .testTag("panel-positions").padding(horizontal = 6.dp, vertical = 4.dp), horizontalAlignment = Alignment.End) {
                Text("持仓 ${account.positions.size} · 浮动盈亏", fontSize = 10.sp, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (known) money(account.floatingPnlMicros, true) else "—", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (known) colors.pnl(account.floatingPnlMicros) else colors.Ink, maxLines = 1)
            }
            Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClick = onOpen).testTag("open-order")
                .padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("下单", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.Accent)
                Chevron(down = false, colors.Accent)
            }
        }
    }
}

/**
 * Open order panel: a fixed header names the order's account and environment, and [content] (the ticket) fills the
 * rest. [handle] shows the grab bar used under the chart; the panel docked beside a landscape chart closes by button only.
 */
@Composable
internal fun MobileOrderPanel(
    instrument: Instrument,
    accountName: String,
    environment: String,
    handle: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TradingColors
    Column(modifier.background(colors.Surface).testTag("order-panel")) {
        if (handle) {
            DividerLine()
            PanelHandle(open = true) { if (!it) onClose() }
        }
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = if (handle) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新建订单", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Badge(environment)
                }
                Text("${instrument.symbol} · $accountName · ${instrument.currency} 结算", fontSize = 11.sp, color = colors.Muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClose, Modifier.testTag("collapse-order")) {
                Text("收起", fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
                Chevron(down = true, colors.Accent)
            }
        }
        content()
    }
}

/** Grab bar: a tap toggles the panel, a swipe up opens it and a swipe down closes it. */
@Composable
private fun PanelHandle(open: Boolean, onOpen: (Boolean) -> Unit) {
    val colors = TradingColors
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    var travel by remember { mutableStateOf(0f) }
    Box(
        Modifier.fillMaxWidth().height(18.dp)
            .draggable(rememberDraggableState { travel += it }, Orientation.Vertical,
                onDragStarted = { travel = 0f },
                onDragStopped = { if (travel < -threshold) onOpen(true) else if (travel > threshold) onOpen(false) })
            .clickable(role = Role.Button) { onOpen(!open) }
            .semantics { contentDescription = if (open) "收起下单面板" else "展开下单面板" }
            .testTag("order-panel-handle"),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size(36.dp, 4.dp).background(colors.Border, RoundedCornerShape(2.dp))) }
}
