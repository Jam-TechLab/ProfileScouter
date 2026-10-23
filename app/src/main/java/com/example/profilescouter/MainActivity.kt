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
import androidx.camera.core.CameraSelector
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.profilescouter.ui.theme.ProfileScouterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ProfileScouterTheme { ScouterScreen() } }
    }
}

@Composable
private fun ScouterScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    fun permissionGranted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(permissionGranted()) }
    var requested by rememberSaveable { mutableStateOf(false) }
    var showDemo by rememberSaveable { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = permissionGranted()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(Modifier.fillMaxSize()) {
            if (granted) {
                CameraPreview(Modifier.fillMaxSize())
            } else {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("カメラへのアクセス", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("スカウターの映像表示にカメラを使用します。", color = Color.White)
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
            Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp)) {
                Text("PROFILE SCOUTER", color = Color(0xFF6CFFD0), style = MaterialTheme.typography.titleLarge)
                Text("表示テスト・顔認識は未接続", color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.65f)).padding(6.dp))
            }
            if (granted && showDemo) {
                Column(
                    Modifier.align(Alignment.CenterEnd).padding(24.dp).widthIn(max = 250.dp)
                        .border(1.dp, Color(0xFF6CFFD0), RoundedCornerShape(12.dp))
                        .background(Color(0xDD102520), RoundedCornerShape(12.dp)).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("DEMO PROFILE", color = Color(0xFF6CFFD0))
                    Text("サンプルユーザー", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("所属：メディア系演習", color = Color.White)
                    Text("趣味：音楽・アプリ開発", color = Color.White)
                    Text("これは仮データです。人物への追従はまだ行いません。", color = Color.LightGray)
                }
            }
            if (granted) {
                Button(onClick = { showDemo = !showDemo }, modifier = Modifier
                    .align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp)) {
                    Text(if (showDemo) "仮プロフィールを隠す" else "仮プロフィールを表示")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var error by remember { mutableStateOf<String?>(null) }
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(0)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (exception: Exception) {
            error = "カメラを起動できませんでした。アプリを再起動してください。"
        }
        onDispose { controller.unbind() }
    }
    Box(modifier) {
        AndroidView(factory = {
            PreviewView(it).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        }, modifier = Modifier.fillMaxSize())
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
    }
}
