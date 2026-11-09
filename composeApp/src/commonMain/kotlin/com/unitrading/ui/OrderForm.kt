package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unitrading.broker.*

/**
 * The order form of every layout: its fields scroll and the review button stays below them. The phone sheet shows the
 * order kind in its own header, so only the desktop ticket asks for [showKinds].
 */
@Composable
internal fun OrderForm(
    instrument: Instrument,
    quote: Quote?,
    capabilities: BrokerCapabilities,
    draft: OrderDraft,
    onDraft: (OrderDraft) -> Unit,
    preview: OrderPreview?,
    busy: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    environment: BrokerEnvironment = BrokerEnvironment.LOCAL_DEMO,
    capacity: QuantityCapacityState = QuantityCapacityState(instrument.maxLotsHundredths),
    onRefreshCapacity: (() -> Unit)? = null,
    skipConfirmation: Boolean = false,
    showKinds: Boolean = false,
    detailsOpen: Boolean = false,
) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    val focus = LocalFocusManager.current
    var details by remember(instrument.symbol) { mutableStateOf(detailsOpen) }
    val error = if (instrument.isContinuous && instrument.executionSymbol == null) "${instrument.executionLabel}，暂不可下单"
        else if (preview == null) "请输入有效数量与价格" else preview.errors.firstOrNull()
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("order-fields")
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (instrument.isContinuous) Text("图表 / 指标 ${instrument.symbol} · 下单 / 报价 ${instrument.executionLabel}", color = colors.Accent, style = type.bodySmall)
            if (showKinds) OrderKindChoice(instrument, quote, capabilities, draft, onDraft, !busy, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Side.SELL, Side.BUY).forEach { side ->
                    OrderSideButton(side, quote?.let { instrument.formatPrice(if (side == Side.SELL) it.bid else it.ask) } ?: "—",
                        selected = draft.side == side, enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("order-side-${side.name}")) { onDraft(draft.withSide(side, instrument, quote)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val less = draft.steppedQuantity(instrument, false)
                // Only a limit the server has given stops the plus button: the broker still checks every order.
                val more = draft.steppedQuantity(instrument, true, capacity.maximum?.takeIf { it > 0 } ?: instrument.maxLotsHundredths)
                QuantityStep(false, !busy && less != null) { less?.let { onDraft(draft.copy(quantity = it)) } }
                PriceInput("数量（${instrument.quantityUnit}）", draft.quantity, { onDraft(draft.copy(quantity = it)) }, "order-quantity",
                    Modifier.weight(1f), enabled = !busy, integer = instrument.quantityDecimals == 0, centered = true)
                QuantityStep(true, !busy && more != null) { more?.let { onDraft(draft.copy(quantity = it)) } }
            }
            OrderQuantitySlider(instrument, draft, onDraft, enabled = !busy, capacity = capacity, onRefresh = onRefreshCapacity)
            if (draft.kind != OrderKind.MARKET) PriceInput(if (draft.kind == OrderKind.LIMIT) "限价" else "突破触发价",
                draft.entry, { onDraft(draft.copy(entry = it)) }, "order-entry", enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrderProtectionControl(true, instrument, quote, draft, onDraft, !busy, Modifier.weight(1f))
                OrderProtectionControl(false, instrument, quote, draft, onDraft, !busy, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ quote?.let { onDraft(draft.withReferenceProtection(instrument, it)) } },
                    enabled = quote != null && !busy, modifier = Modifier.testTag("reference-protection")) { Text("设置参考 SL / TP") }
                Spacer(Modifier.weight(1f))
                TextButton({ onDraft(draft.clearProtection()) },
                    enabled = !busy && (draft.stopLossEnabled || draft.takeProfitEnabled || draft.stopLoss.isNotBlank() || draft.takeProfit.isNotBlank()),
                    modifier = Modifier.testTag("clear-protection")) { Text("清除") }
            }
            DividerLine()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { details = !details }
                .testTag("order-estimates-toggle"), verticalAlignment = Alignment.CenterVertically) {
                Text("预估与合约信息", Modifier.weight(1f), style = type.titleSmall)
                Icon(if (details) AppIcons.ExpandLess else AppIcons.ExpandMore, contentDescription = if (details) "收起" else "展开", tint = colors.Muted)
            }
            if (details) Column(Modifier.testTag("order-estimates"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val currency = instrument.currency
                EstimateRow("可下单上限 / 步长", "${capacity.maximum?.let(instrument::formatQuantity) ?: "未知"} / ${instrument.formatQuantity(instrument.lotStepHundredths)} ${instrument.quantityUnit}")
                EstimateRow("点差", quote?.let { instrument.formatPrice(it.ask - it.bid) } ?: "—")
                EstimateRow(if (instrument.estimatesIncludeFees) "止损净盈亏" else "止损毛盈亏", preview?.stopLossPnlMicros?.let { "${money(it, true)} $currency" } ?: "未设置")
                EstimateRow(if (instrument.estimatesIncludeFees) "止盈净盈亏" else "止盈毛盈亏", preview?.takeProfitPnlMicros?.let { "${money(it, true)} $currency" } ?: "未设置")
                val risk = preview?.stopLossPnlMicros
                val reward = preview?.takeProfitPnlMicros
                EstimateRow("收益 / 风险", if (risk != null && reward != null && risk < 0 && reward > 0)
                    formatFixed((reward.toDouble() / -risk * 100).toLong(), 2) else "—")
                EstimateRow("预计保证金", preview?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} $currency" } ?: "未知 / 服务器校验")
                EstimateRow("双边手续费", preview?.takeIf { it.feesKnown }?.let { "${money(it.feesMicros)} $currency" } ?: "未知")
                Text(if (draft.kind == OrderKind.MARKET) "按当前报价估算，成交价可能变化。" else "按当前报价估算；挂单不预占保证金，触发时重新校验资金与止损/止盈。",
                    color = colors.Muted, style = type.bodySmall)
            }
            preview?.warnings?.forEach { Text(it, color = colors.Muted, style = type.bodySmall) }
            preview?.errors?.drop(1)?.forEach { Text(it, color = colors.Loss, style = type.bodySmall) }
        }
        DividerLine()
        Column(Modifier.fillMaxWidth().testTag("order-actions").padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (error != null) Text(error, color = colors.Loss, style = type.bodySmall, modifier = Modifier.testTag("order-error"))
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${draft.side.label()} · ${draft.kind.label()} · ${draft.quantity} ${instrument.quantityUnit}", style = type.bodySmall, color = colors.Muted)
                Text(preview?.entryPrice?.let(instrument::formatPrice) ?: "—", style = type.titleSmall)
            }
            val sideColor = if (draft.side == Side.BUY) colors.Up else colors.Down
            Button({ focus.clearFocus(); onSubmit() }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("review-order"),
                enabled = preview?.valid == true && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = sideColor, contentColor = contentOn(sideColor))) {
                Text(if (busy) "处理中…" else (if (skipConfirmation) "" else "检查并") + (if (environment == BrokerEnvironment.LOCAL_DEMO) "模拟" else "") + draft.side.label())
            }
        }
    }
}

