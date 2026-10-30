package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.broker.MarketSnapshot

@Composable
internal fun ConnectionStatusControl(market: MarketSnapshot, compact: Boolean, busy: Boolean, onSync: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val colors = TradingColors
    val ready = market.connected && market.tradingReady
    val label = if (!market.connected) "已断开" else if (ready) "已连接" else "交易未就绪"
    val color = if (ready) colors.Profit else colors.Loss
    Box {
        TextButton({ expanded = !expanded }, Modifier.testTag("connection-status").height(36.dp)
            .then(if (compact) Modifier.width(30.dp) else Modifier)
            .semantics { contentDescription = "连接状态：$label，点击查看详情" },
            contentPadding = PaddingValues(horizontal = if (compact) 4.dp else 8.dp)) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            if (!compact) { Spacer(Modifier.width(6.dp)); Text(label, fontSize = 11.sp, color = color) }
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.widthIn(min = 240.dp, max = 360.dp).testTag("connection-details")) {
            Text(market.statusMessage.ifBlank { label }, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 12.sp, color = colors.Ink)
            if (onSync != null) DropdownMenuItem(text = { Text(if (busy) "正在同步…" else "同步账户") },
                enabled = !busy && market.connected, modifier = Modifier.testTag("resynchronize-account"),
                onClick = { expanded = false; onSync() })
        }
    }
}
