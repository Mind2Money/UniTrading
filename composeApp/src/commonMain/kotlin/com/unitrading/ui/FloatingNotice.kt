package com.unitrading.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal enum class NoticeTone { INFO, ATTENTION }
internal data class TradingNotice(val id: Long, val message: String, val tone: NoticeTone = NoticeTone.INFO, val persistent: Boolean = false)

/** An in-app overlay: only the card consumes pointer input, so the rest of the workbench stays interactive. */
@Composable
internal fun FloatingNoticeCard(
    notice: TradingNotice,
    tag: String,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    title: String = "通知",
    action: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val attention = notice.tone == NoticeTone.ATTENTION
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dismiss by rememberUpdatedState(onDismiss)
    val accessibility = LocalAccessibilityManager.current
    val timeout = accessibility?.calculateRecommendedTimeoutMillis(8_000L, containsIcons = false, containsText = true, containsControls = true) ?: 8_000L
    LaunchedEffect(notice.id, notice.persistent, hovered, timeout) {
        if (!notice.persistent && !hovered && dismiss != null) { delay(timeout); dismiss?.invoke() }
    }
    Surface(modifier.fillMaxWidth().testTag("$tag-card").hoverable(interaction)
        .pointerInput(Unit) { detectTapGestures() }
        .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium, color = if (attention) scheme.errorContainer else scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface, shadowElevation = 6.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Icon(if (attention) AppIcons.ErrorOutline else AppIcons.Info, contentDescription = null, Modifier.padding(top = 12.dp).size(20.dp),
                tint = if (attention) scheme.error else scheme.primary)
            Column(Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(notice.message, Modifier.fillMaxWidth().testTag(tag), style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                action?.invoke()
                if (onDismiss != null) ActionIcon(AppIcons.Close, "关闭$title", "dismiss-$tag", onClick = onDismiss)
            }
        }
    }
}
