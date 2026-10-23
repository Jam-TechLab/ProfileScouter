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
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
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
    var spatialMode by rememberSaveable { mutableStateOf(false) }
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
                if (spatialMode) SpatialScouter(Modifier.fillMaxSize())
                else CameraPreview(Modifier.fillMaxSize())
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
                Text("顔検出テスト・人物識別は未接続", color = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.65f)).padding(6.dp))
            }
            if (granted && showDemo && !spatialMode) {
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
                    Text("これは仮データです。顔の枠は検出結果に追従します。", color = Color.LightGray)
                }
            }
            if (granted) {
                Row(modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { spatialMode = !spatialMode }) {
                        Text(if (spatialMode) "通常カメラへ" else "空間ARを試す")
                    }
                    if (!spatialMode) Button(onClick = { showDemo = !showDemo }) {
                        Text(if (showDemo) "仮表示を隠す" else "仮表示を出す")
                    }
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
    var faces by remember { mutableStateOf<List<Face>>(emptyList()) }
    val detector = remember(context) {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build())
    }
    val controller = remember(context) {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        var active = true
        val executor = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(executor, MlKitAnalyzer(
            listOf(detector), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, executor
        ) { result ->
            if (active) {
                faces = result.getValue(detector)?.toList() ?: emptyList()
                error = if (result.getThrowable(detector) != null)
                    "顔検出に失敗しました。アプリを再起動してください。" else null
            }
        })
        try {
            controller.bindToLifecycle(lifecycleOwner)
        } catch (exception: Exception) {
            error = "カメラを起動できませんでした。アプリを再起動してください。"
        }
        onDispose {
            active = false
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            faces = emptyList()
        }
    }
    DisposableEffect(detector) { onDispose { detector.close() } }
    Box(modifier) {
        AndroidView(factory = {
            PreviewView(it).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            faces.forEach { face ->
                val box = face.boundingBox
                drawRect(
                    color = Color(0xFF6CFFD0),
                    topLeft = Offset(box.left.toFloat(), box.top.toFloat()),
                    size = Size(box.width().toFloat(), box.height().toFloat()),
                    style = Stroke(width = 3.dp.toPx())
                )
            }
        }
        Text(
            if (faces.isEmpty()) "顔をカメラに映してください" else "顔を ${faces.size} 人検出中（人物識別は未接続）",
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 20.dp, bottom = 90.dp)
                .background(Color.Black.copy(alpha = 0.7f)).padding(8.dp)
        )
        error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
    }
}
