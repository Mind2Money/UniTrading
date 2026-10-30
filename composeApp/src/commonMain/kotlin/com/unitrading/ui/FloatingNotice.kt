package com.unitrading.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val colors = TradingColors
    val accent = if (notice.tone == NoticeTone.ATTENTION) colors.Loss else colors.Accent
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
        shape = RoundedCornerShape(12.dp), color = colors.Raised, border = BorderStroke(1.dp, accent.copy(alpha = .45f)), shadowElevation = 8.dp) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
                action?.invoke()
                if (onDismiss != null) IconButton(onDismiss, Modifier.size(36.dp).testTag("dismiss-$tag")
                    .semantics { contentDescription = "关闭$title" }) { Text("×", fontSize = 20.sp, color = colors.Muted) }
            }
            Text(notice.message, Modifier.fillMaxWidth().padding(end = 6.dp).testTag(tag), fontSize = 13.sp, lineHeight = 19.sp, color = colors.Ink)
        }
    }
}