/** Market, limit or stop: one control for every layout, offering the kinds the broker supports. */
@Composable
internal fun OrderKindChoice(instrument: Instrument, quote: Quote?, capabilities: BrokerCapabilities, draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 40.dp) {
    SegmentedChoice(OrderKind.entries.filter { it in capabilities.orderKinds }, draft.kind, { it.label() }, { "order-kind-${it.name}" },
        { onDraft(draft.withKind(instrument, quote, it)) }, modifier, enabled, height)
}

/** Side with its live price: picks the order side and never submits by itself. */
@Composable
internal fun OrderSideButton(side: Side, price: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = TradingColors
    val color = if (side == Side.SELL) colors.Down else colors.Up
    val shape = MaterialTheme.shapes.medium
    Surface(modifier.clip(shape).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = shape, color = color.copy(alpha = if (selected) .18f else .06f),
        border = BorderStroke(if (selected) 2.dp else 1.dp, color.copy(alpha = if (selected) 1f else .4f))) {
        Column(Modifier.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(side.label(), color = color, style = MaterialTheme.typography.labelLarge)
                Text(if (side == Side.SELL) "BID" else "ASK", color = colors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(price, color = colors.Ink, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuantityStep(increase: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(onClick, Modifier.size(48.dp).testTag(if (increase) "quantity-increase" else "quantity-decrease"), enabled = enabled,
        shape = MaterialTheme.shapes.medium) {
        Icon(if (increase) AppIcons.Add else AppIcons.Remove, contentDescription = if (increase) "增加数量" else "减少数量")
    }
}

/** Numeric field for quantities and prices; the Done key just dismisses the keyboard. */
@Composable
internal fun PriceInput(label: String, value: String, onValue: (String) -> Unit, tag: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, integer: Boolean = false, centered: Boolean = false) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onValue, modifier.fillMaxWidth().testTag(tag), enabled = enabled, label = { Text(label) }, singleLine = true,
        textStyle = LocalTextStyle.current.copy(textAlign = if (centered) TextAlign.Center else TextAlign.Start),
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
}
