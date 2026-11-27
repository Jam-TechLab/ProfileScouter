/*
 * プロフィールの入力、正面・左右の撮影、内容確認、PCへの登録と結果表示を行う画面。
 */
package com.example.profilescouter.ui.registration

import com.example.profilescouter.core.camera.bitmapToJpeg
import com.example.profilescouter.core.network.normalizeServerUrl
import com.example.profilescouter.model.FaceDirection
import com.example.profilescouter.model.validateRegistration
import com.example.profilescouter.ui.designsystem.ScouterNotice
import com.example.profilescouter.ui.designsystem.ScouterSection
import com.example.profilescouter.ui.designsystem.ScouterSpace

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.*
import java.io.File

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OwnProfileScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val model = remember(context) { ViewModelProvider(context as ComponentActivity)[RegistrationViewModel::class.java] }
    val preferences = remember { context.getSharedPreferences("registration_connection", 0) }
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(preferences.getString("base_url", "").orEmpty().removePrefix("http://")) }
    var name by rememberSaveable { mutableStateOf("") }
    var department by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var photos by rememberSaveable { mutableStateOf(arrayListOf("", "", "")) }
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDirection by rememberSaveable { mutableIntStateOf(0) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmRetry by remember { mutableStateOf(false) }
    val state = model.state
    val busy = preparing || state == RegistrationState.Sending
    val directory = remember { File(context.cacheDir, "profile_capture").apply { mkdirs() } }

    fun removePhoto(path: String?) {
        path?.takeIf { it.isNotEmpty() }?.let {
            val file = File(it)
            if (file.parentFile?.canonicalPath == directory.canonicalPath) file.delete()
        }
    }
    fun clearPhotos() { photos.forEach { removePhoto(it) }; photos = arrayListOf("", "", "") }
    fun leave() { if (!busy) { clearPhotos(); model.reset(); onClose() } }
    fun close() {
        if (!busy) {
            if (state is RegistrationState.Done || (name.isBlank() && department.isBlank() &&
                    description.isBlank() && photos.all { it.isEmpty() })) leave()
            else confirmLeave = true
        }
    }
    BackHandler { if (step == 1 && !busy && state !is RegistrationState.Done) step = 0 else close() }
    LaunchedEffect(Unit) {
        if (submitted && model.state == RegistrationState.Ready) model.markInterrupted()
    }
    LaunchedEffect(state) { if (state is RegistrationState.Done) clearPhotos() }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path = pendingPath
        val direction = pendingDirection
        pendingPath = null
        if (!success || path == null) removePhoto(path)
        else scope.launch {
            preparing = true; error = null
            try {
                val candidate = withContext(Dispatchers.IO) { readRegistrationPhoto(File(path)) }
                try {
                    // Side profiles may be rejected by ML Kit; their final validation belongs to Python.
                    if (direction == FaceDirection.FRONT.ordinal)
                        withContext(NonCancellable) { validateRegistrationFace(candidate) }
                    withContext(Dispatchers.IO) {
                        val bytes = bitmapToJpeg(candidate, maxEdge = 1280)
                        File(path).writeBytes(bytes)
                    }
                } finally { candidate.recycle() }
                removePhoto(photos[direction])
                photos = ArrayList(photos.toMutableList().also { it[direction] = path })
            } catch (exception: CancellationException) { removePhoto(path); throw exception }
            catch (exception: Exception) { removePhoto(path); error = exception.message ?: "写真を準備できませんでした" }
            finally { preparing = false }
        }
    }
    fun capture() {
        try {
            val file = File.createTempFile("registration-", ".jpg", directory)
            pendingPath = file.path
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.profilephotos", file))
        } catch (exception: Exception) { removePhoto(pendingPath); pendingPath = null; error = exception.message }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) capture() else error = "顔写真の撮影にはカメラの許可が必要です"
    }
    fun validate() {
        validateRegistration(name, department, description)
        normalizeServerUrl(address)
        require(photos.all { it.isNotEmpty() && File(it).isFile }) { "正面・左側面・右側面をすべて撮影してください" }
    }
    fun submit() {
        try {
            validate()
            val base = normalizeServerUrl(address)
            preferences.edit().putString("base_url", base).apply()
            error = null; submitted = true
            model.submit(base, name, department, description, photos.toList())
        } catch (exception: Exception) { error = exception.message }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            TopAppBar(title = { Text(if (state is RegistrationState.Done) "登録完了" else "プロフィール登録") },
                navigationIcon = { TextButton(enabled = !busy, onClick = {
                    if (step == 1 && state !is RegistrationState.Done) step = 0 else close()
                }) { Text("戻る") } })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = ScouterSpace.lg),
                verticalArrangement = Arrangement.spacedBy(ScouterSpace.md)) {
                if (state is RegistrationState.Done) {
                    Text("登録できました", style = MaterialTheme.typography.headlineMedium)
                    ScouterNotice("登録ID：${state.personId}\nプロフィールと3方向の顔写真をPCへ登録しました。")
                    Text("登録した人をカメラで認識するには、PC側の認識処理がこのDBを参照する必要があります。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (step == 0) "01 / 入力・3方向の撮影" else "02 / 確認・PCへ登録",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("顔写真3枚とプロフィールを、指定したPCへ送信して登録します。",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (step == 0) {
                        OutlinedTextField(address, { address = it }, enabled = !busy, singleLine = true,
                            label = { Text("登録先PCのアドレス") }, placeholder = { Text("192.168.1.10:5000") },
                            supportingText = { Text("http:// は省略できます。認識用とは別の接続先です。") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(name, { if (it.length <= 100) name = it }, enabled = !busy, singleLine = true,
                            label = { Text("氏名 *") }, supportingText = { Text("${name.length}/100") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(department, { if (it.length <= 100) department = it }, enabled = !busy, singleLine = true,
                            label = { Text("所属・部署") }, supportingText = { Text("${department.length}/100") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(description, { if (it.length <= 1000) description = it }, enabled = !busy,
                            label = { Text("その他の属性") }, supportingText = { Text("${description.length}/1000") },
                            minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
                    } else {
                        ScouterNotice("登録先：${normalizeServerUrl(address)}\n氏名：${name.trim()}\n所属：${department.trim().ifBlank { "未入力" }}\n${description.trim()}")
                    }
                    FaceDirection.entries.forEach { direction ->
                        ScouterSection(direction.label) {
                            Column(Modifier.padding(ScouterSpace.md), verticalArrangement = Arrangement.spacedBy(ScouterSpace.sm)) {
                                RegistrationPhotoPreview(photos[direction.ordinal], direction.label)
                                Text(direction.guide, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (step == 0) OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    pendingDirection = direction.ordinal
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) capture()
                                    else permission.launch(Manifest.permission.CAMERA)
                                }) { Text(if (photos[direction.ordinal].isEmpty()) "${direction.label}を撮影" else "${direction.label}を撮り直す") }
                            }
                        }
                    }
                    Text("撮影はカメラアプリで行います。必要に応じてインカメラへ切り替えてください。左右は本人を基準にしています。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state is RegistrationState.Failed) ScouterNotice(state.message +
                        if (state.uncertain) "\nPC側で登録済みの可能性があります。再送する前に登録状況を確認してください。" else "", isError = true)
                    error?.let { ScouterNotice(it, isError = true) }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (preparing) "写真を準備しています…" else "PCへ登録しています…") }
                }
                Spacer(Modifier.height(ScouterSpace.sm))
            }
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth().padding(ScouterSpace.lg), onClick = {
                if (state is RegistrationState.Done) leave()
                else if (step == 0) {
                    try { validate(); error = null; step = 1 } catch (exception: Exception) { error = exception.message }
                } else if (state is RegistrationState.Failed && state.uncertain) confirmRetry = true
                else submit()
            }) { Text(if (state is RegistrationState.Done) "ARへ戻る" else if (step == 0) "内容を確認" else "この内容でPCへ登録") }
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("入力を破棄しますか？") },
        text = { Text("入力と撮影した一時写真を破棄します。PCに登録済みのデータは削除されません。") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; leave() }) { Text("破棄して戻る") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("編集を続ける") } })
    if (confirmRetry) AlertDialog(onDismissRequest = { confirmRetry = false }, title = { Text("登録を再送しますか？") },
        text = { Text("前回の登録が完了していると、同じ人が重複登録されます。PC側で未登録と確認できた場合だけ再送してください。") },
        confirmButton = { TextButton(onClick = { confirmRetry = false; submit() }) { Text("確認したので再送") } },
        dismissButton = { TextButton(onClick = { confirmRetry = false }) { Text("戻る") } })
}

@Composable
private fun RegistrationPhotoPreview(path: String, label: String) {
    val bitmap by produceState<Bitmap?>(null, path) {
        value = null
        value = if (path.isEmpty()) null else withContext(Dispatchers.IO) { runCatching { readRegistrationPhoto(File(path)) }.getOrNull() }
    }
    val current = bitmap
    DisposableEffect(current) { onDispose { current?.recycle() } }
    if (current != null) Image(current.asImageBitmap(), "$label の撮影画像",
        Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Fit)
    else Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
        Text(if (path.isEmpty()) "未撮影" else "写真を読み込み中", style = MaterialTheme.typography.bodyMedium)
    }
}
