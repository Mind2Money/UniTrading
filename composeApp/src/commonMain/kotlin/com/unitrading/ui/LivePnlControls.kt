package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unitrading.app.LivePnlController
import kotlinx.coroutines.launch

/** Toggle for the system P&L notice; rendered only on platforms that run a presenter (Android, iOS). */
@Composable
internal fun LivePnlSwitch(controller: LivePnlController, modifier: Modifier = Modifier) {
    val available by controller.available.collectAsState()
    if (!available) return
    val settings by controller.settings.collectAsState()
    val error by controller.error.collectAsState()
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth().testTag("live-pnl-setting"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("实时盈亏通知", fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("在系统通知栏 / 灵动岛显示当前账户的浮动盈亏", fontSize = 12.sp, lineHeight = 17.sp, color = TradingColors.Muted)
            }
            Spacer(Modifier.width(12.dp))
            Switch(settings.enabled, { enabled -> scope.launch { controller.setEnabled(enabled) } }, Modifier.testTag("live-pnl-switch"))
        }
        error?.let { Text(it, fontSize = 11.sp, color = TradingColors.Loss) }
    }
}
