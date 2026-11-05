package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.unitrading.broker.ActivityEvent
import com.unitrading.chart.dateTime

@Composable
internal fun ActivityLogPage(
    accountName: String,
    events: List<ActivityEvent>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(TradingColors.Surface).testTag("activity-page")) {
        PageTopBar("活动记录", accountName, onBack = onBack, backTag = "activity-back")
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("activity-list")) {
            if (events.isEmpty()) item { EmptyState("暂无活动", "这里记录交易指令、触发情况和拒绝原因。", icon = AppIcons.History) }
            items(events.asReversed()) { event ->
                ListItem(headlineContent = { Text(event.message) }, overlineContent = { Text("${dateTime(event.timeMs)} UTC") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                DividerLine()
            }
        }
    }
}
