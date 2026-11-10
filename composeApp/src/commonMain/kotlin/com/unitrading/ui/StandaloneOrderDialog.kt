package com.unitrading.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.unitrading.broker.BrokerCapabilities
import com.unitrading.broker.Instrument
import com.unitrading.broker.OrderPreview
import com.unitrading.broker.PendingOrder

/** One order being changed on its own: the [price] and [quantity] as typed. */
internal data class StandaloneOrderEdit(val order: PendingOrder, val price: String, val quantity: String)

/**
 * Changes one order on its own, by its id: a stop or target the broker could not pair with its position, or one whose
 * pairing keeps the position's protection locked. The price always; the quantity where the broker allows it
 * ([BrokerCapabilities.modifyOrderQuantity]). [current] is the order as the account holds it now; once it is gone or has
 * changed, the dialog only says so. [estimate] is the broker's check of the typed values.
 */
@Composable
internal fun StandaloneOrderDialog(
    edit: StandaloneOrderEdit,
    spec: Instrument,
    current: PendingOrder?,
    capabilities: BrokerCapabilities,
    estimate: (price: Long, lotsHundredths: Long) -> OrderPreview,
    busy: Boolean,
    skipConfirmation: Boolean,
    onEdit: (StandaloneOrderEdit) -> Unit,
    onConfirm: (price: Long, lotsHundredths: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = TradingColors
    val type = MaterialTheme.typography
    val order = edit.order
    val request = order.request
    val price = spec.parsePrice(edit.price)
    val lots = if (capabilities.modifyOrderQuantity) spec.parseQuantity(edit.quantity) else request.lotsHundredths
    val preview = if (price != null && lots != null) estimate(price, lots) else null
    val validVersion = current != null && current.version == order.version && current.standaloneEditable
    AlertDialog(
        modifier = Modifier.testTag("standalone-order-dialog"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("单独修改订单") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${request.symbol} · ${order.role.label()} · ${request.side.label()}${request.kind.label()} #${shortId(order.id)}", style = type.titleSmall)
            Text("原价格 ${request.entryPrice?.let(spec::formatPrice) ?: "—"} · 原数量 ${spec.formatQuantity(request.lotsHundredths)} ${spec.quantityUnit}" +
                if (order.filledLotsHundredths > 0) " · 已成交 ${spec.formatQuantity(order.filledLotsHundredths)}" else "", color = colors.Muted, style = type.bodySmall)
            PriceInput("新价格", edit.price, { onEdit(edit.copy(price = it)) }, "standalone-price")
            if (capabilities.modifyOrderQuantity) OutlinedTextField(edit.quantity, { onEdit(edit.copy(quantity = it)) },
                Modifier.fillMaxWidth().testTag("standalone-quantity"), label = { Text("新数量（${spec.quantityUnit}）") }, singleLine = true,
                isError = lots == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            else Text("当前交易商在这里只能改价格，数量保持 ${spec.formatQuantity(request.lotsHundredths)} ${spec.quantityUnit}。", color = colors.Muted, style = type.bodySmall)
            Text("只改这一张单：同一仓位的其他止损、止盈和入场单不变，也不会因此重新配对。确认后按最新报价重新校验。", color = colors.Muted, style = type.bodySmall)
            if (!validVersion) Text("订单已成交、已撤销或已更新，请取消后重新选择。", color = colors.Loss, style = type.bodySmall)
            if (price == null) Text("请输入符合品种精度的有效价格。", color = colors.Loss, style = type.bodySmall)
            if (lots == null) Text("请输入有效数量。", color = colors.Loss, style = type.bodySmall)
            preview?.errors?.forEach { Text(it, color = colors.Loss, style = type.bodySmall) }
            preview?.warnings?.forEach { Text(it, color = if ("反向" in it) colors.Loss else colors.Muted, style = type.bodySmall) }
        } },
        confirmButton = {
            Button({ if (price != null && lots != null) onConfirm(price, lots) }, Modifier.testTag("confirm-standalone-order"),
                enabled = !busy && validVersion && preview?.valid == true, colors = brandButtonColors()) { Text(if (skipConfirmation) "应用修改" else "确认修改") }
        },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } },
    )
}
