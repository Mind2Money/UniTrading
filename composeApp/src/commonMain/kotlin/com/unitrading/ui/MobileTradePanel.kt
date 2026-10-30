package com.unitrading.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import kotlinx.coroutines.launch

internal enum class MobileOrderPanelState { CLOSED, EDITING, MINIMIZED }

/** Per-symbol session retained while browsing other charts within the same account. */
internal class MobileOrderSession(instrument: Instrument) {
    val draft = mutableStateOf(OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10"))
    val panelState = mutableStateOf(MobileOrderPanelState.CLOSED)
}

/** A single entry point leaves the chart usable until an order is started. */
@Composable
internal fun MobileTradeBar(
    account: AccountSnapshot,
    onOpen: () -> Unit,
    onPositions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    Column(modifier.fillMaxWidth().background(colors.Surface).testTag("trade-bar")) {
        DividerLine()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onPositions).testTag("panel-positions"), verticalArrangement = Arrangement.Center) {
                Text("持仓 ${account.positions.size} · 浮动", fontSize = 11.sp, color = colors.Muted, maxLines = 1)
                val known = account.equityKnown && account.balanceKnown
                Text(if (known) money(account.floatingPnlMicros, true) else "—", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = if (known) colors.pnl(account.floatingPnlMicros) else colors.Ink, maxLines = 1)
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onPositions).testTag("panel-daily-pnl"), verticalArrangement = Arrangement.Center) {
                Text("今日盈亏 · 券商", fontSize = 11.sp, color = colors.Muted, maxLines = 1)
                Text(account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—", fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.pnl(account.dailyPnl?.totalMicros), maxLines = 1)
            }
            Button(onOpen, Modifier.heightIn(min = 48.dp).testTag("open-order"), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 24.dp)) {
                Text("下单", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(12.dp))
                Chevron(down = false, LocalContentColor.current)
            }
        }
    }
}

/** The same draft session: chart edits continue while the form is minimized. */
@Composable
internal fun MinimizedOrderBar(
    instrument: Instrument,
    draft: OrderDraft,
    busy: Boolean,
    onRestore: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TradingColors
    val sideColor = if (draft.side == Side.BUY) colors.Up else colors.Down
    Surface(modifier.fillMaxWidth().testTag("minimized-order"), color = colors.Surface, tonalElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(36.dp).background(sideColor, RoundedCornerShape(2.dp)))
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onRestore)
                .testTag("restore-order").heightIn(min = 52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${draft.side.label()} · ${draft.kind.label()} · ${draft.quantity} ${instrument.quantityUnit}",
                        color = sideColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${instrument.symbol} · ${if (busy) "提交中…" else "草稿保留 · 可拖动图表调价"}",
                        color = colors.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Chevron(down = false, colors.Accent)
            }
            PanelIconButton(close = true, enabled = !busy, onClick = onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileOrderSheet(
    instrument: Instrument,
    accountName: String,
    environment: String,
    onMinimize: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TradingColors
    // Open fully so the review action stays above the IME and short-screen fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun dismiss(onDismiss: () -> Unit) {
        scope.launch {
            sheetState.hide()
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onMinimize,
        sheetState = sheetState,
        containerColor = colors.Surface,
        contentColor = colors.Ink,
        modifier = modifier.testTag("order-panel"),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("新建订单", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("${instrument.symbol} · $accountName", fontSize = 11.sp, color = colors.Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                PanelIconButton(close = false, onClick = { dismiss(onMinimize) })
                PanelIconButton(close = true, enabled = !busy, onClick = { dismiss(onClose) })
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(environment)
                Spacer(Modifier.width(8.dp))
                Text("${instrument.currency} 结算", fontSize = 11.sp, color = colors.Muted)
                Spacer(Modifier.weight(1f))
            }
            content()
        }
    }
}

@Composable
private fun PanelIconButton(close: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val color = TradingColors.Muted.copy(alpha = if (enabled) 1f else .4f)
    IconButton(onClick, Modifier.size(48.dp).testTag(if (close) "close-order" else "minimize-order")
        .semantics { contentDescription = if (close) "关闭并放弃本次下单" else "最小化下单面板" }, enabled = enabled) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.8.dp.toPx()
            if (close) {
                drawLine(color, Offset(size.width * .2f, size.height * .2f), Offset(size.width * .8f, size.height * .8f), stroke)
                drawLine(color, Offset(size.width * .8f, size.height * .2f), Offset(size.width * .2f, size.height * .8f), stroke)
            } else drawLine(color, Offset(size.width * .15f, size.height * .65f), Offset(size.width * .85f, size.height * .65f), stroke)
        }
    }
}
