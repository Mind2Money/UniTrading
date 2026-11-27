package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.unitrading.app.AccountAliasesController
import com.unitrading.broker.BrokerPlugin
import com.unitrading.broker.BrokerRegistry

/** A two-level tablet account menu that overlays the chart and expands to the left. */
@Composable
internal fun TabletAccountMenu(registry: BrokerRegistry, plugin: BrokerPlugin, accountId: String,
    aliases: AccountAliasesController, enabled: Boolean, onBroker: (String) -> Unit, onAccount: (String) -> Unit) {
    val brokers by registry.state.collectAsState()
    val catalog by key(plugin) { plugin.catalog.collectAsState() }
    val names = LocalAccountAliases.current
    var expanded by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<AliasEdit?>(null) }
    renaming?.let { AliasDialog(it) { renaming = null } }
    val account = catalog.accounts.firstOrNull { it.id == accountId }
    fun close() { expanded = false; section = null }
    Box {
        TextButton(onClick = { if (expanded) close() else expanded = true },
            modifier = Modifier.widthIn(min = 160.dp, max = 230.dp).height(40.dp).testTag("tablet-account-toggle"),
            enabled = enabled, shape = RoundedCornerShape(7.dp),
            colors = ButtonDefaults.textButtonColors(containerColor = TradingColors.Surface),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Text(names.login(plugin.descriptor), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
                Text(account?.let { names.account(plugin.descriptor.id, it) } ?: "选择子账户", maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
            Icon(if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                if (expanded) "收起账户栏" else "展开账户栏", Modifier.size(18.dp))
        }
        if (expanded && enabled) {
            val position = remember { object : PopupPositionProvider {
                override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                    IntOffset((anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                        anchorBounds.bottom.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
            } }
            Popup(popupPositionProvider = position, onDismissRequest = ::close,
                properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = true)) {
                Row(Modifier.testTag("tablet-expanded-accounts"), verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (section != null) Column {
                        if (section == 1) Spacer(Modifier.height(48.dp))
                        Surface(Modifier.width(300.dp).heightIn(max = 400.dp).testTag("tablet-account-choices"),
                            shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp, shadowElevation = 6.dp) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (section == 0) brokers.forEach { broker ->
                                    val descriptor = broker.descriptor
                                    val label = names.login(descriptor)
                                    DropdownMenuItem(text = { Column {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(descriptor.name.takeIf { it != label }, descriptor.environment.label).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                                    } }, onClick = { close(); onBroker(descriptor.id) }, modifier = Modifier.testTag("quick-login-${descriptor.id}"),
                                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (broker === plugin) Icon(AppIcons.Check, "当前登录")
                                            RenameButton("修改 $label 的别名", "rename-login-${descriptor.id}") {
                                                close(); renaming = AliasEdit("登录账户别名", descriptor.name, aliases.aliases.value.logins[descriptor.id],
                                                    { aliases.setLogin(descriptor.id, it) }, { aliases.error.value })
                                            }
                                        } })
                                } else catalog.accounts.forEach { child ->
                                    val label = names.account(plugin.descriptor.id, child)
                                    DropdownMenuItem(text = { Column {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(child.name.takeIf { it != label }, child.id, child.currency, "只读".takeIf { child.readOnly }).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                                    } }, onClick = { close(); onAccount(child.id) }, modifier = Modifier.testTag("quick-account-${child.id}"),
                                        trailingIcon = { Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (child.id == accountId) Icon(AppIcons.Check, "当前交易子账户")
                                            RenameButton("修改 $label 的别名", "rename-account-${child.id}") {
                                                close(); renaming = AliasEdit("交易子账户别名", child.name, aliases.aliases.value.accounts[plugin.descriptor.id]?.get(child.id),
                                                    { aliases.setAccount(plugin.descriptor.id, child.id, it) }, { aliases.error.value })
                                            }
                                        } })
                                }
                            }
                        }
                    }
                    Surface(Modifier.width(224.dp).testTag("tablet-account-levels"), shape = RoundedCornerShape(8.dp),
                        tonalElevation = 3.dp, shadowElevation = 6.dp) {
                        Column {
                            listOf("选择账户" to names.login(plugin.descriptor),
                                "子账户" to (account?.let { names.account(plugin.descriptor.id, it) } ?: "选择子账户")).forEachIndexed { index, (title, value) ->
                                DropdownMenuItem(text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, style = MaterialTheme.typography.labelSmall, color = TradingColors.Muted)
                                    Spacer(Modifier.width(6.dp))
                                    Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                                } }, onClick = { section = if (section == index) null else index },
                                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (index == 0) "quick-login-picker" else "quick-account-picker"),
                                    trailingIcon = { Icon(AppIcons.ChevronLeft, "向左展开", Modifier.size(18.dp)) })
                            }
                        }
                    }
                }
            }
        }
    }
}
