package com.unitrading.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.unitrading.broker.BrokerEnvironment

/** Small tonal label for a state or a role; colour never carries the meaning alone, the text does. */
@Composable
internal fun StatusBadge(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Text(text, modifier.background(color.copy(alpha = .14f), MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
        color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}

internal fun BrokerEnvironment.badgeLabel() = when {
    this == BrokerEnvironment.LOCAL_DEMO -> "本地模拟"
    live -> "Live 实盘"
    else -> "远程 Demo"
}

/** Which money is at stake stays visible on every screen; Live takes the error colour on purpose. */
@Composable
internal fun EnvironmentBadge(environment: BrokerEnvironment, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    StatusBadge(environment.badgeLabel(), when {
        environment == BrokerEnvironment.LOCAL_DEMO -> colors.primary
        environment.live -> colors.error
        else -> colors.tertiary
    }, modifier)
}

/** Brand mark: the rising candles on a primary tile. */
@Composable
internal fun AppLogo(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(size).background(colors.primary, RoundedCornerShape(size * .28f)), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Logo, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(size * .78f))
    }
}

/** Icon-only action: 48dp touch target, a tooltip on hover or long press, and [label] for screen readers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActionIcon(
    icon: ImageVector, label: String, tag: String? = null, modifier: Modifier = Modifier, enabled: Boolean = true,
    tint: Color = Color.Unspecified, iconSize: Dp = 24.dp, onClick: () -> Unit,
) {
    // Not focusable: a focusable tooltip shown by hover would swallow the click on the button under it.
    TooltipBox(TooltipDefaults.rememberPlainTooltipPositionProvider(), { PlainTooltip { Text(label) } }, rememberTooltipState(), focusable = false) {
        IconButton(onClick, modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier), enabled = enabled) {
            Icon(icon, contentDescription = label, Modifier.size(iconSize), tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
        }
    }
}

/** Page header: one title per screen, with the account or context as its subtitle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageTopBar(
    title: String, subtitle: String? = null, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, backTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        modifier = modifier,
        navigationIcon = { if (onBack != null) ActionIcon(AppIcons.ArrowBack, "返回", backTag, onClick = onBack) },
        actions = actions, expandedHeight = 56.dp, windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
    )
}

/** A figure with its caption, as used in the account summary and the status bar. */
@Composable
internal fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, valueTag: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, if (valueTag != null) Modifier.testTag(valueTag) else Modifier, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
    }
}

/** Section subhead of a list; [count] sits beside it so the title stays scannable. */
@Composable
internal fun ListSubheader(title: String, modifier: Modifier = Modifier, count: Int? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 16.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (count != null) "$title $count" else title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun EmptyState(title: String, detail: String, modifier: Modifier = Modifier, icon: ImageVector = AppIcons.Inbox) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp).size(24.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A filled dot next to its label: connection and running states. */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}

/** Single choice among a few short options. */
@Composable
internal fun <T> SegmentedChoice(
    options: List<T>, selected: T?, label: (T) -> String, tag: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, height: Dp = 40.dp, activeColor: (T) -> Color? = { null },
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, option ->
            val active = activeColor(option)
            SegmentedButton(option == selected, { onSelect(option) }, SegmentedButtonDefaults.itemShape(index, options.size),
                Modifier.height(height).testTag(tag(option)), enabled,
                colors = if (active == null) SegmentedButtonDefaults.colors() else SegmentedButtonDefaults.colors(
                    activeContainerColor = active.copy(alpha = .18f), activeContentColor = active),
                contentPadding = PaddingValues(horizontal = 4.dp), icon = {},
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

/**
 * Form with many fields: a full-screen dialog on phones and a centred one on wider windows, the title bar carrying
 * close and save either way, so the save action stays reachable while the form scrolls.
 */
@Composable
internal fun FormDialog(
    title: String, confirmLabel: String, confirmEnabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, dialogTag: String, cancelTag: String,
    confirmTag: String, dismissEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit,
) {
    Dialog({ if (dismissEnabled) onDismiss() }, DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val fullScreen = maxWidth < 600.dp
            Surface(if (fullScreen) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).fillMaxHeight(.9f),
                shape = if (fullScreen) RoundedCornerShape(0.dp) else MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxSize().imePadding().testTag(dialogTag)) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionIcon(AppIcons.Close, "关闭", cancelTag, enabled = dismissEnabled, onClick = onDismiss)
                        Text(title, Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onConfirm, Modifier.testTag(confirmTag), enabled = confirmEnabled) { Text(confirmLabel) }
                    }
                    DividerLine()
                    content()
                }
            }
        }
    }
}
