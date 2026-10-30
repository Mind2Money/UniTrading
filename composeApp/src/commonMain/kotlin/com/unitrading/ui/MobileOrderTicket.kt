package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*
import com.unitrading.chart.Chevron

/** Mobile ticket: only the fields scroll; review is always reachable with one thumb. */
@Composable
internal fun MobileOrderTicket(
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
) {
    val colors = TradingColors
    val focus = LocalFocusManager.current
    var details by remember(instrument.symbol) { mutableStateOf(false) }
    val error = if (preview == null) "请输入有效数量与价格" else preview.errors.firstOrNull()
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("order-fields")
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().selectableGroup().background(colors.Raised, RoundedCornerShape(8.dp)).padding(3.dp)) {
                OrderKind.entries.filter { it in capabilities.orderKinds }.forEach { kind ->
                    val selected = kind == draft.kind
                    Box(Modifier.weight(1f).heightIn(min = 42.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (selected) colors.Surface else Color.Transparent)
                        .selectable(selected, enabled = !busy, role = Role.Tab) { onDraft(draft.withKind(instrument, quote, kind)) }
                        .testTag("order-kind-${kind.name}").padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(kind.label(), color = if (selected) colors.Accent else colors.Muted, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Side.SELL, Side.BUY).forEach { side ->
                    MobileQuoteButton(side, quote?.let { instrument.formatPrice(if (side == Side.SELL) it.bid else it.ask) } ?: "—",
                        selected = draft.side == side, enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("order-side-${side.name}")) { onDraft(draft.withSide(side)) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val less = draft.steppedQuantity(instrument, false)
                val more = draft.steppedQuantity(instrument, true)
                QuantityStep("−", "减少数量", "quantity-decrease", !busy && less != null) { less?.let { onDraft(draft.copy(quantity = it)) } }
                MobilePriceInput("数量（${instrument.quantityUnit}）", draft.quantity, { onDraft(draft.copy(quantity = it)) }, "order-quantity",
                    Modifier.weight(1f), enabled = !busy, integer = instrument.quantityDecimals == 0, centered = true)
                QuantityStep("+", "增加数量", "quantity-increase", !busy && more != null) { more?.let { onDraft(draft.copy(quantity = it)) } }
            }
            if (draft.kind != OrderKind.MARKET) MobilePriceInput(if (draft.kind == OrderKind.LIMIT) "限价" else "突破触发价",
                draft.entry, { onDraft(draft.copy(entry = it)) }, "order-entry", enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MobilePriceInput("止损 SL", draft.stopLoss, { onDraft(draft.copy(stopLoss = it)) }, "order-sl", Modifier.weight(1f), !busy)
                MobilePriceInput("止盈 TP", draft.takeProfit, { onDraft(draft.copy(takeProfit = it)) }, "order-tp", Modifier.weight(1f), !busy)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ quote?.let { onDraft(draft.withReferenceProtection(instrument, it)) } },
                    enabled = quote != null && !busy, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.testTag("reference-protection")) {
                    Text("设置参考 SL / TP", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton({ onDraft(draft.copy(stopLoss = "", takeProfit = "")) },
                    enabled = !busy && (draft.stopLoss.isNotBlank() || draft.takeProfit.isNotBlank()), modifier = Modifier.testTag("clear-protection")) {
                    Text("清除", fontSize = 12.sp)
                }
            }
            DividerLine()
            TextButton({ details = !details }, Modifier.fillMaxWidth().testTag("order-estimates-toggle"), contentPadding = PaddingValues(0.dp)) {
                Text("预估与合约信息", Modifier.weight(1f), textAlign = TextAlign.Start, color = colors.Ink)
                Text(if (details) "收起" else "展开", fontSize = 12.sp, color = colors.Muted)
                Spacer(Modifier.width(6.dp))
                Chevron(down = !details, colors.Muted)
            }
            if (details) Column(Modifier.testTag("order-estimates"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EstimateRow("数量范围 / 步长", "${instrument.formatQuantity(instrument.minLotsHundredths)}–${instrument.formatQuantity(instrument.maxLotsHundredths)} / ${instrument.formatQuantity(instrument.lotStepHundredths)} ${instrument.quantityUnit}")
                EstimateRow("点差", quote?.let { instrument.formatPrice(it.ask - it.bid) } ?: "—")
                EstimateRow(if (instrument.estimatesIncludeFees) "止损净结果" else "止损毛结果", preview?.stopLossPnlMicros?.let { "${money(it, true)} ${instrument.currency}" } ?: "未设置")
                EstimateRow(if (instrument.estimatesIncludeFees) "止盈净结果" else "止盈毛结果", preview?.takeProfitPnlMicros?.let { "${money(it, true)} ${instrument.currency}" } ?: "未设置")
                val risk = preview?.stopLossPnlMicros
                val reward = preview?.takeProfitPnlMicros
                EstimateRow("收益 / 风险", if (risk != null && reward != null && risk < 0 && reward > 0)
                    formatFixed((reward.toDouble() / -risk * 100).toLong(), 2) else "—")
                EstimateRow("预计保证金", preview?.takeIf { it.marginKnown }?.let { "${money(it.marginMicros)} ${instrument.currency}" } ?: "未知 / 服务器校验")
                EstimateRow("双边手续费", preview?.takeIf { it.feesKnown }?.let { "${money(it.feesMicros)} ${instrument.currency}" } ?: "未知")
                Text("按当前报价估算，成交价可能变化。", color = colors.Muted, fontSize = 12.sp)
            }
            preview?.warnings?.forEach { Text(it, color = colors.Muted, fontSize = 12.sp) }
            preview?.errors?.drop(1)?.forEach { Text(it, color = colors.Loss, fontSize = 12.sp) }
        }
        DividerLine()
        Column(Modifier.fillMaxWidth().background(colors.Surface).testTag("order-actions")
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (error != null) Text(error, color = colors.Loss, fontSize = 12.sp, modifier = Modifier.testTag("order-error"))
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${draft.side.label()} · ${draft.kind.label()} · ${draft.quantity} ${instrument.quantityUnit}", fontSize = 12.sp, color = colors.Muted)
                Text(preview?.entryPrice?.let(instrument::formatPrice) ?: "—", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Button({ focus.clearFocus(); onSubmit() }, Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("review-order"),
                enabled = preview?.valid == true && !busy, shape = RoundedCornerShape(10.dp)) {
                Text(if (busy) "处理中…" else if (environment == BrokerEnvironment.LOCAL_DEMO) "检查并模拟${draft.side.label()}" else "检查并${draft.side.label()}", fontSize = 15.sp)
            }
        }
    }
}

@Composable
internal fun MobileQuoteButton(side: Side, price: String, selected: Boolean?, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (side == Side.SELL) TradingColors.Down else TradingColors.Up
    Surface(modifier.clip(RoundedCornerShape(10.dp)).selectable(selected == true, enabled = enabled,
        role = if (selected == null) Role.Button else Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(10.dp), color = color.copy(alpha = if (selected == true) .18f else .06f),
        border = BorderStroke(if (selected == true) 2.dp else 1.dp, color.copy(alpha = if (selected == true) 1f else .5f))) {
        Column(Modifier.heightIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(side.label(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(if (side == Side.SELL) "BID" else "ASK", color = TradingColors.Muted, fontSize = 10.sp)
            }
            Text(price, color = TradingColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuantityStep(text: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = description }, enabled = enabled,
        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(0.dp)) { Text(text, fontSize = 22.sp) }
}

@Composable
private fun MobilePriceInput(label: String, value: String, onValue: (String) -> Unit, tag: String,
    modifier: Modifier = Modifier, enabled: Boolean = true, integer: Boolean = false, centered: Boolean = false) {
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onValue, modifier.fillMaxWidth().testTag(tag), enabled = enabled, label = { Text(label, fontSize = 12.sp) },
        placeholder = { if (tag != "order-quantity" && tag != "order-entry") Text("可选", color = TradingColors.Muted, fontSize = 12.sp) },
        singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, textAlign = if (centered) TextAlign.Center else TextAlign.Start),
        keyboardOptions = KeyboardOptions(keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), shape = RoundedCornerShape(10.dp))
}
