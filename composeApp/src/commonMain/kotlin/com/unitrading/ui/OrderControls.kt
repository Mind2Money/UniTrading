package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.Instrument
import com.unitrading.broker.Quote

@Composable
internal fun OrderQuantitySlider(instrument: Instrument, draft: OrderDraft, onDraft: (OrderDraft) -> Unit, enabled: Boolean,
    capacity: QuantityCapacityState = QuantityCapacityState(instrument.maxLotsHundredths), onRefresh: (() -> Unit)? = null) {
    val scale = QuantitySliderScale.from(instrument, capacity.maximum)
    val focus = LocalFocusManager.current
    Column {
        if (onRefresh != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(capacity.message ?: capacity.maximum?.let { "当前最多 ${instrument.formatQuantity(it)} ${instrument.quantityUnit}" } ?: "可下单数量未知",
                Modifier.weight(1f).testTag("order-quantity-capacity"), fontSize = 10.sp, color = TradingColors.Muted)
            TextButton(onRefresh, enabled = enabled && !capacity.loading, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(if (capacity.loading) "计算中…" else "刷新", fontSize = 11.sp)
            }
        }
        if (scale == null) return@Column
        Slider(value = scale.fraction(instrument.parseQuantity(draft.quantity) ?: scale.minimum),
            onValueChange = { fraction -> scale.quantity(fraction)?.let {
                focus.clearFocus()
                onDraft(draft.copy(quantity = instrument.formatQuantity(it)))
            } }, enabled = enabled && scale.intervals > 0,
            // Drawing thousands of tick marks makes large futures/FX ranges unusable; quantity still snaps to every step.
            steps = if (scale.intervals in 2..100) scale.intervals.toInt() - 1 else 0,
            modifier = Modifier.fillMaxWidth().height(32.dp).testTag("order-quantity-slider").semantics {
                contentDescription = "下单数量滑动条"
                stateDescription = "${draft.quantity} ${instrument.quantityUnit}"
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${instrument.formatQuantity(scale.minimum)} ${instrument.quantityUnit}", fontSize = 10.sp, color = TradingColors.Muted)
            Text("步长 ${instrument.formatQuantity(scale.step)}", fontSize = 10.sp, color = TradingColors.Muted)
            Text("${instrument.formatQuantity(scale.maximum)} ${instrument.quantityUnit}", fontSize = 10.sp, color = TradingColors.Muted)
        }
    }
}

/** Unchecked protection is excluded from the request, but retains its last price for re-enabling. */
@Composable
internal fun OrderProtectionControl(
    stop: Boolean, instrument: Instrument, quote: Quote?, draft: OrderDraft, onDraft: (OrderDraft) -> Unit,
    enabled: Boolean, modifier: Modifier = Modifier,
) {
    val checked = if (stop) draft.stopLossEnabled else draft.takeProfitEnabled
    val tag = if (stop) "order-sl" else "order-tp"
    val title = if (stop) "止损 SL" else "止盈 TP"
    val focus = LocalFocusManager.current
    Column(modifier) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("$tag-enabled")
            .toggleable(checked, enabled = enabled, role = Role.Checkbox) {
                focus.clearFocus()
                onDraft(draft.withProtectionEnabled(instrument, quote, stop, it))
            }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(end = 6.dp))
            Text(title, fontSize = 13.sp)
        }
        if (checked) PriceInput("价格", if (stop) draft.stopLoss else draft.takeProfit,
            { onDraft(draft.withProtectionPrice(stop, it)) }, tag, enabled = enabled)
    }
}
