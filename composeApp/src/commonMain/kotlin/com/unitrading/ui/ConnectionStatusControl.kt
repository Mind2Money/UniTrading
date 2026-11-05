package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.unitrading.broker.MarketSnapshot

@Composable
internal fun ConnectionStatusControl(market: MarketSnapshot, compact: Boolean, busy: Boolean, onSync: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val colors = TradingColors
    val ready = market.connected && market.tradingReady
    val label = if (!market.connected) "已断开" else if (ready) "已连接" else "交易未就绪"
    val color = if (ready) colors.Profit else colors.Loss
    Box {
        val description = "连接状态：$label，点击查看详情"
        if (compact) IconButton({ expanded = !expanded }, Modifier.testTag("connection-status").semantics { contentDescription = description }) { StatusDot(color, size = 10.dp) }
        else TextButton({ expanded = !expanded }, Modifier.testTag("connection-status").semantics { contentDescription = description }) {
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(label, color = color)
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 240.dp, max = 360.dp).testTag("connection-details")) {
            Text(market.statusMessage.ifBlank { label }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            if (onSync != null) DropdownMenuItem(text = { Text(if (busy) "正在同步…" else "同步账户") },
                enabled = !busy && market.connected, modifier = Modifier.testTag("resynchronize-account"),
                leadingIcon = { Icon(AppIcons.Sync, contentDescription = null) },
                onClick = { expanded = false; onSync() })
        }
    }
}
