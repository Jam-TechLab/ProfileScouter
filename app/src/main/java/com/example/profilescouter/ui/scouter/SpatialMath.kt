/*
 * カメラ画像の回転に伴う座標変換と、現実空間の点を画面へ投影する計算を担当する。
 */
package com.example.profilescouter.ui.scouter

internal fun toUprightImage(x: Float, y: Float, width: Int, height: Int, rotation: Int): FloatArray = when (rotation) {
    90 -> floatArrayOf(height - y, x)
    180 -> floatArrayOf(width - x, height - y)
    270 -> floatArrayOf(y, width - x)
    else -> floatArrayOf(x, y)
}

/** ML Kit boxes use the upright image; camera intrinsics use the unrotated image. */
internal fun toRawImage(x: Float, y: Float, width: Int, height: Int, rotation: Int): FloatArray =
    when (rotation) {
        90 -> floatArrayOf(y, height - x)
        180 -> floatArrayOf(width - x, height - y)
        270 -> floatArrayOf(width - y, x)
        else -> floatArrayOf(x, y)
    }

/** Deliberately returns off-screen coordinates: never clamp cards to viewport edges. */
internal fun project(point: FloatArray, vp: FloatArray, width: Float, height: Float): FloatArray? {
    val input = floatArrayOf(point[0], point[1], point[2], 1f)
    val clip = FloatArray(4) { row -> (0..3).sumOf { col ->
        (vp[col * 4 + row] * input[col]).toDouble()
    }.toFloat() }
    if (clip.any { !it.isFinite() } || clip[3] <= 0.0001f) return null
    return floatArrayOf((clip[0] / clip[3] + 1f) * width / 2f,
        (1f - clip[1] / clip[3]) * height / 2f)
}
