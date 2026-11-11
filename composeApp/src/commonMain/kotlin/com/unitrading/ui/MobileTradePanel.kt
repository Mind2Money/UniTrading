package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.unitrading.app.AccountBrackets
import com.unitrading.broker.*

internal enum class MobileOrderPanelState { CLOSED, EDITING, MINIMIZED }

/** Per-symbol session retained while browsing other charts within the same account. */
internal class MobileOrderSession(instrument: Instrument) {
    val draft = mutableStateOf(OrderDraft(quantity = if (instrument.quantityDecimals == 0) "1" else "0.10"))
    val panelState = mutableStateOf(MobileOrderPanelState.CLOSED)
    val advanced = mutableStateOf(false)
}

/** A single entry point leaves the chart usable until an order is started. */
@Composable
internal fun MobileTradeBar(
    account: AccountSnapshot,
    onOpen: () -> Unit,
    onPositions: () -> Unit,
    modifier: Modifier = Modifier, dense: Boolean = false,
) {
    val colors = TradingColors
    val known = account.equityKnown && account.balanceKnown
    Surface(modifier.fillMaxWidth().testTag("trade-bar"), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = if (dense) 0.dp else 10.dp, bottom = if (dense) 0.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val metric = Modifier.weight(1f).heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onPositions)
            Metric("持仓 ${account.positions.size} · 浮动", if (known) money(account.floatingPnlMicros, true) else "—",
                metric.testTag("panel-positions").padding(horizontal = 8.dp, vertical = if (dense) 0.dp else 6.dp), if (known) colors.pnl(account.floatingPnlMicros) else colors.Ink)
            Metric("今日盈亏 · 券商", account.dailyPnl?.let { money(it.totalMicros, true) } ?: "—",
                metric.testTag("panel-daily-pnl").padding(horizontal = 8.dp, vertical = if (dense) 0.dp else 6.dp), colors.pnl(account.dailyPnl?.totalMicros))
            Button(onOpen, Modifier.heightIn(min = 48.dp).testTag("open-order"), colors = brandButtonColors(), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(AppIcons.Add, contentDescription = null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("下单")
            }
        }
    }
}

/** Measure the chart and order controls into disjoint rectangles without remounting the chart on rotation. */
@Composable
internal fun MobileOrderDockLayout(
    modifier: Modifier,
    editing: Boolean,
    landscape: Boolean,
    keyboardOpen: Boolean,
    advanced: Boolean,
    bottomHeight: Dp,
    chart: @Composable () -> Unit,
    dock: @Composable () -> Unit,
) {
    Layout(content = {
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { chart() }
        Box(Modifier.clipToBounds(), propagateMinConstraints = true) { dock() }
    }, modifier = modifier) { children, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (editing && landscape) {
            val dockWidth = (width * .42f).toInt().coerceIn(minOf(280.dp.roundToPx(), width / 2), width / 2)
            val chartWidth = width - dockWidth
            val plot = children[0].measure(Constraints.fixed(chartWidth, height))
            val panel = children[1].measure(Constraints.fixed(dockWidth, height))
            layout(width, height) {
                plot.placeRelative(0, 0)
                panel.placeRelative(chartWidth, 0)
            }
        } else {
            val panelHeight = if (editing) {
                val fraction = when {
                    keyboardOpen -> .75f
                    advanced -> .56f
                    else -> .44f
                }
                val minimum = if (keyboardOpen) 188.dp else 276.dp
                val chartReserve = if (keyboardOpen) minOf(72.dp.roundToPx(), height / 5) else minOf(155.dp.roundToPx(), height / 2)
                val target = (height * fraction).toInt().coerceAtLeast(minimum.roundToPx())
                val preferredHeight = if (!keyboardOpen && !advanced) target.coerceAtMost(320.dp.roundToPx()) else target
                preferredHeight.coerceAtMost(height - chartReserve)
            } else bottomHeight.roundToPx().coerceAtMost(height)
            val chartHeight = height - panelHeight
            val plot = children[0].measure(Constraints.fixed(width, chartHeight))
            val panel = children[1].measure(Constraints.fixed(width, panelHeight))
            layout(width, height) {
                plot.placeRelative(0, 0)
                panel.placeRelative(0, chartHeight)
            }
        }
    }
}

