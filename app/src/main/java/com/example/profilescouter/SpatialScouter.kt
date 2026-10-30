package com.example.profilescouter

import android.graphics.Paint
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import io.github.sceneview.ar.ARScene
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.exp

private data class SpatialPerson(
    val number: Int,
    var trackingId: Int?,
    var anchor: Anchor?,
    var lastSeen: Long,
    var estimatedDepth: Boolean,
    var depthMeters: Float,
    var faceWorld: FloatArray,
    var cardOffset: FloatArray,
    var targetLocal: FloatArray? = null,
    var renderedLocal: FloatArray? = null,
    var hits: Int = 1
)

private data class DetectedFaceBox(val trackingId: Int?, val left: Float, val top: Float,
    val right: Float, val bottom: Float)

private data class ProjectedProfile(
    val number: Int, val x: Float, val y: Float,
    val width: Float, val height: Float, val estimated: Boolean, val depthMeters: Float
)

/** Uses ARCore camera frames exclusively; CameraX is disposed before entering this mode. */
@Composable
internal fun SpatialScouter(modifier: Modifier = Modifier, snapshot: CameraSnapshot) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val detector = remember {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking().build())
    }
    val people = remember { mutableListOf<SpatialPerson>() }
    var cards by remember { mutableStateOf<List<ProjectedProfile>>(emptyList()) }
    var status by remember { mutableStateOf("端末をゆっくり動かして周囲を認識してください") }
    var failure by remember { mutableStateOf<String?>(null) }
    var resetVersion by remember { mutableIntStateOf(0) }
    var showFaceBoxes by remember { mutableStateOf(true) }
    var detectedBoxes by remember { mutableStateOf<List<DetectedFaceBox>>(emptyList()) }
    var detectionCount by remember { mutableIntStateOf(0) }
    val runtime = remember { SpatialRuntime() }
    val captureExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    DisposableEffect(snapshot) {
        snapshot.capture = { callback ->
            runtime.captureRequest?.invoke(Result.failure(IllegalStateException("撮影要求が更新されました")))
            runtime.captureRequest = callback
        }
        onDispose {
            snapshot.capture = null
            runtime.captureRequest?.invoke(Result.failure(IllegalStateException("カメラを終了しました")))
            runtime.captureRequest = null
            captureExecutor.shutdown()
        }
    }
    DisposableEffect(detector) {
        onDispose {
            runtime.active = false
            people.forEach { it.anchor?.detach() }
            people.clear()
            detector.close()
        }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        ARScene(
            modifier = Modifier.fillMaxSize(),
            planeRenderer = false,
            sessionConfiguration = { session, config ->
                // Rendering must not wait for the next camera exposure.
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC))
                    Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            },
            onSessionFailed = {
                failure = "空間ARを開始できませんでした。ARCore対応端末とGoogle Play開発者サービス（AR）を確認してください。\n${it.javaClass.simpleName}"
            },
            onSessionPaused = { cards = emptyList(); detectedBoxes = emptyList() },
            onSessionUpdated = { session, frame ->
                runtime.captureRequest?.let { callback ->
                    try {
                        val rotation = cameraImageRotation(context, session.cameraConfig.cameraId, hostView.display?.rotation)
                        val image = frame.acquireCameraImage()
                        runtime.captureRequest = null
                        try {
                            captureExecutor.execute {
                                val result = runCatching { image.use { cameraImageToBitmap(it, rotation) } }
                                executor.execute { callback(result) }
                            }
                        } catch (exception: Exception) {
                            image.close()
                            callback(Result.failure(exception))
                        }
                    } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
                        // Retry on the next AR frame until the caller's timeout.
                    } catch (exception: Exception) {
                        runtime.captureRequest = null
                        callback(Result.failure(exception))
                    }
                }
                if (runtime.resetVersion != resetVersion) {
                    runtime.resetVersion = resetVersion
                    people.forEach { it.anchor?.detach() }
                    people.clear()
                    cards = emptyList()
                    detectedBoxes = emptyList()
                    detectionCount = 0
                }
                val camera = frame.camera
                if (camera.trackingState != TrackingState.TRACKING) {
                    cards = emptyList()
                    detectedBoxes = emptyList()
                    status = "空間の追跡待ち：明るい場所で端末をゆっくり動かしてください"
                } else {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val view = runtime.viewMatrix
                    val projection = runtime.projectionMatrix
                    camera.getViewMatrix(view, 0)
                    camera.getProjectionMatrix(projection, 0, 0.05f, 100f)
                    val vp = runtime.viewProjectionMatrix
                    Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
                    // Screen right/up differ from sensor right/up by 90 degrees in portrait.
                    val right = camera.displayOrientedPose.rotateVector(floatArrayOf(1f, 0f, 0f))
                    val up = camera.displayOrientedPose.rotateVector(floatArrayOf(0f, 1f, 0f))
                    val dt = if (runtime.lastRender == 0L) 16L else (now - runtime.lastRender).coerceIn(1, 100)
                    runtime.lastRender = now
                    val blend = (1.0 - exp(-dt / 55.0)).toFloat()
                    if (now - runtime.lastDetection > 700) detectedBoxes = emptyList()
                    people.filter { isTrackExpired(it.hits, now - it.lastSeen) }
                        .toList().forEach { it.anchor?.detach(); people.remove(it) }
                    // Anchors stay in world space, including while their cards are off-screen.
                    cards = people.filter { it.hits >= 2 && it.anchor?.trackingState == TrackingState.TRACKING }
                        .mapNotNull { person ->
                            val target = person.targetLocal ?: return@mapNotNull null
                            val rendered = person.renderedLocal ?: target.copyOf().also { person.renderedLocal = it }
                            for (axis in 0..2) rendered[axis] += (target[axis] - rendered[axis]) * blend
                            val facePoint = person.anchor!!.pose.transformPoint(rendered)
                            val faceOnScreen = project(facePoint, vp, viewportWidth, viewportHeight)
                                ?.let { it[0] in 0f..viewportWidth && it[1] in 0f..viewportHeight } ?: false
                            // Off-screen cards persist briefly; in-view false detections disappear promptly.
                            if (faceOnScreen && now - person.lastSeen > 1500) return@mapNotNull null
                            val p = FloatArray(3) { facePoint[it] + person.cardOffset[it] }
                            val origin = project(p, vp, viewportWidth, viewportHeight)
                            val corner = project(FloatArray(3) { i -> p[i] + right[i] * 0.24f - up[i] * 0.12f },
                                vp, viewportWidth, viewportHeight)
                            if (origin == null || corner == null) null
                            else ProjectedProfile(person.number, origin[0], origin[1],
                                abs(corner[0] - origin[0]), abs(corner[1] - origin[1]), person.estimatedDepth,
                                person.depthMeters)
                        }
                    status = "追跡中：${people.count { it.hits >= 2 }}人／確認中：${people.count { it.hits < 2 }}人"
                    if (!runtime.busy && now - runtime.lastAnalysis >= 200 && failure == null) {
                        try {
                            val image = frame.acquireCameraImage()
                            runtime.busy = true
                            runtime.lastAnalysis = now
                            val pose = camera.pose
                            val screenPose = camera.displayOrientedPose
                            val intrinsics = camera.imageIntrinsics
                            val focal = intrinsics.focalLength
                            val principal = intrinsics.principalPoint
                            val sensorOrientation = runtime.sensorOrientation ?: (
                                context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                                    .getCameraCharacteristics(session.cameraConfig.cameraId)
                                    .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
                                    ?: 90).also { runtime.sensorOrientation = it }
                            val displayDegrees = when (hostView.display?.rotation) {
                                android.view.Surface.ROTATION_90 -> 90
                                android.view.Surface.ROTATION_180 -> 180
                                android.view.Surface.ROTATION_270 -> 270
                                else -> 0
                            }
                            val rotation = (sensorOrientation - displayDegrees + 360) % 360
                            val imageWidth = image.width
                            val imageHeight = image.height
                            val epoch = resetVersion
                            // Capture depth and its image-to-texture mapping with the same frame.
                            // Keep only the depth image; sample nine pixels per face, never copy the full map.
                            val depth = if (now - runtime.lastDepth >= 500) {
                                runtime.lastDepth = now
                                captureDepth(frame)
                            } else null
                            val viewMapping = FloatArray(6)
                            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,
                                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.VIEW_NORMALIZED, viewMapping)
                            val task = try {
                                detector.process(InputImage.fromMediaImage(image, rotation))
                            } catch (exception: Exception) {
                                image.close()
                                depth?.close()
                                throw exception
                            }
                            task
                                .addOnSuccessListener(executor) { faces ->
                                    if (runtime.active && runtime.resetVersion == epoch) {
                                        runtime.lastDetection = android.os.SystemClock.elapsedRealtime()
                                        detectionCount = faces.size
                                        detectedBoxes = faces.map { face ->
                                            val b = face.boundingBox
                                            val corners = listOf(
                                                toRawImage(b.left.toFloat(), b.top.toFloat(), imageWidth, imageHeight, rotation),
                                                toRawImage(b.right.toFloat(), b.top.toFloat(), imageWidth, imageHeight, rotation),
                                                toRawImage(b.left.toFloat(), b.bottom.toFloat(), imageWidth, imageHeight, rotation),
                                                toRawImage(b.right.toFloat(), b.bottom.toFloat(), imageWidth, imageHeight, rotation)
                                            ).map { raw ->
                                                floatArrayOf(
                                                    (viewMapping[0] + (viewMapping[2] - viewMapping[0]) * raw[0] +
                                                        (viewMapping[4] - viewMapping[0]) * raw[1]) * viewportWidth,
                                                    (viewMapping[1] + (viewMapping[3] - viewMapping[1]) * raw[0] +
                                                        (viewMapping[5] - viewMapping[1]) * raw[1]) * viewportHeight)
                                            }
                                            DetectedFaceBox(face.trackingId, corners.minOf { it[0] }, corners.minOf { it[1] },
                                                corners.maxOf { it[0] }, corners.maxOf { it[1] })
                                        }
                                        val claimed = mutableSetOf<Int>()
                                        faces.filter { it.boundingBox.width() >= 35 }.forEach { face ->
                                            val box = face.boundingBox
                                            val uprightWidth = if (rotation % 180 == 0) imageWidth else imageHeight
                                            val uprightHeight = if (rotation % 180 == 0) imageHeight else imageWidth
                                            if (!isFaceFullyVisible(box.left.toFloat(), box.top.toFloat(),
                                                    box.right.toFloat(), box.bottom.toFloat(), uprightWidth, uprightHeight)) {
                                                // Keep the existing anchor and card offset as the face leaves the view.
                                                return@forEach
                                            }
                                            val raw = toRawImage(box.exactCenterX(), box.exactCenterY(),
                                                imageWidth, imageHeight, rotation)
                                            val x = raw[0]
                                            val y = raw[1]
                                            // Fallback assumes an approximately 16 cm wide face.
                                            val faceFocal = if (rotation % 180 == 0) focal[0] else focal[1]
                                            val approximate = faceFocal * 0.16f / box.width()
                                            // Reject depth readings likely to be background behind the face.
                                            val measured = depth?.metersAt(x, y)
                                                ?.takeIf { it in approximate * 0.55f..approximate * 1.65f }
                                            val prior = people.firstOrNull {
                                                canReuseTrackingId(it.trackingId, face.trackingId, now - it.lastSeen)
                                            }
                                            val z = measured ?: prior?.let {
                                                if (!it.estimatedDepth) it.depthMeters
                                                else it.depthMeters * 0.7f + approximate * 0.3f
                                            } ?: approximate
                                            if (z in 0.25f..8f) {
                                                val local = floatArrayOf(
                                                    (x - principal[0]) * z / focal[0],
                                                    -(y - principal[1]) * z / focal[1], -z)
                                                val faceWorld = pose.transformPoint(local)
                                                // Align the panel's top-left with the detected face's top-right.
                                                // Use the same depth as the face, plus only a 1 cm horizontal gap.
                                                val edgeRaw = toRawImage(box.right.toFloat(), box.top.toFloat(),
                                                    imageWidth, imageHeight, rotation)
                                                val edgeWorld = pose.transformPoint(floatArrayOf(
                                                    (edgeRaw[0] - principal[0]) * z / focal[0],
                                                    -(edgeRaw[1] - principal[1]) * z / focal[1], -z))
                                                val gap = screenPose.rotateVector(floatArrayOf(0.01f, 0f, 0f))
                                                val side = FloatArray(3) { edgeWorld[it] - faceWorld[it] + gap[it] }
                                                val available = people.filter { it.number !in claimed }
                                                // Identity within a continuous track takes priority over noisy depth.
                                                val existing = available.firstOrNull {
                                                    canReuseTrackingId(it.trackingId, face.trackingId, now - it.lastSeen)
                                                } ?: available.minByOrNull { distance(it.faceWorld, faceWorld) }
                                                    ?.takeIf { now - it.lastSeen < 1200 && distance(it.faceWorld, faceWorld) < 0.7f }
                                                if (existing != null || people.size < 12) {
                                                    if (existing != null) {
                                                        existing.hits = consecutiveHits(existing.hits, now - existing.lastSeen)
                                                        existing.faceWorld = FloatArray(3) {
                                                            existing.faceWorld[it] * 0.10f + faceWorld[it] * 0.90f
                                                        }
                                                        if (existing.hits >= 2 && existing.anchor == null) {
                                                            existing.anchor = runCatching {
                                                                session.createAnchor(Pose.makeTranslation(existing.faceWorld))
                                                            }.getOrNull()
                                                        }
                                                        // Reuse one anchor; move a lightweight local target instead.
                                                        existing.anchor?.let { anchor ->
                                                            existing.targetLocal = anchor.pose.inverse().transformPoint(existing.faceWorld)
                                                        }
                                                        // Preserve the world-space side selected at acquisition.
                                                        // Recomputing it from the turning phone pulls cards across the scene.
                                                        existing.trackingId = face.trackingId
                                                        existing.lastSeen = now
                                                        existing.estimatedDepth = measured == null && (prior?.estimatedDepth ?: true)
                                                        existing.depthMeters = z
                                                        claimed += existing.number
                                                    } else {
                                                        val number = runtime.nextNumber++
                                                        // A single detection never creates a visible card or anchor.
                                                        people += SpatialPerson(number, face.trackingId, null, now,
                                                            measured == null, z, faceWorld, side)
                                                        claimed += number
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener(executor) {
                                    if (runtime.active) status = "顔検出を再試行中：${it.javaClass.simpleName}"
                                }
                                .addOnCompleteListener(executor) {
                                    image.close(); depth?.close(); runtime.busy = false
                                }
                        } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
                            runtime.busy = false
                        } catch (_: Exception) {
                            runtime.busy = false
                            status = "カメラ画像の取得を再試行しています"
                        }
                    }
                }
            }
        )
        val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        Canvas(Modifier.fillMaxSize()) {
            if (showFaceBoxes) {
                val canvas = drawContext.canvas.nativeCanvas
                paint.clearShadowLayer()
                paint.color = android.graphics.Color.rgb(255, 210, 70)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.dp.toPx()
                detectedBoxes.forEach { box ->
                    canvas.drawRect(box.left, box.top, box.right, box.bottom, paint)
                    paint.style = Paint.Style.FILL
                    paint.textSize = 13.dp.toPx()
                    canvas.drawText("face ${box.trackingId ?: "?"}", box.left, box.top - 5.dp.toPx(), paint)
                    paint.style = Paint.Style.STROKE
                }
            }
            cards.forEach { card ->
                if (card.width > 10 && card.height > 10) {
                    val canvas = drawContext.canvas.nativeCanvas
                    val saved = canvas.save()
                    canvas.translate(card.x, card.y)
                    // Scale all text and panel geometry together with perspective.
                    canvas.scale(card.width / 300f, card.height / 160f)
                    paint.style = Paint.Style.FILL
                    // A subtle backdrop keeps text legible against bright scenery, without a border.
                    paint.clearShadowLayer()
                    paint.color = android.graphics.Color.argb(85, 0, 0, 0)
                    canvas.drawRoundRect(-8f, 0f, 300f, 140f, 8f, 8f, paint)
                    paint.setShadowLayer(2.5f, 0f, 1f, android.graphics.Color.argb(220, 0, 0, 0))
                    paint.color = android.graphics.Color.rgb(108, 255, 208)
                    paint.textSize = 20f
                    canvas.drawText("DEMO PERSON ${card.number}", 0f, 25f, paint)
                    paint.color = android.graphics.Color.WHITE
                    paint.textSize = 16f
                    canvas.drawText("プロフィール接続待ち", 0f, 60f, paint)
                    val distanceLabel = String.format(java.util.Locale.US, "%.2f m", card.depthMeters)
                    canvas.drawText("推定 $distanceLabel / ${if (card.estimated) "顔幅" else "Depth"}", 0f, 91f, paint)
                    canvas.drawText("空間位置に固定・仮データ", 0f, 125f, paint)
                    paint.clearShadowLayer()
                    canvas.restoreToCount(saved)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomStart).navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 78.dp)
            .background(Color.Black.copy(alpha = 0.75f)).padding(10.dp)) {
            Text(failure ?: status, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showFaceBoxes, onCheckedChange = { showFaceBoxes = it })
                Text("検出枠を表示（検出：${if (detectedBoxes.isEmpty()) 0 else detectionCount}個）", color = Color.White)
            }
            Text("2回検出で表示・画面端では位置を保持", color = Color(0xFF6CFFD0))
            Button(onClick = { resetVersion++ }) { Text("空間の仮プロフィールをリセット") }
        }
    }
}

