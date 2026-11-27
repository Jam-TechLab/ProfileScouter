/*
 * 会話録音の許可・開始停止、送信待ちの再送、会った人と共有会話履歴を表示する。
 */
package com.example.profilescouter.ui.conversation

import com.example.profilescouter.model.Encounter
import com.example.profilescouter.ui.designsystem.ScouterHistoryPage
import com.example.profilescouter.ui.designsystem.ScouterNotice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun conversationTime(ms: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@Composable
internal fun ConversationScreen(model: ConversationViewModel, onDismiss: () -> Unit) {
    var person by remember { mutableStateOf<Encounter?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var resendId by remember { mutableStateOf<String?>(null) }
    var showPending by remember { mutableStateOf(false) }
    var showRecording by remember { mutableStateOf(false) }
    BackHandler(enabled = person != null) { person = null }
    ScouterHistoryPage(person?.name ?: "会話と履歴", if (person == null) "認識した相手との会話を録音し、要約を履歴に残します。"
        else "この人物の共有履歴です。別の端末が追加した会話も表示します。", onDismiss) {
        if (person == null) {
            Text("会った人", style = MaterialTheme.typography.titleMedium)
            if (model.encounters.isNotEmpty()) Text("顔写真は、カメラで人物を認識したときに保存されます。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.avatarErrors.isNotEmpty()) ScouterNotice(
                "顔写真を保存できない人物がいます。カメラでもう一度認識してください。\n${model.avatarErrors.values.first()}", isError = true)
            if (model.encounters.isEmpty()) Text("カメラで人物を認識すると、ここに追加されます。")
            model.encounters.sortedByDescending { it.lastSeen }.chunked(3).forEach { entries ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    entries.forEach { entry ->
                        PersonAvatar(entry, model, Modifier.weight(1f)) { person = entry; model.loadHistory(entry) }
                    }
                    repeat(3 - entries.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            HorizontalDivider()
            TextButton(onClick = { showRecording = !showRecording }) {
                Text(if (showRecording) "録音設定を閉じる" else "録音設定")
            }
            if (showRecording) {
            ScouterNotice(model.recordingLabel)
            model.recordingPersonId?.let { Text("録音対象：人物ID $it", style = MaterialTheme.typography.labelMedium) }
            if (model.uploadStatus.isNotBlank()) Text(model.uploadStatus, style = MaterialTheme.typography.bodySmall)
            if (model.uploadingId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.recordingName != null) {
                OutlinedButton(onClick = { model.disable() }, modifier = Modifier.fillMaxWidth()) {
                    Text("録音を終了して送信")
                }
            }
            if (model.lastRecording.isNotBlank()) Text("直前の録音\n${model.lastRecording}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("録音を相手に伝えてから開始してください。画面を消す・アプリを切り替えると録音は停止します。",
                style = MaterialTheme.typography.bodyMedium)
            Text("録音を始めるときはカメラ画面へ戻り、「会話録音を開始」を押してください。")
            Text("「PCで認識」で人物IDが分かった1人が対象です。録音中はAR画面にも表示します。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.recordingName == null) {
                Text("顔を見失ってから送信するまで：${model.graceSeconds}秒")
                Text("一時的に顔が外れても録音を続けます。別の人物や複数の顔を認識した場合は区切ります。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 30, 60, 120).forEach { seconds -> FilterChip(selected = model.graceSeconds == seconds,
                        onClick = { model.configure(seconds, model.maxMinutes) }, label = { Text(if (seconds < 60) "${seconds}秒" else "${seconds / 60}分") }) }
                }
                Text("1ファイルの上限：${model.maxMinutes}分（続けて見えていれば次の録音を開始）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 5, 10).forEach { minutes -> FilterChip(selected = model.maxMinutes == minutes,
                        onClick = { model.configure(model.graceSeconds, minutes) }, label = { Text("${minutes}分") }) }
                }
            }
            }
            if (model.pending.isNotEmpty()) TextButton(onClick = { showPending = !showPending }) {
                Text(if (showPending) "送信状況・エラーを閉じる" else "送信状況・エラー（${model.pending.size}件）")
            }
            if (showPending) model.pending.sortedByDescending { it.endedAt }.forEach { item ->
                    Text("${item.name} · ${conversationTime(item.startedAt)}", style = MaterialTheme.typography.titleSmall)
                    Text(model.audioDetails(item), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (model.checkingId == item.id) "保存済みの結果を確認中（音声は再送しません）"
                        else if (item.error != null && item.awaitingResult) "結果未確認 · PCで処理中の場合があります"
                        else if (item.error != null) "未送信の音声を端末に保存しています"
                        else if (model.uploadingId == item.id) model.uploadStatus else "順番待ち",
                        style = MaterialTheme.typography.bodySmall)
                    if (item.error != null) {
                        var expanded by remember(item.id) { mutableStateOf(false) }
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "詳細を閉じる" else "エラーの詳細") }
                        if (expanded) Text("会話ID：${item.id}\n${item.error}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        if (item.awaitingResult) TextButton(enabled = item.error != null && model.checkingId == null,
                            onClick = { model.checkResult(item.id) }) { Text("結果を確認") }
                        TextButton(enabled = item.error != null && model.checkingId != item.id,
                            onClick = { if (item.awaitingResult) resendId = item.id else model.retry(item.id) }) { Text("再送") }
                        TextButton(enabled = item.error != null && model.checkingId != item.id, onClick = { deleteId = item.id }) { Text("音声を削除") }
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
    resendId?.let { id -> AlertDialog(onDismissRequest = { resendId = null },
        title = { Text("音声を再送しますか？") },
        text = { Text("PCで処理が続いている可能性があります。先に「結果を確認」を使い、PC側で処理の停止・失敗を確認してから再送してください。") },
        confirmButton = { TextButton(onClick = { model.retry(id, allowUnconfirmed = true); resendId = null }) { Text("再送する") } },
        dismissButton = { TextButton(onClick = { resendId = null }) { Text("待つ") } }) }
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null },
        title = { Text("送信待ちの音声を削除しますか？") },
        text = { Text("この端末の音声だけを削除します。PC側で処理済みの場合、会話履歴は残ります。") },
        confirmButton = { TextButton(onClick = { model.discard(id); deleteId = null }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { deleteId = null }) { Text("キャンセル") } }) }
}

@Composable
private fun PersonAvatar(person: Encounter, model: ConversationViewModel, modifier: Modifier, onClick: () -> Unit) {
    val path = model.avatar(person).absolutePath
    val revision = model.avatarRevision
    val bitmap by produceState<Bitmap?>(null, path, revision) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
    }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(72.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                val image = bitmap
                if (image != null) Image(image.asImageBitmap(), "${person.name}の顔写真", Modifier.fillMaxSize().clip(CircleShape),
                    contentScale = ContentScale.Crop)
                else Box(contentAlignment = Alignment.Center) { Text(person.name.take(1), style = MaterialTheme.typography.headlineSmall) }
            }
            Text(person.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
            Text("履歴を見る", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
