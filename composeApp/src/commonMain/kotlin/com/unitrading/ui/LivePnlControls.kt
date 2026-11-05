package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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
    Column(modifier.fillMaxWidth().testTag("live-pnl-setting")) {
        ListItem(
            headlineContent = { Text("实时盈亏通知") },
            supportingContent = { Text("在系统通知栏 / 灵动岛显示当前账户的浮动盈亏") },
            leadingContent = { Icon(AppIcons.Notifications, contentDescription = null) },
            trailingContent = { Switch(settings.enabled, { enabled -> scope.launch { controller.setEnabled(enabled) } }, Modifier.testTag("live-pnl-switch")) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        error?.let { Text(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = TradingColors.Loss) }
    }
}