@Composable
internal fun MobileOrderDock(
    instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities, accountName: String,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    panelState: MobileOrderPanelState, onStateChange: (MobileOrderPanelState) -> Unit,
    onClose: () -> Unit, onSubmit: () -> Unit, canSubmit: Boolean, busy: Boolean,
    skipConfirmation: Boolean, keyboardOpen: Boolean,
    brackets: AccountBrackets = AccountBrackets(),
    calculatedLots: Long? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = TradingColors
    val focus = LocalFocusManager.current
    val collapsed = panelState == MobileOrderPanelState.MINIMIZED
    fun setMinimized() {
        focus.clearFocus()
        onStateChange(MobileOrderPanelState.MINIMIZED)
    }
    NavigationBackHandler(rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = !keyboardOpen,
        onBackCompleted = { if (!collapsed) setMinimized() else if (!busy) onClose() })
    Surface(Modifier.fillMaxSize().testTag("order-sheet").semantics {
        stateDescription = if (collapsed) "已最小化" else "已展开"
    }, color = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = colors.Ink) {
        Column(Modifier.fillMaxSize()) {
            DividerLine()
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 4.dp)
                .then(if (collapsed) Modifier.testTag("minimized-order") else Modifier),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).fillMaxHeight()
                    .then(if (collapsed) Modifier.clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button) { onStateChange(MobileOrderPanelState.EDITING) }
                        .testTag("restore-order").semantics { contentDescription = "展开下单面板" }
                    else Modifier), verticalArrangement = Arrangement.Center) {
                    Text(if (instrument.isContinuous) instrument.executionLabel else instrument.symbol,
                        style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val quantity = if (brackets.sizingRisk == null) draft.quantity else calculatedLots?.let(instrument::formatQuantity) ?: "—"
                    Text(if (collapsed) "$quantity ${instrument.quantityUnit} · ${draft.side.label()}" else accountName,
                        style = MaterialTheme.typography.labelSmall, color = colors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (collapsed) {
                    listOf(true, false).forEach { stop ->
                        val requiredStop = stop && brackets.sizingRisk != null
                        val managed = brackets.controls(stop) || requiredStop
                        val checked = if (requiredStop) draft.stopLoss.isNotBlank()
                            else managed || if (stop) draft.stopLossEnabled else draft.takeProfitEnabled
                        val protectionColor = if (stop) colors.Stop else colors.Target
                        IconToggleButton(checked = checked,
                            onCheckedChange = { onDraft(draft.withProtectionEnabled(instrument, quote, stop, it)) },
                            enabled = !busy && !managed, modifier = Modifier.size(48.dp)
                                .testTag(if (stop) "compact-sl" else "compact-tp")
                                .semantics { contentDescription = if (stop) "止损 SL" else "止盈 TP" },
                            colors = IconButtonDefaults.iconToggleButtonColors(
                                containerColor = Color.Transparent, checkedContainerColor = Color.Transparent)) {
                            Surface(Modifier.size(32.dp, 28.dp), shape = MaterialTheme.shapes.small,
                                color = if (checked) protectionColor.copy(alpha = .18f) else Color.Transparent,
                                border = BorderStroke(1.dp, if (checked) protectionColor else MaterialTheme.colorScheme.outline)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(if (stop) "SL" else "TP", style = MaterialTheme.typography.labelMedium,
                                        color = (if (checked) protectionColor else colors.Muted).copy(alpha = if (busy) .4f else 1f))
                                }
                            }
                        }
                    }
                    val enabled = canSubmit && !busy
                    ActionIcon(AppIcons.Send, if (skipConfirmation) "立即下单" else "检查并下单", "compact-submit-order", enabled = enabled,
                        tint = if (enabled) (if (draft.side == Side.BUY) colors.Up else colors.Down) else colors.Muted.copy(alpha = .4f)) {
                        focus.clearFocus(); onSubmit()
                    }
                } else ActionIcon(AppIcons.ExpandMore, "最小化下单面板", "minimize-order", onClick = ::setMinimized)
                ActionIcon(AppIcons.Close, "关闭并放弃本次下单", "close-order", enabled = !busy, onClick = onClose)
            }
            MobileOrderTypes(instrument, quote, capabilities, draft, onDraft, !busy, showSide = collapsed)
            if (!collapsed) Column(Modifier.weight(1f).testTag("order-panel"), content = content)
        }
    }
}

internal val MobileOrderPeekHeight = 97.dp
internal val MobileTradeBarHeight = 68.dp

/**
 * Order kind, plus the side while minimized: the compact dock retains the basic controls, and once expanded the side buttons
 * with their live prices take over the side.
 */
@Composable
private fun MobileOrderTypes(instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities,
    draft: OrderDraft, onDraft: (OrderDraft) -> Unit, enabled: Boolean, showSide: Boolean) {
    val colors = TradingColors
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp).testTag("order-type-strip"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OrderKindChoice(instrument, quote, capabilities, draft, onDraft, enabled, Modifier.weight(3f), height = 36.dp)
        if (showSide) SegmentedChoice(listOf(Side.SELL, Side.BUY), draft.side, { it.label() }, { "order-peek-side-${it.name}" }, { side ->
            // The entry belongs to the side it was placed for: a resting price is rebuilt for the other side.
            val sided = draft.withSide(side, instrument, quote)
            onDraft(if (side == draft.side || draft.kind == OrderKind.MARKET) sided else sided.copy(kind = OrderKind.MARKET).withKind(instrument, quote, draft.kind))
        }, Modifier.weight(2f), enabled, height = 36.dp, activeColor = { if (it == Side.BUY) colors.UpText else colors.DownText })
    }
}
