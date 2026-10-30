package com.example.profilescouter

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScouterSettings(showBoxes: Boolean, detectionCount: Int, status: String,
    onBoxesChanged: (Boolean) -> Unit, onConnection: () -> Unit, onRecognition: () -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("スカウター設定", style = MaterialTheme.typography.headlineSmall)
            Text("接続と表示を調整", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onConnection, modifier = Modifier.fillMaxWidth()) { Text("PC接続・通信テスト") }
            FilledTonalButton(onClick = onRecognition, modifier = Modifier.fillMaxWidth()) { Text("顔JPEG・認識モード") }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("顔の検出枠", style = MaterialTheme.typography.titleMedium)
                    Text("検出 ${detectionCount}個・追跡IDを表示", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = showBoxes, onCheckedChange = onBoxesChanged)
            }
            Text(status, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("表示位置と認識結果をリセット") }
        }
    }
}
