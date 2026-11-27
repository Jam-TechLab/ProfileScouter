/*
 * 余白の共通値と、設定シート・操作行・選択肢・通知などの再利用できるUI部品を定義する。
 */
package com.example.profilescouter.ui.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal object ScouterSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** 長い履歴はドラッグで閉じない画面に表示し、縦操作を本文のスクロールに使う。 */
@Composable
internal fun ScouterHistoryPage(title: String, subtitle: String, onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ScouterSpace.lg, vertical = ScouterSpace.sm),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
                key(title) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())
                        .padding(horizontal = ScouterSpace.lg).padding(bottom = ScouterSpace.lg),
                        verticalArrangement = Arrangement.spacedBy(ScouterSpace.md)) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        content()
                    }
                }
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScouterSheet(title: String, subtitle: String, onDismiss: () -> Unit,
    dismissEnabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (dismissEnabled) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { dismissEnabled || it != SheetValue.Hidden }),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.86f)
            .imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = ScouterSpace.lg).padding(bottom = ScouterSpace.lg),
            verticalArrangement = Arrangement.spacedBy(ScouterSpace.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = dismissEnabled, onClick = onDismiss) { Text("閉じる") }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
internal fun ScouterSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ScouterSpace.sm)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
internal fun ScouterAction(title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(title) }, supportingContent = { Text(description) },
            trailingContent = { Text("開く", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
    }
}

@Composable
internal fun ScouterSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(ScouterSpace.md), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ScouterSpace.md)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ScouterSpace.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
internal fun ScouterNotice(message: String, isError: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium,
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(ScouterSpace.md), style = MaterialTheme.typography.bodyMedium)
    }
}
