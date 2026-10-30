package com.example.profilescouter

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun RecognitionPanel(pipeline: FaceRecognitionPipeline, onDismiss: () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("server_connection", 0)
    var error by remember { mutableStateOf<String?>(null) }
    val packet = pipeline.latest
    val bitmap = remember(packet?.requestId) { packet?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) } }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("顔JPEG・認識") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("顔の周囲25%を含むJPEGを作ります。PC送信は明示的に選ぶまで行いません。")
            Button(onClick = { error = null; pipeline.changeMode(RecognitionMode.PREVIEW) }) { Text("切り抜き確認（送信なし）") }
            Button(onClick = { error = null; pipeline.changeMode(RecognitionMode.MOCK) }) { Text("仮応答でプロフィール表示") }
            Button(onClick = {
                try {
                    normalizeServerUrl(preferences.getString("base_url", "").orEmpty())
                    error = null
                    pipeline.changeMode(RecognitionMode.LIVE)
                } catch (_: Exception) { error = "先に「PC接続」でURLを設定してください。" }
            }) { Text("PCへ自動送信を開始") }
            TextButton(onClick = { pipeline.changeMode(RecognitionMode.OFF) }) { Text("停止して表示をクリア") }
            Text(error ?: pipeline.status)
            bitmap?.let { Image(it.asImageBitmap(), "顔の送信候補JPEG", Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
            packet?.let { Text("顔${it.number} / ${it.jpeg.size / 1024} KB\nrequestId: ${it.requestId}") }
            Text("仮応答は実際の人物識別ではありません。画面を閉じても選択中の処理は続きます。")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } })
}
