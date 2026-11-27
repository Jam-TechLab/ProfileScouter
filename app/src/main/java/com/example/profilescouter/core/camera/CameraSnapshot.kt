/*
 * ARカメラからの静止画取得、画像の向きの計算、YUV画像の変換、JPEG化を担当する。
 */
package com.example.profilescouter.core.camera

import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CameraSnapshot {
    var capture: (((Result<Bitmap>) -> Unit) -> Unit)? = null
    var cancelCapture: (() -> Unit)? = null
    @Volatile var stage: String = "未開始"
    suspend fun bitmap(): Bitmap = try { withTimeout(15000) {
        suspendCancellableCoroutine { continuation ->
            val handler = capture
            if (handler == null) continuation.resumeWithException(IllegalStateException("カメラの準備ができていません"))
            else handler { result ->
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                else result.getOrNull()?.recycle()
            }
        }
    } } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        cancelCapture?.invoke()
        throw IllegalStateException("画像取得がタイムアウトしました（$stage）。AR映像が動いているか確認してください。")
    }
}

internal fun cameraImageRotation(context: Context, cameraId: String, displayRotation: Int?): Int {
    val sensor = context.getSystemService(CameraManager::class.java)
        .getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val screen = when (displayRotation) {
        android.view.Surface.ROTATION_90 -> 90
        android.view.Surface.ROTATION_180 -> 180
        android.view.Surface.ROTATION_270 -> 270
        else -> 0
    }
    return (sensor - screen + 360) % 360
}

internal fun bitmapToJpeg(bitmap: Bitmap, maxEdge: Int = 960): ByteArray {
    val ratio = minOf(1f, maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        else bitmap
    return try {
        ByteArrayOutputStream().use { stream ->
            check(scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)) { "JPEG変換に失敗しました" }
            stream.toByteArray()
        }
    } finally { if (scaled !== bitmap) scaled.recycle() }
}

/** Handles padded rows and interleaved chroma. Caller closes the image after conversion. */
internal fun cameraImageToBitmap(image: Image, rotation: Int): Bitmap {
    require(image.format == ImageFormat.YUV_420_888)
    val width = image.width; val height = image.height
    val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
    val nv21 = packYuv420(width, height, arrayOf(y.buffer, u.buffer, v.buffer),
        intArrayOf(y.rowStride, u.rowStride, v.rowStride), intArrayOf(y.pixelStride, u.pixelStride, v.pixelStride))
    val bytes = ByteArrayOutputStream().use {
        check(YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), 95, it))
        it.toByteArray()
    }
    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    if (rotation == 0) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, width, height,
        Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== bitmap) bitmap.recycle() }
}

/** Cache Image plane properties once. Avoid JNI getters for every pixel. */
internal fun packYuv420(width: Int, height: Int, buffers: Array<ByteBuffer>,
    rowStrides: IntArray, pixelStrides: IntArray): ByteArray {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    val output = ByteArray(width * height * 3 / 2)
    val planes = buffers.map { it.duplicate() }
    val starts = planes.map { it.position() }
    for (row in 0 until height) {
        val offset = starts[0] + row * rowStrides[0]
        if (pixelStrides[0] == 1) {
            planes[0].position(offset)
            planes[0].get(output, row * width, width)
        } else for (col in 0 until width)
            output[row * width + col] = planes[0].get(offset + col * pixelStrides[0])
    }
    var index = width * height
    for (row in 0 until height / 2) for (col in 0 until width / 2) {
        output[index++] = planes[2].get(starts[2] + row * rowStrides[2] + col * pixelStrides[2])
        output[index++] = planes[1].get(starts[1] + row * rowStrides[1] + col * pixelStrides[1])
    }
    return output
}
