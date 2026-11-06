/*
 * PCでの認識・デモ・写真確認のモードを選び、開始・停止と送信候補画像の確認を行う画面。
 */
package com.example.profilescouter

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.profilescouter.ui.designsystem.*

@Composable
internal fun RecognitionPanel(pipeline: FaceRecognitionPipeline, onDismiss: () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("server_connection", 0)
    var selected by remember { mutableStateOf(if (pipeline.mode == RecognitionMode.OFF) RecognitionMode.LIVE else pipeline.mode) }
    var error by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    val packet = pipeline.latest
    val bitmap = remember(packet?.requestId, details) {
        if (details) packet?.let { BitmapFactory.decodeByteArray(it.jpeg, 0, it.jpeg.size) } else null
    }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    ScouterSheet("認識モード", "使い方を選び、準備ができたら開始してください。", onDismiss) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(ScouterSpace.sm)) {
        ScouterModeOption("PCで認識", "顔写真を接続先のPCへ送り、プロフィールを表示", selected == RecognitionMode.LIVE) {
            selected = RecognitionMode.LIVE; error = null
        }
        ScouterModeOption("デモで試す", "通信せず、仮プロフィールで表示を確認", selected == RecognitionMode.MOCK) {
            selected = RecognitionMode.MOCK; error = null
        }
        ScouterModeOption("顔写真を確認", "切り抜いた写真だけを確認・送信なし", selected == RecognitionMode.PREVIEW) {
            selected = RecognitionMode.PREVIEW; details = true; error = null
        }
        }
        error?.let { ScouterNotice(it, isError = true) }
        Button(onClick = {
            try {
                if (selected == RecognitionMode.LIVE) {
                    normalizeServerUrl(preferences.getString("base_url", "").orEmpty())
                    normalizeEndpointPath(preferences.getString("recognition_path", "/recognize").orEmpty())
                }
                if (pipeline.mode != selected) pipeline.changeMode(selected)
                error = null
                if (selected != RecognitionMode.PREVIEW) onDismiss()
            } catch (_: Exception) { error = "設定の「PCに接続」で、先に接続先を保存してください。" }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (pipeline.mode == selected) "このモードで続ける" else "このモードで開始") }
        if (pipeline.mode != RecognitionMode.OFF) {
            ScouterNotice(pipeline.status)
            TextButton(onClick = { pipeline.changeMode(RecognitionMode.OFF) }) { Text("認識を停止") }
        }
        TextButton(onClick = { details = !details }) { Text(if (details) "送信画像の詳細を閉じる" else "送信画像の詳細") }
        if (details) {
            bitmap?.let { Image(it.asImageBitmap(), "顔写真の切り抜き", Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
            Text(packet?.let { "顔${it.number} · ${it.jpeg.size / 1024} KB" } ?: "顔を検出すると、ここに写真を表示します。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
