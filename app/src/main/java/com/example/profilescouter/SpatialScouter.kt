/*
 * ARCoreのカメラ映像から顔を検出・追跡し、人物の横の空間座標にプロフィールを表示する中心画面。
 */
package com.example.profilescouter

import android.graphics.Paint
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.profilescouter.ui.designsystem.ScouterSpace
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
    var depthMeters: Float,
    var faceWorld: FloatArray,
    var cardOffset: FloatArray,
    var targetLocal: FloatArray? = null,
    var renderedLocal: FloatArray? = null,
    var hits: Int = 1,
    var recognitionKey: String = java.util.UUID.randomUUID().toString()
)

private data class DetectedFaceBox(val trackingId: Int?, val left: Float, val top: Float,
    val right: Float, val bottom: Float)

private data class WorldFaceBox(val trackingId: Int?, val corners: List<FloatArray>)

private data class ProjectedProfile(
    val number: Int, val x: Float, val y: Float,
    val width: Float, val height: Float, val depthMeters: Float,
    val recognition: FaceRecognitionResult?
)

/** ARCore owns the camera; face JPEGs are made from the same frame as detection. */
@Composable
internal fun SpatialScouter(modifier: Modifier = Modifier, snapshot: CameraSnapshot, onOpenConnection: () -> Unit,
    onOpenProfile: () -> Unit) {
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
    val recognitionScope = rememberCoroutineScope()
    val pipeline = remember { FaceRecognitionPipeline(recognitionScope) }
    var showRecognition by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var analysisEnabled by remember { mutableStateOf(true) }
    val currentAnalysis = rememberUpdatedState(analysisEnabled)
    var performance by remember { mutableStateOf("計測中") }
    val preferences = remember(context) { context.getSharedPreferences("server_connection", 0) }
    val captureExecutor = remember { java.util.concurrent.Executors.newSingleThreadExecutor() }
    DisposableEffect(snapshot) {
        snapshot.capture = { callback ->
            snapshot.stage = "ARフレーム待ち"
            runtime.captureRequest?.invoke(Result.failure(IllegalStateException("撮影要求が更新されました")))
            runtime.captureRequest = callback
        }
        snapshot.cancelCapture = { runtime.captureRequest = null }
        onDispose {
            snapshot.capture = null
            snapshot.cancelCapture = null
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
            sessionConfiguration = { _, config ->
                // Rendering must not wait for the next camera exposure.
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.lightEstimationMode = Config.LightEstimationMode.DISABLED
                config.planeFindingMode = Config.PlaneFindingMode.DISABLED
                config.depthMode = Config.DepthMode.DISABLED
            },
            onViewCreated = {
                // Keep the camera stream's color/tone conversion enabled.
                view.isPostProcessingEnabled = true
                view.antiAliasing = com.google.android.filament.View.AntiAliasing.NONE
            },
            onSessionFailed = {
                failure = "空間ARを開始できませんでした。ARCore対応端末とGoogle Play開発者サービス（AR）を確認してください。\n${it.javaClass.simpleName}"
            },
            onSessionPaused = { cards = emptyList(); detectedBoxes = emptyList(); runtime.worldBoxes = emptyList(); pipeline.reset() },
            onSessionUpdated = { session, frame ->
                val tick = android.os.SystemClock.elapsedRealtime()
                if (runtime.metricsStart == 0L) runtime.metricsStart = tick
                runtime.renderFrames++
                if (frame.timestamp != runtime.lastCameraTimestamp) {
                    runtime.cameraFrames++
                    runtime.lastCameraTimestamp = frame.timestamp
                }
                if (tick - runtime.metricsStart >= 1000) {
                    val seconds = (tick - runtime.metricsStart) / 1000f
                    performance = "AR %.0f fps / カメラ更新 %.0f fps".format(
                        runtime.renderFrames / seconds, runtime.cameraFrames / seconds)
                    runtime.metricsStart = tick; runtime.renderFrames = 0; runtime.cameraFrames = 0
                }
                runtime.captureRequest?.let { callback ->
                    try {
                        val rotation = cameraImageRotation(context, session.cameraConfig.cameraId, hostView.display?.rotation)
                        val image = frame.acquireCameraImage()
                        snapshot.stage = "カメラ画像取得済み・変換待ち"
                        runtime.captureRequest = null
                        try {
                            captureExecutor.execute {
                                snapshot.stage = "YUV画像を変換中"
                                val result = runCatching { image.use { cameraImageToBitmap(it, rotation) } }
                                snapshot.stage = if (result.isSuccess) "画像取得完了" else "画像変換失敗"
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
                    runtime.worldBoxes = emptyList()
                    pipeline.reset()
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
                    // Reproject diagnostic boxes every frame, just like the AR cards.
                    // Face inference remains throttled; camera movement needs no new inference.
                    detectedBoxes = if (now - runtime.lastDetection > 700) emptyList() else
                        runtime.worldBoxes.mapNotNull { box ->
                            val points = box.corners.map { project(it, vp, viewportWidth, viewportHeight) }
                            if (points.any { it == null }) null else {
                                val valid = points.filterNotNull()
                                DetectedFaceBox(box.trackingId, valid.minOf { it[0] }, valid.minOf { it[1] },
                                    valid.maxOf { it[0] }, valid.maxOf { it[1] })
                            }
                        }
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
                                abs(corner[0] - origin[0]), abs(corner[1] - origin[1]),
                                person.depthMeters, pipeline.results[person.recognitionKey])
                        }
                    status = "追跡中：${people.count { it.hits >= 2 }}人／確認中：${people.count { it.hits < 2 }}人"
                    if (analysisEnabled && !runtime.busy && now - runtime.lastAnalysis >= 200 && failure == null) {
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
                            val viewMapping = FloatArray(6)
                            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS,
                                floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), Coordinates2d.VIEW_NORMALIZED, viewMapping)
                            var retainedForJpeg = false
                            val task = try {
                                detector.process(InputImage.fromMediaImage(image, rotation))
                            } catch (exception: Exception) {
                                image.close()
                                throw exception
                            }
                            task
                                .addOnSuccessListener(executor) { faces ->
                                    if (runtime.active && currentAnalysis.value && runtime.resetVersion == epoch) {
                                        runtime.lastDetection = android.os.SystemClock.elapsedRealtime()
                                        val indices = uniqueFaceIndices(faces.map {
                                            val b = it.boundingBox
                                            FaceRectangle(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                                        }, faces.map { it.trackingId })
                                        val uniqueFaces = indices.map { faces[it] }
                                        detectionCount = uniqueFaces.size
                                        detectedBoxes = uniqueFaces.map { face ->
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
                                        val faceCandidates = mutableListOf<FaceCandidate>()
                                        val nextWorldBoxes = mutableListOf<WorldFaceBox>()
                                        uniqueFaces.filter { it.boundingBox.width() >= 35 }.forEach { face ->
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
                                            // Distance assumes an approximately 16 cm wide face.
                                            val faceFocal = if (rotation % 180 == 0) focal[0] else focal[1]
                                            val approximate = faceFocal * 0.16f / box.width()
                                            val prior = people.firstOrNull {
                                                canReuseTrackingId(it.trackingId, face.trackingId, now - it.lastSeen)
                                            }
                                            val z = prior?.let { it.depthMeters * 0.7f + approximate * 0.3f } ?: approximate
                                            if (z in 0.25f..8f) {
                                                val worldCorners = listOf(
                                                    box.left.toFloat() to box.top.toFloat(),
                                                    box.right.toFloat() to box.top.toFloat(),
                                                    box.left.toFloat() to box.bottom.toFloat(),
                                                    box.right.toFloat() to box.bottom.toFloat()
                                                ).map { (bx, by) ->
                                                    val rawCorner = toRawImage(bx, by, imageWidth, imageHeight, rotation)
                                                    pose.transformPoint(floatArrayOf(
                                                        (rawCorner[0] - principal[0]) * z / focal[0],
                                                        -(rawCorner[1] - principal[1]) * z / focal[1], -z))
                                                }
                                                nextWorldBoxes += WorldFaceBox(face.trackingId, worldCorners)
                                                val local = floatArrayOf(
                                                    (x - principal[0]) * z / focal[0],
                                                    -(y - principal[1]) * z / focal[1], -z)
                                                val faceWorld = pose.transformPoint(local)
                                                // Align the panel's top-left with the detected face's top-right.
                                                // Use the same distance as the face, plus a 4 cm horizontal gap.
                                                val edgeRaw = toRawImage(box.right.toFloat(), box.top.toFloat(),
                                                    imageWidth, imageHeight, rotation)
                                                val edgeWorld = pose.transformPoint(floatArrayOf(
                                                    (edgeRaw[0] - principal[0]) * z / focal[0],
                                                    -(edgeRaw[1] - principal[1]) * z / focal[1], -z))
                                                val gap = screenPose.rotateVector(floatArrayOf(0.04f, 0f, 0f))
                                                val side = FloatArray(3) { edgeWorld[it] - faceWorld[it] + gap[it] }
                                                val available = people.filter { it.number !in claimed }
                                                val screenMatches = available.filter { now - it.lastSeen < 1000 }
                                                    .mapNotNull { candidate ->
                                                        val world = candidate.anchor?.let { anchor ->
                                                            candidate.targetLocal?.let { anchor.pose.transformPoint(it) }
                                                        } ?: candidate.faceWorld
                                                        val localPosition = pose.inverse().transformPoint(world)
                                                        if (localPosition[2] >= -0.1f) return@mapNotNull null
                                                        val rawX = focal[0] * localPosition[0] / -localPosition[2] + principal[0]
                                                        val rawY = principal[1] - focal[1] * localPosition[1] / -localPosition[2]
                                                        val upright = toUprightImage(rawX, rawY, imageWidth, imageHeight, rotation)
                                                        val cost = faceAssociationCost(upright[0], upright[1],
                                                            FaceRectangle(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()))
                                                        candidate to cost
                                                    }.filter { it.second <= 0.64f }.sortedBy { it.second }
                                                val screenMatch = screenMatches.firstOrNull()?.takeIf {
                                                    screenMatches.size == 1 || screenMatches[1].second - it.second > 0.15f
                                                }?.first
                                                // Identity within a continuous track takes priority over noisy depth.
                                                val existing = available.firstOrNull {
                                                    canReuseTrackingId(it.trackingId, face.trackingId, now - it.lastSeen)
                                                } ?: screenMatch ?: available.minByOrNull { distance(it.faceWorld, faceWorld) }
                                                    ?.takeIf { now - it.lastSeen < 1200 && distance(it.faceWorld, faceWorld) < 0.7f }
                                                if (existing != null || people.size < 12) {
                                                    if (existing != null) {
                                                        // A short, unambiguous continuous screen track keeps its app ID.
                                                        if (existing.trackingId != face.trackingId && existing !== screenMatch)
                                                            existing.recognitionKey = java.util.UUID.randomUUID().toString()
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
                                                        // Refresh actual face-edge geometry while visible, then retain it off-screen.
                                                        existing.cardOffset = FloatArray(3) {
                                                            existing.cardOffset[it] * 0.7f + side[it] * 0.3f
                                                        }
                                                        existing.lastSeen = now
                                                        existing.depthMeters = z
                                                        claimed += existing.number
                                                    } else {
                                                        val number = runtime.nextNumber++
                                                        // A single detection never creates a visible card or anchor.
                                                        people += SpatialPerson(number, face.trackingId, null, now,
                                                            z, faceWorld, side)
                                                        claimed += number
                                                    }
                                                    val person = existing ?: people.last()
                                                    if (person.hits >= 2 && face.trackingId != null) {
                                                        faceCandidates += FaceCandidate(person.recognitionKey, person.number, face.trackingId,
                                                            paddedFaceCrop(box.left, box.top, box.right, box.bottom,
                                                                uprightWidth, uprightHeight))
                                                    }
                                                }
                                            }
                                        }
                                        runtime.worldBoxes = nextWorldBoxes
                                        pipeline.choose(faceCandidates, android.os.SystemClock.elapsedRealtime())?.let { selected ->
                                            retainedForJpeg = true
                                            pipeline.submit(image, rotation, selected, android.os.SystemClock.elapsedRealtime(),
                                                preferences.getString("base_url", "").orEmpty(),
                                                preferences.getString("recognition_path", "/recognize").orEmpty()) { key ->
                                                runtime.active && runtime.resetVersion == epoch &&
                                                    people.any { it.recognitionKey == key &&
                                                        android.os.SystemClock.elapsedRealtime() - it.lastSeen < 1200 }
                                            }
                                        }
                                    }
                                }
                                .addOnFailureListener(executor) {
                                    if (runtime.active) status = "顔検出を再試行中：${it.javaClass.simpleName}"
                                }
                                .addOnCompleteListener(executor) {
                                    if (!retainedForJpeg) image.close()
                                    runtime.busy = false
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
        val palette = MaterialTheme.colorScheme
        val type = MaterialTheme.typography
        val profileStyle = ProfileCardStyle(palette.surface.copy(alpha = 0.88f).toArgb(),
            palette.onSurface.toArgb(), palette.onSurfaceVariant.toArgb(), palette.primary.toArgb(),
            type.headlineSmall.fontSize.value, type.bodyMedium.fontSize.value, type.labelSmall.fontSize.value)
        val profileRenderer = remember(profileStyle) { ProfileCardRenderer(profileStyle) }
        Canvas(Modifier.fillMaxSize()) {
            if (showFaceBoxes) {
                val canvas = drawContext.canvas.nativeCanvas
                paint.clearShadowLayer()
                paint.color = palette.secondary.toArgb()
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
                    canvas.scale(card.width / 320f, card.height / 180f)
                    profileRenderer.draw(canvas, profileCardContent(card.number, card.recognition, pipeline.mode),
                        if (showFaceBoxes) String.format(java.util.Locale.US, "%.2f m · 顔幅推定", card.depthMeters) else null)
                    canvas.restoreToCount(saved)
                }
            }
        }
        Surface(modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(ScouterSpace.md).fillMaxWidth(),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Profile Scouter", style = MaterialTheme.typography.titleMedium)
                    Text("プロフィールを、そばに。", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { showSettings = true }) { Text("設定") }
            }
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(ScouterSpace.md).fillMaxWidth(),
            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(ScouterSpace.md), verticalArrangement = Arrangement.spacedBy(ScouterSpace.sm)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (pipeline.mode == RecognitionMode.OFF) "スタンバイ" else when (pipeline.mode) {
                        RecognitionMode.PREVIEW -> "切り抜き確認"
                        RecognitionMode.MOCK -> "デモモード"
                        else -> "PC認識中"
                    }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${if (detectedBoxes.isEmpty()) 0 else detectionCount}人を検出", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(failure ?: if (pipeline.mode == RecognitionMode.OFF) status else pipeline.status.take(80),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2)
                FilledTonalButton(onClick = {
                    if (pipeline.mode == RecognitionMode.OFF) showRecognition = true
                    else pipeline.changeMode(RecognitionMode.OFF)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pipeline.mode == RecognitionMode.OFF) "認識モードを選ぶ" else "認識を停止")
                }
            }
        }
    }
    if (showRecognition) RecognitionPanel(pipeline, onDismiss = { showRecognition = false })
    if (showSettings) ScouterSettings(showFaceBoxes, if (detectedBoxes.isEmpty()) 0 else detectionCount,
        "$status\n${pipeline.status}\n$performance\n※fpsは表示遅延そのものの計測値ではありません。",
        analysisEnabled = analysisEnabled,
        onAnalysisChanged = {
            analysisEnabled = it
            if (!it) { pipeline.changeMode(RecognitionMode.OFF); detectedBoxes = emptyList() }
        },
        onBoxesChanged = { showFaceBoxes = it },
        onConnection = { showSettings = false; onOpenConnection() },
        onProfile = { showSettings = false; onOpenProfile() },
        onRecognition = { showSettings = false; showRecognition = true },
        onReset = { resetVersion++; showSettings = false }, onDismiss = { showSettings = false })
}

private class SpatialRuntime {
    var metricsStart = 0L
    var renderFrames = 0
    var cameraFrames = 0
    var lastCameraTimestamp = 0L
    var worldBoxes: List<WorldFaceBox> = emptyList()
    var captureRequest: ((Result<android.graphics.Bitmap>) -> Unit)? = null
    var active = true
    var busy = false
    var lastAnalysis = 0L
    var nextNumber = 1
    var resetVersion = 0
    var sensorOrientation: Int? = null
    var lastRender = 0L
    var lastDetection = 0L
    val viewMatrix = FloatArray(16)
    val projectionMatrix = FloatArray(16)
    val viewProjectionMatrix = FloatArray(16)
}

private fun distance(a: FloatArray, b: FloatArray): Float =
    sqrt(a.indices.sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toDouble() }).toFloat()