private class SpatialRuntime {
    var captureRequest: ((Result<android.graphics.Bitmap>) -> Unit)? = null
    var active = true
    var busy = false
    var lastAnalysis = 0L
    var nextNumber = 1
    var resetVersion = 0
    var sensorOrientation: Int? = null
    var lastRender = 0L
    var lastDetection = 0L
    var lastDepth = 0L
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val viewProjectionMatrix = FloatArray(16)
}

private fun distance(a: FloatArray, b: FloatArray): Float =
    sqrt(a.indices.sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() }).toFloat()

private class DepthSnapshot(private val image: android.media.Image, val uv: FloatArray) : AutoCloseable {
    private val width = image.width
    private val height = image.height
    private val plane = image.planes[0]
    private val buffer = plane.buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    override fun close() { image.close() }
    fun metersAt(x: Float, y: Float): Float? {
        val u = uv[0] + (uv[2] - uv[0]) * x + (uv[4] - uv[0]) * y
        val v = uv[1] + (uv[3] - uv[1]) * x + (uv[5] - uv[1]) * y
        if (u !in 0f..0.9999f || v !in 0f..0.9999f) return null
        val cx = (u * width).toInt()
        val cy = (v * height).toInt()
        val samples = mutableListOf<Int>()
        for (dy in -1..1) for (dx in -1..1) {
            val px = cx + dx; val py = cy + dy
            if (px in 0 until width && py in 0 until height) {
                val mm = buffer.getShort(py * plane.rowStride + px * plane.pixelStride).toInt() and 0xffff
                if (mm in 250..8000) samples += mm
            }
        }
        return samples.sorted().let { if (it.size < 3) null else it[it.size / 2] / 1000f }
    }
}

private fun captureDepth(frame: Frame): DepthSnapshot? = try {
    val image = frame.acquireDepthImage16Bits()
    try {
        val uv = FloatArray(6)
        frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.TEXTURE_NORMALIZED, uv)
        DepthSnapshot(image, uv)
    } catch (exception: Exception) { image.close(); throw exception }
} catch (_: Exception) { null }
