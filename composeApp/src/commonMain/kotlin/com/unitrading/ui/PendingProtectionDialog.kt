package com.unitrading.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.*

/**
 * New stop loss / take profit prices for a pending entry, each with its estimated result from the entry price. Nothing
 * reaches the broker before [onConfirm]; [current] is the order as the account shows it now.
 */
@Composable
internal fun PendingProtectionDialog(
    edit: PendingProtectionEdit,
    spec: Instrument,
    current: PendingOrder?,
    capabilities: BrokerCapabilities,
    estimate: (stopLoss: Long?, takeProfit: Long?) -> OrderPreview,
    busy: Boolean,
    onEdit: (PendingProtectionEdit) -> Unit,
    onConfirm: (stopLoss: Long?, takeProfit: Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TradingColors
    val request = edit.order.request
    val sl = if (edit.stopLoss.isBlank()) null else spec.parsePrice(edit.stopLoss)
    val tp = if (edit.takeProfit.isBlank()) null else spec.parsePrice(edit.takeProfit)
    val validInput = (edit.stopLoss.isBlank() || sl != null && sl > 0 && sl % spec.tickSize == 0L) &&
        (edit.takeProfit.isBlank() || tp != null && tp > 0 && tp % spec.tickSize == 0L)
    // Brackets the broker keeps as separate orders do not bump the entry's version, so the prices are compared too.
    val validVersion = current != null && current.version == edit.order.version && current.request == request
    val capabilityError = pendingProtectionEditError(capabilities, current ?: edit.order, sl, tp)
    val preview = if (validInput) estimate(sl, tp) else null
    // The broker's estimate when it has one; the same arithmetic otherwise, so a rejected price still shows its result.
    fun result(price: Long?, pnl: Long?): String = price?.let { exit ->
        (pnl ?: request.entryPrice?.let { estimatedExitPnl(spec, request.side, request.lotsHundredths, it, exit) })?.let { "${money(it, true)} USD" } ?: "—"
    } ?: "未设置"
    AlertDialog(
        modifier = Modifier.testTag("pending-protection-dialog"),
        onDismissRequest = onDismiss,
        title = { Text("修改挂单止损 / 止盈") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${request.symbol} · ${request.side.label()}${request.kind.label()} · ${spec.formatQuantity(request.lotsHundredths)} ${spec.quantityUnit} · " +
                "入场 ${request.entryPrice?.let(spec::formatPrice) ?: "—"}", fontSize = 12.sp)
            Text("原 SL ${request.stopLoss?.let(spec::formatPrice) ?: "—"} / TP ${request.takeProfit?.let(spec::formatPrice) ?: "—"}", color = colors.Muted, fontSize = 11.sp)
            PriceInput(if (capabilities.removeProtection) "新止损 SL（留空移除）" else "新止损 SL（保留原价或修改）", edit.stopLoss, { onEdit(edit.copy(stopLoss = it)) }, "pending-protect-sl")
            PriceInput(if (capabilities.removeProtection) "新止盈 TP（留空移除）" else "新止盈 TP（保留原价或修改）", edit.takeProfit, { onEdit(edit.copy(takeProfit = it)) }, "pending-protect-tp")
            if (!capabilities.atomicProtectionEdit) Text("每次修改一条保护线；修改另一条前请等待服务器确认。", fontSize = 11.sp)
            capabilityError?.let { Text(it, color = colors.Loss, fontSize = 11.sp) }
            EstimateRow(if (spec.estimatesIncludeFees) "止损净结果" else "止损毛结果", result(sl, preview?.stopLossPnlMicros))
            EstimateRow(if (spec.estimatesIncludeFees) "止盈净结果" else "止盈毛结果", result(tp, preview?.takeProfitPnlMicros))
            Text("按挂单入场价估算；入场成交后按新的止损/止盈保护。确认后交易商才会校验并更新。", color = colors.Muted, fontSize = 11.sp)
            if (!validVersion) Text("挂单已成交、已撤销或止损/止盈已更新，请取消后重新选择。", color = colors.Loss, fontSize = 11.sp)
            if (!validInput) Text("价格必须为正数并符合品种最小变动单位。", color = colors.Loss, fontSize = 11.sp)
            preview?.errors?.forEach { Text(it, color = colors.Loss, fontSize = 11.sp) }
        } },
        confirmButton = { Button({ onConfirm(sl, tp) }, Modifier.testTag("confirm-pending-protection"),
            enabled = validInput && validVersion && capabilityError == null && preview?.valid == true && !busy) { Text("确认修改") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } },
    )
}
