package com.example.profilescouter

import android.content.Context
import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CameraSnapshot {
    var capture: (((Result<Bitmap>) -> Unit) -> Unit)? = null
    suspend fun bitmap(): Bitmap = withTimeout(5000) {
        suspendCancellableCoroutine { continuation ->
            val handler = capture
            if (handler == null) continuation.resumeWithException(IllegalStateException("カメラの準備ができていません"))
            else handler { result ->
                if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                else result.getOrNull()?.recycle()
            }
        }
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

internal fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
    val ratio = minOf(1f, 960f / maxOf(bitmap.width, bitmap.height))
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
    val nv21 = ByteArray(width * height * 3 / 2)
    val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
    for (row in 0 until height) for (col in 0 until width)
        nv21[row * width + col] = y.buffer.get(y.buffer.position() + row * y.rowStride + col * y.pixelStride)
    var index = width * height
    for (row in 0 until height / 2) for (col in 0 until width / 2) {
        nv21[index++] = v.buffer.get(v.buffer.position() + row * v.rowStride + col * v.pixelStride)
        nv21[index++] = u.buffer.get(u.buffer.position() + row * u.rowStride + col * u.pixelStride)
    }
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
