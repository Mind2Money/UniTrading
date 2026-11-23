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
import com.unitrading.app.ChartTimeZone

/** Session belongs to a chart; the display time zone is shared by the workspace. */
@Composable
internal fun ChartSessionControls(
    session: ChartSession,
    zone: ChartTimeZone,
    networkTimeMs: Long?,
    onSession: (ChartSession) -> Unit,
    onTimeZone: (ChartTimeZone) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val chipColors = AssistChipDefaults.assistChipColors(containerColor = colors.surfaceContainerHigh.copy(alpha = .94f),
        labelColor = colors.onSurface, leadingIconContentColor = colors.onSurfaceVariant)
    var sessionOpen by remember { mutableStateOf(false) }
    var zoneOpen by remember { mutableStateOf(false) }
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
    Box {
        AssistChip(onClick = { zoneOpen = true }, modifier = Modifier.testTag("chart-timezone-menu"),
            label = { Text(zone.labelAt(networkTimeMs), maxLines = 1) },
            leadingIcon = { Icon(AppIcons.Language, "图表时区", Modifier.size(18.dp)) },
            colors = chipColors, border = BorderStroke(1.dp, colors.outlineVariant))
        DropdownMenu(expanded = zoneOpen, onDismissRequest = { zoneOpen = false }) {
            ChartTimeZone.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.labelAt(networkTimeMs)) },
                    onClick = { zoneOpen = false; onTimeZone(option) },
                    trailingIcon = { if (option == zone) Icon(AppIcons.Check, "当前时区", tint = colors.primary) },
                    modifier = Modifier.testTag("chart-timezone-${option.name}"))
            }
        }
    }
}
