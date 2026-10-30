package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, Modifier.testTag("activity-back")) { Text("返回设置") }
            Text("活动记录", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(accountName, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = TradingColors.Muted, fontSize = 12.sp)
        DividerLine()
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("activity-list")) {
            if (events.isEmpty()) item { EmptyState("暂无活动", "这里记录交易指令、触发情况和拒绝原因。") }
            items(events.asReversed()) { event ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${dateTime(event.timeMs)} UTC", fontSize = 10.sp, color = TradingColors.Muted)
                    Text(event.message, fontSize = 12.sp)
                }
                DividerLine()
            }
        }
    }
}
