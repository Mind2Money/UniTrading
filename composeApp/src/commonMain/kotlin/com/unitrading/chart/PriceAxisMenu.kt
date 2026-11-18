package com.unitrading.chart

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** The price-axis menu is shared by touch and desktop and never changes broker-side prices. */
@Composable
internal fun PriceAxisMenu(
    expanded: Boolean,
    page: Int,
    settings: PriceAxisSettings,
    automatic: Boolean,
    logarithmAvailable: Boolean,
    onPage: (Int) -> Unit,
    onDismiss: () -> Unit,
    onChange: (PriceAxisSettings) -> Unit,
    onAuto: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(8.dp)) {
        fun mark(selected: Boolean, label: String) = (if (selected) "✓  " else "     ") + label
        if (page == 0) {
            DropdownMenuItem(text = { Text(mark(automatic, "自动适应屏幕")) }, onClick = onAuto)
            DropdownMenuItem(text = { Text(mark(settings.lockRatio, "锁定价格与 K 线比例")) }, onClick = { onChange(settings.copy(lockRatio = !settings.lockRatio)) })
            DropdownMenuItem(text = { Text(mark(settings.chartOnly, "仅缩放价格图表")) }, onClick = { onChange(settings.copy(chartOnly = !settings.chartOnly)) })
            DropdownMenuItem(text = { Text(mark(settings.inverted, "反转价格轴")) }, onClick = { onChange(settings.copy(inverted = !settings.inverted)) })
            HorizontalDivider()
            listOf(PriceScaleMode.REGULAR to "常规", PriceScaleMode.PERCENT to "百分比", PriceScaleMode.INDEXED to "指数化为 100", PriceScaleMode.LOGARITHMIC to "对数").forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(mark(settings.mode == mode, label)) }, enabled = mode != PriceScaleMode.LOGARITHMIC || logarithmAvailable,
                    onClick = { onChange(settings.copy(mode = mode)); onDismiss() })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text(if (settings.left) "价格轴移到右侧" else "价格轴移到左侧") }, onClick = { onChange(settings.copy(left = !settings.left)); onDismiss() })
            DropdownMenuItem(text = { Text("标签与线条  ›") }, onClick = { onPage(1) })
            DropdownMenuItem(text = { Text(mark(settings.showPlus, "+ 价格操作按钮")) }, onClick = { onChange(settings.copy(showPlus = !settings.showPlus)) })
        } else {
            DropdownMenuItem(text = { Text("‹  返回") }, onClick = { onPage(0) })
            DropdownMenuItem(text = { Text(mark(settings.showLastPrice, "最新价格标签")) }, onClick = { onChange(settings.copy(showLastPrice = !settings.showLastPrice)) })
            DropdownMenuItem(text = { Text(mark(settings.showCountdown, "K 线倒计时")) }, onClick = { onChange(settings.copy(showCountdown = !settings.showCountdown)) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(mark(settings.showPriceLine, "最新价格线")) }, onClick = { onChange(settings.copy(showPriceLine = !settings.showPriceLine)) })
            DropdownMenuItem(text = { Text(mark(settings.showBidAsk, "卖价线")) }, onClick = { onChange(settings.copy(showBidAsk = !settings.showBidAsk)) })
        }
    }
}
