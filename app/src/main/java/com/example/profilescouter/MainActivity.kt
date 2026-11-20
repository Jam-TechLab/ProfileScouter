/*
 * アプリの起動、カメラ権限の確認、AR・接続設定・プロフィール登録の画面切り替えを担当する。
 */
package com.example.profilescouter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.profilescouter.ui.theme.ProfileScouterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversations = ViewModelProvider(this)[ConversationViewModel::class.java]
        setContent { ProfileScouterTheme { ScouterScreen(conversations) } }
    }
}

@Composable
private fun ScouterScreen(conversations: ConversationViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun permissionGranted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(permissionGranted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val snapshot = remember { CameraSnapshot() }
    var showConnection by remember { mutableStateOf(false) }
    var showProfile by rememberSaveable { mutableStateOf(false) }
    if (showProfile) {
        OwnProfileScreen(onClose = { showProfile = false })
        return
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = permissionGranted()
            if (event == Lifecycle.Event.ON_STOP) conversations.foregroundEnded()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
            if (granted) {
                SpatialScouter(Modifier.fillMaxSize(), snapshot, onOpenConnection = { showConnection = true },
                    onOpenProfile = { showProfile = true }, conversations = conversations)
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("カメラへのアクセス", style = MaterialTheme.typography.titleLarge)
                    Text("スカウターの映像表示にカメラを使用します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { showProfile = true }) { Text("マイプロフィールを登録") }
                    Button(onClick = {
                        requested = true
                        launcher.launch(Manifest.permission.CAMERA)
                    }) { Text("カメラを許可") }
                    if (requested) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")))
                    }) { Text("アプリの設定を開く") }
                }
            }
        }
    }
    if (showConnection && granted) ConnectionPanel(snapshot, onDismiss = { showConnection = false })
}
