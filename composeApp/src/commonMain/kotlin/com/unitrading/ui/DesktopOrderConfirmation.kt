package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderKind
import com.unitrading.broker.OrderPreview
import com.unitrading.broker.OrderRequest
import com.unitrading.broker.Side
import com.unitrading.broker.money

/** Explain what will be rechecked without changing the order or weakening the broker's checks. */
internal fun placeConfirmationDisclosure(order: OrderRequest, bracketsApply: Boolean, bracketsEnabled: Boolean, sizeByRisk: Boolean): String = when {
    sizeByRisk && bracketsApply && order.kind == OrderKind.MARKET -> "提交时按最新报价复核风险，必要时减少手数。"
    bracketsApply && order.kind == OrderKind.MARKET -> "提交时按最新报价重算自动止损止盈，实际成交价可能与预估不同。"
    bracketsEnabled && !bracketsApply -> (if (sizeByRisk) "减仓单按输入的数量提交，不按风险计算手数。" else "减仓单不附加子账户止损止盈。") +
        "提交时会按最新报价和账户状态重新校验。"
    else -> "提交时会按最新报价和账户状态重新校验，实际成交价可能与预估不同。"
}

/** Desktop order review; the caller still refreshes and validates the order again when submitted. */
@Composable
internal fun DesktopOrderConfirmation(
    title: String, loginName: String, accountName: String, environment: String,
    instrument: Instrument, order: OrderRequest, preview: OrderPreview?, disclosure: String,
    keyboard: Boolean, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit,
) {
    val colors = TradingColors
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val sideColor = if (order.side == Side.BUY) colors.Up else colors.Down
    val actionColor = actionFill(sideColor)
    Dialog(onDismissRequest = { if (!busy) onCancel() }) {
        Surface(Modifier.widthIn(min = 340.dp, max = 500.dp).fillMaxWidth().heightIn(max = 680.dp)
            .testTag("desktop-order-confirmation"), shape = RoundedCornerShape(18.dp),
            color = colors.Surface, shadowElevation = 12.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.Border.copy(alpha = .65f))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = colors.Ink)
                        Text("请核对订单信息", color = colors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    ActionIcon(AppIcons.Close, "关闭下单确认", "cancel-order-confirmation", enabled = !busy,
                        tint = scheme.onSurfaceVariant) { onCancel() }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("交易账户", color = colors.Muted, style = MaterialTheme.typography.labelMedium)
                        Text("$loginName / $accountName / $environment", color = colors.Ink, style = MaterialTheme.typography.bodyMedium)
                        order.sourceSymbol?.let {
                            Text("图表 / 指标 $it → 下单 ${order.symbol}", color = colors.Accent, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(Modifier.fillMaxWidth().background(scheme.surfaceContainerLow, RoundedCornerShape(12.dp))
                        .border(1.dp, colors.Border.copy(alpha = .45f), RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("${order.symbol} · ${order.side.label()}${order.kind.label()} · ${instrument.formatQuantity(order.lotsHundredths)} ${instrument.quantityUnit}",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.Ink)
                        HorizontalDivider(color = colors.Border.copy(alpha = .6f))
                        order.entryPrice?.let { EstimateRow("委托价格", instrument.formatPrice(it)) }
                        EstimateRow("当前预估入场", preview?.entryPrice?.let(instrument::formatPrice) ?: "—")
                        val estimated = if (order.kind == OrderKind.MARKET) "（预估）" else ""
                        EstimateRow("止损价$estimated", order.stopLoss?.let(instrument::formatPrice) ?: "未设置")
                        EstimateRow("止盈价$estimated", order.takeProfit?.let(instrument::formatPrice) ?: "未设置")
                        EstimateRow("预计保证金 USD", preview?.takeIf { it.marginKnown }?.let { money(it.marginMicros) } ?: "未知 / 服务器校验")
                    }
                    Text(disclosure, style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                    if (keyboard) Text("键盘下单：按 Enter 确认，按 Esc 取消。", Modifier.testTag("confirm-keys-hint"),
                        style = MaterialTheme.typography.bodySmall, color = colors.Muted)
                    if (!preview?.errors.isNullOrEmpty()) {
                        Column(Modifier.fillMaxWidth().background(colors.Loss.copy(alpha = .09f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            preview?.errors?.forEach { Text(it, Modifier.testTag("confirm-error"), color = colors.Loss, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    preview?.warnings?.forEach { Text(it, Modifier.testTag("confirm-warning"), color = colors.Caution, style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = colors.Border.copy(alpha = .55f))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onCancel, enabled = !busy, modifier = Modifier.testTag("cancel-order-review")) { Text("取消") }
                    Spacer(Modifier.width(10.dp))
                    Button(onConfirm, enabled = !busy && (preview == null || preview.valid),
                        modifier = Modifier.testTag("confirm-action").focusRequester(focus),
                        colors = ButtonDefaults.buttonColors(containerColor = actionColor, contentColor = contentOn(actionColor))) {
                        Text("确认${order.side.label()}")
                    }
                }
                if (keyboard) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            }
        }
    }
}
