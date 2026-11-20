/*
 * 会話録音の許可・開始停止、送信待ちの再送、会った人と共有会話履歴を表示する。
 */
package com.example.profilescouter

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.profilescouter.ui.designsystem.*
import java.text.DateFormat
import java.util.Date

private fun conversationTime(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
internal fun ConversationScreen(model: ConversationViewModel, canRecord: Boolean, onDismiss: () -> Unit) {
    var person by remember { mutableStateOf<Encounter?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.arm()
    }
    ScouterSheet(person?.name ?: "会話と履歴", if (person == null) "認識した相手との会話を録音し、要約を履歴に残します。"
        else "この人物の共有履歴です。別の端末が追加した会話も表示します。", onDismiss) {
        if (person == null) {
            ScouterNotice(model.status)
            if (model.uploadStatus.isNotBlank()) Text(model.uploadStatus, style = MaterialTheme.typography.bodySmall)
            Text("録音を相手に伝えてから開始してください。画面を消す・アプリを切り替えると録音は停止します。",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { if (model.enabled) model.disable() else permission.launch(Manifest.permission.RECORD_AUDIO) },
                enabled = canRecord || model.enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (model.enabled) "自動録音を停止" else "自動録音を有効にする")
            }
            if (!canRecord) Text("先に認識モードで「PCで認識」を開始してください。")
            Text("「PCで認識」で人物IDが分かった1人が対象です。録音中はAR画面にも表示します。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.recordingName == null) {
                Text("見失い猶予：${model.graceSeconds}秒")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20).forEach { seconds -> FilterChip(selected = model.graceSeconds == seconds,
                        onClick = { model.configure(seconds, model.maxMinutes) }, label = { Text("${seconds}秒") }) }
                }
                Text("1ファイルの上限：${model.maxMinutes}分（続けて見えていれば次の録音を開始）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5).forEach { minutes -> FilterChip(selected = model.maxMinutes == minutes,
                        onClick = { model.configure(model.graceSeconds, minutes) }, label = { Text("${minutes}分") }) }
                }
            }
            if (model.pending.isNotEmpty()) {
                Text("送信待ち", style = MaterialTheme.typography.titleMedium)
                model.pending.toList().forEach { item ->
                    Text("${item.name} · ${conversationTime(item.startedAt)}", style = MaterialTheme.typography.titleSmall)
                    Text(item.error ?: "送信・要約待ち", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = item.error != null, onClick = { model.retry(item.id) }) { Text("再送") }
                        TextButton(enabled = item.error != null, onClick = { deleteId = item.id }) { Text("音声を削除") }
                    }
                }
            }
            HorizontalDivider()
            Text("この端末で会った人", style = MaterialTheme.typography.titleMedium)
            if (model.encounters.isEmpty()) Text("人物ID付きの認識結果を受け取ると、ここに追加されます。")
            model.encounters.sortedByDescending { it.lastSeen }.forEach { entry ->
                ScouterAction(entry.name, "最終認識：${conversationTime(entry.lastSeen)}") {
                    person = entry; model.loadHistory(entry)
                }
            }
        } else {
            val selected = person!!
            Row {
                TextButton(onClick = { person = null }) { Text("一覧へ戻る") }
                TextButton(onClick = { model.loadHistory(selected) }, enabled = !model.historyLoading) { Text("更新") }
            }
            model.historyError?.let { ScouterNotice(it, isError = true) }
            if (model.historyEntries.isEmpty() && !model.historyLoading && model.historyError == null)
                Text("会話履歴はまだありません。")
            model.historyEntries.forEach { entry ->
                Text(conversationTime(entry.endedAt), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(entry.summary, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
            }
            if (model.historyLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.historyNext != null) TextButton(enabled = !model.historyLoading,
                onClick = { model.loadHistory(selected, more = true) }) { Text("以前の会話を読み込む") }
        }
    }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("送信待ちの音声を削除しますか？") },
        text = { Text("この端末の音声だけを削除します。PC側で処理済みの場合、会話履歴は残ります。") },
        confirmButton = { TextButton(onClick = { model.discard(id); deleteId = null }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("キャンセル") } }) }
}
