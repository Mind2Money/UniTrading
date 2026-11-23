package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.unitrading.app.ChartSession

/** Each chart keeps its own session. */
@Composable
internal fun ChartSessionControls(
    session: ChartSession,
    onSession: (ChartSession) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chipColors = AssistChipDefaults.assistChipColors(containerColor = colors.surfaceContainerHigh.copy(alpha = .94f),
        labelColor = colors.onSurface, leadingIconContentColor = colors.onSurfaceVariant)
    var sessionOpen by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { sessionOpen = true }, modifier = Modifier.testTag("chart-session-menu"),
            label = { Text(if (session == ChartSession.REGULAR) "RTH" else "ETH") },
            leadingIcon = { Icon(AppIcons.History, "图表交易时段", Modifier.size(18.dp)) },
            colors = chipColors, border = BorderStroke(1.dp, colors.outlineVariant))
        DropdownMenu(expanded = sessionOpen, onDismissRequest = { sessionOpen = false }) {
            ChartSession.entries.forEach { option ->
                DropdownMenuItem(text = { Text("${if (option == ChartSession.REGULAR) "RTH" else "ETH"} · ${option.label}") },
                    onClick = { sessionOpen = false; onSession(option) },
                    trailingIcon = { if (option == session) Icon(AppIcons.Check, "当前时段", tint = colors.primary) },
                    modifier = Modifier.testTag("chart-session-${option.name}"))
            }
        }
    }
}
