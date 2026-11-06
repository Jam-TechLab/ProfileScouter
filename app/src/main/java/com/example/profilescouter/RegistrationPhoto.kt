/*
 * 撮影した登録写真を読み込み、サイズと向きを補正し、正面写真の顔を検証する。
 */
package com.example.profilescouter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun readRegistrationPhoto(file: File): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "写真を読み込めませんでした" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }))
    val exif = ExifInterface(file.path)
    val transform = Matrix().apply {
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            2 -> postScale(-1f, 1f)
            3 -> postRotate(180f)
            4 -> postScale(1f, -1f)
            5 -> { postRotate(90f); postScale(-1f, 1f) }
            6 -> postRotate(90f)
            7 -> { postRotate(270f); postScale(-1f, 1f) }
            8 -> postRotate(270f)
        }
    }
    if (transform.isIdentity) return bitmap
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

internal suspend fun validateRegistrationFace(bitmap: Bitmap) {
    val detector = FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
    suspendCancellableCoroutine<Unit> { continuation ->
        detector.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { faces ->
            if (continuation.isActive) {
                val error = when {
                    faces.isEmpty() -> "顔が見つかりません。明るい場所で正面から撮影してください"
                    faces.size > 1 -> "1人だけが写るように撮り直してください"
                    faces[0].boundingBox.width() < 100 -> "顔をもう少し大きく写してください"
                    else -> null
                }
                if (error == null) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalArgumentException(error))
            }
        }.addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            .addOnCompleteListener { detector.close() }
    }
}
