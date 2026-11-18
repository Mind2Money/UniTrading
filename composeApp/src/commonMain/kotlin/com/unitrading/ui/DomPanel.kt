package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*

/** Prices are only a scale: missing market levels never become fictitious order quantities. */
internal fun domPriceLadder(snapshot: OrderFlowSnapshot, tickSize: Long): List<Long> {
    if (tickSize <= 0 || snapshot.depthStatus != DepthStatus.AVAILABLE) return emptyList()
    val best = snapshot.bestBid?.price ?: snapshot.bids.maxOfOrNull { it.price }
        ?: snapshot.bestAsk?.price ?: snapshot.asks.minOfOrNull { it.price } ?: return emptyList()
    return (-40L..40L).mapNotNull { step ->
        runCatching { checkedAdd(best, checkedMultiply(step, tickSize)) }.getOrNull()?.takeIf { it > 0 }
    }.sortedDescending()
}

/** Read-only ladder; orders are marked from the selected account and never placed by tapping the ladder. */
@Composable
internal fun DomPanel(plugin: BrokerPlugin, instrument: Instrument, orders: List<PendingOrder>, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val feed = plugin.orderFlow
    val current = key(plugin) { feed?.collectAsState()?.value ?: OrderFlowSnapshot(statusMessage = "此连接未提供盘口") }
    val contract = instrument.executionSymbol
    val snapshot = if (contract != null && current.symbol == contract) current
        else OrderFlowSnapshot(statusMessage = if (contract == null) "等待主力合约确认" else "等待盘口订阅")
    val prices = remember(snapshot.bids, snapshot.asks, snapshot.bestBid, snapshot.bestAsk, snapshot.depthStatus, instrument.tickSize) { domPriceLadder(snapshot, instrument.tickSize) }
    val bids = remember(snapshot.bids) { snapshot.bids.associate { it.price to it.size } }
    val asks = remember(snapshot.asks) { snapshot.asks.associate { it.price to it.size } }
    val maximum = remember(bids, asks) { (bids.values + asks.values).maxOrNull()?.coerceAtLeast(1) ?: 1 }
    val own = remember(orders, contract) { orders.filter { it.request.symbol == contract && it.request.kind == OrderKind.LIMIT && it.request.entryPrice != null }
        .groupBy { it.request.side to it.request.entryPrice } }
    val colors = TradingColors
    Surface(modifier.testTag("dom-panel"), color = colors.Surface, shape = MaterialTheme.shapes.small, tonalElevation = 3.dp, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("DOM", subtitle = contract ?: "主力待确认") {
                ActionIcon(AppIcons.Close, "关闭 DOM", "close-dom", onClick = onClose)
            }
            val available = snapshot.depthStatus == DepthStatus.AVAILABLE && snapshot.connected
            Text(if (available) "实时盘口 · 只读" else snapshot.statusMessage.ifBlank { "等待盘口数据" },
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).testTag("dom-status"),
                color = if (available) colors.Profit else colors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2)
            Row(Modifier.fillMaxWidth().background(colors.Raised).height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                DomCell("我的买", Modifier.weight(.78f), colors.Muted)
                DomCell("买量", Modifier.weight(.85f), colors.Profit)
                DomCell("价格", Modifier.weight(1.35f), colors.Muted)
                DomCell("卖量", Modifier.weight(.85f), colors.Loss)
                DomCell("我的卖", Modifier.weight(.78f), colors.Muted)
            }
            if (prices.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Text("暂无可用深度，请检查连接及 Level 2 行情权限", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                val list = key(contract) { rememberLazyListState(initialFirstVisibleItemIndex = 28) }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("dom-prices"), state = list) {
                    items(prices, key = { it }) { price ->
                        fun ownText(side: Side): String {
                            val found = own[side to price].orEmpty()
                            if (found.isEmpty()) return ""
                            val quantity = found.fold(0L) { total, o ->
                                runCatching { checkedAdd(total, (o.request.lotsHundredths - o.filledLotsHundredths).coerceAtLeast(0)) }.getOrDefault(total)
                            }
                            return instrument.formatQuantity(quantity)
                        }
                        val isBest = price == snapshot.bestBid?.price || price == snapshot.bestAsk?.price
                        Row(Modifier.fillMaxWidth().height(30.dp).background(if (isBest) colors.Accent.copy(alpha = .14f) else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically) {
                            DomCell(ownText(Side.BUY), Modifier.weight(.78f), colors.Profit)
                            DomVolume(bids[price], maximum, colors.Profit, Modifier.weight(.85f), true)
                            DomCell(instrument.formatPrice(price), Modifier.weight(1.35f), if (isBest) colors.Accent else colors.Ink, bold = isBest)
                            DomVolume(asks[price], maximum, colors.Loss, Modifier.weight(.85f), false)
                            DomCell(ownText(Side.SELL), Modifier.weight(.78f), colors.Loss)
                        }
                    }
                }
            }
            Text("深度数量为张 · 仅供查看", Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1)
        }
    }
}

@Composable
private fun DomVolume(value: Long?, maximum: Long, color: Color, modifier: Modifier, fromRight: Boolean) {
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        value?.let { size ->
            Box(Modifier.fillMaxWidth((size.toDouble() / maximum).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                .align(if (fromRight) Alignment.CenterEnd else Alignment.CenterStart).background(color.copy(alpha = .18f)))
        }
        DomCell(value?.toString().orEmpty(), Modifier.fillMaxWidth(), color)
    }
}

@Composable
private fun DomCell(text: String, modifier: Modifier, color: Color, bold: Boolean = false) {
    Text(text, modifier.padding(horizontal = 1.dp), color = color, style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
