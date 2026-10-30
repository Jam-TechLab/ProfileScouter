package com.example.profilescouter

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

@Composable
internal fun ConnectionPanel(snapshot: CameraSnapshot, onDismiss: () -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("server_connection", 0)
    var url by remember { mutableStateOf(preferences.getString("base_url", "").orEmpty()
        .removePrefix("http://").let { if (preferences.getString("base_url", "").orEmpty().startsWith("https://"))
            preferences.getString("base_url", "").orEmpty() else it }) }
    var busy by remember { mutableStateOf(false) }
    var healthPath by remember { mutableStateOf(preferences.getString("health_path", "/health").orEmpty()) }
    var imagePath by remember { mutableStateOf(preferences.getString("recognition_path", "/recognize").orEmpty()) }
    var result by remember { mutableStateOf("PCのURLを設定し、接続テストを押してください。") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var jpeg by remember { mutableStateOf<ByteArray?>(null) }
    val scope = rememberCoroutineScope()
    fun saveSettings(): String {
        val base = normalizeServerUrl(url)
        val health = normalizeEndpointPath(healthPath)
        val image = normalizeEndpointPath(imagePath)
        preferences.edit().putString("base_url", base).putString("health_path", health)
            .putString("recognition_path", image).apply()
        return base
    }
    DisposableEffect(Unit) { onDispose { preview?.recycle() } }

    fun execute(action: suspend () -> String) {
        scope.launch {
            busy = true
            result = "処理中…"
            try { result = action() }
            catch (_: TimeoutCancellationException) { result = "カメラ画像を取得できませんでした。カメラを起動し直して試してください。" }
            catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                result = when (exception) {
                    is java.net.ConnectException -> "PCに接続できません。APIの起動・IP・Wi-Fi・ファイアウォールを確認してください。"
                    is java.net.SocketTimeoutException -> "応答がタイムアウトしました。"
                    else -> exception.message ?: exception.javaClass.simpleName
                }
            } finally { busy = false }
        }
    }

    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Python接続テスト") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, enabled = !busy,
                    label = { Text("PCのアドレス") }, placeholder = { Text("192.168.1.10:8000") },
                    supportingText = { Text("http:// は省略できます。HTTPSの場合は https:// を指定。") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                Text("同じWi-FiのPCへ接続します。ここではカメラ画像全体を手動送信します。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("人の横へ結果を反映する場合は、設定の「顔JPEG・認識モード」からPC自動送信を開始してください。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = healthPath, onValueChange = { healthPath = it }, enabled = !busy,
                    label = { Text("接続確認パス（GET）") }, placeholder = { Text("/hello") }, singleLine = true)
                OutlinedTextField(value = imagePath, onValueChange = { imagePath = it }, enabled = !busy,
                    label = { Text("画像送信パス（POST）") }, placeholder = { Text("/image") }, singleLine = true)
                TextButton(enabled = !busy, onClick = { execute { saveSettings(); "接続先を保存しました" } }) {
                    Text("設定を保存")
                }
                Button(enabled = !busy, onClick = {
                    execute {
                        val base = saveSettings()
                        val response = withContext(Dispatchers.IO) { RecognitionClient(base, healthPath, imagePath).health() }
                        "接続成功\n$response"
                    }
                }) { Text("接続テスト") }
                Button(enabled = !busy, onClick = {
                    execute {
                        val bitmap = snapshot.bitmap()
                        val bytes = try { withContext(Dispatchers.Default) { bitmapToJpeg(bitmap) } }
                            catch (exception: Exception) { bitmap.recycle(); throw exception }
                        preview?.recycle()
                        preview = bitmap
                        jpeg = bytes
                        "JPEG準備完了：${bitmap.width} × ${bitmap.height} / ${bytes.size / 1024} KB\n画像を確認して送信してください。"
                    }
                }) { Text("今のカメラ画像を取得") }
                preview?.let { Image(it.asImageBitmap(), contentDescription = "送信前のカメラ画像",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                Button(enabled = !busy && jpeg != null, onClick = {
                    execute {
                        val base = saveSettings()
                        val id = UUID.randomUUID().toString()
                        val bytes = requireNotNull(jpeg)
                        val response = withContext(Dispatchers.IO) { RecognitionClient(base, healthPath, imagePath).recognize(bytes, id) }
                        val json = JSONObject(response)
                        if (json.has("requestId")) check(json.getString("requestId") == id) { "応答のrequestIdが一致しません" }
                        "受信成功（${bytes.size / 1024} KB送信）\n${json.toString(2)}"
                    }
                }) { Text("このJPEGを1枚送信") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(result)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("閉じる") } }
    )
}
