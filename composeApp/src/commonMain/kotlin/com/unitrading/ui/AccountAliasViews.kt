package com.unitrading.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.unitrading.app.AccountAliases
import kotlinx.coroutines.launch

/** The user's aliases of logins and accounts; screens show them in place of the broker's names. */
internal val LocalAccountAliases = staticCompositionLocalOf { AccountAliases() }

/** A login or account being renamed: [original] is the broker's name, [alias] the alias it has now, if any. */
internal class AliasEdit(val title: String, val original: String, val alias: String?, val save: suspend (String) -> Boolean, val error: () -> String?)

/** The pencil at the end of a picker row: it renames the row without choosing it. No tooltip, which could take the next click. */
@Composable
internal fun RenameButton(label: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.testTag(tag)) { Icon(AppIcons.Edit, label, Modifier.size(18.dp), tint = TradingColors.Muted) }
}

/** Sets or clears an alias. A save that fails keeps the dialog open with the reason. */
@Composable
internal fun AliasDialog(edit: AliasEdit, onDone: () -> Unit) {
    var text by remember(edit) { mutableStateOf(edit.alias.orEmpty()) }
    var saving by remember(edit) { mutableStateOf(false) }
    var failure by remember(edit) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(value: String) {
        if (saving) return
        saving = true
        scope.launch {
            if (edit.save(value)) onDone() else { failure = edit.error() ?: "别名未保存。"; saving = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDone() },
        title = { Text(edit.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("原名：${edit.original}", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                OutlinedTextField(text, { if (it.length <= AccountAliases.MAX_LENGTH) text = it }, Modifier.fillMaxWidth().testTag("alias-field"),
                    label = { Text("别名") }, placeholder = { Text(edit.original) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { save(text) }))
                Text("只保存在本机，留空即恢复原名。", style = MaterialTheme.typography.bodySmall, color = TradingColors.Muted)
                failure?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = TradingColors.Loss) }
            }
        },
        confirmButton = { TextButton({ save(text) }, Modifier.testTag("alias-save"), enabled = !saving) { Text("保存") } },
        dismissButton = {
            Row {
                if (edit.alias != null) TextButton({ save("") }, Modifier.testTag("alias-reset"), enabled = !saving) { Text("恢复原名") }
                TextButton(onDone, Modifier.testTag("alias-cancel"), enabled = !saving) { Text("取消") }
            }
        },
    )
}
