/*
 * 顔の追跡を継続する条件、見失った顔の期限、重複検出と対象の対応付けを判定する。
 */
package com.example.profilescouter.ui.scouter

import kotlin.math.max
import kotlin.math.min

internal fun consecutiveHits(previous: Int, elapsedMs: Long): Int =
    if (elapsedMs < if (previous >= 2) 2000 else 1200) previous + 1 else 1

internal fun isTrackExpired(hits: Int, elapsedMs: Long): Boolean =
    elapsedMs > if (hits >= 2) 5000 else 1500

internal fun canReuseTrackingId(oldId: Int?, newId: Int?, elapsedMs: Long): Boolean =
    newId != null && oldId == newId && elapsedMs < 2000

/** Truncated faces give unreliable width-based depth; preserve the last world pose instead. */
internal fun isFaceFullyVisible(left: Float, top: Float, right: Float, bottom: Float,
    imageWidth: Int, imageHeight: Int): Boolean {
    val marginX = imageWidth * 0.025f
    val marginY = imageHeight * 0.025f
    return left > marginX && top > marginY && right < imageWidth - marginX && bottom < imageHeight - marginY
}

internal data class FaceRectangle(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = max(0f, width) * max(0f, height)
}

/** Suppress only substantially overlapping detections, not adjacent faces. */
internal fun uniqueFaceIndices(boxes: List<FaceRectangle>, ids: List<Int?>): List<Int> {
    val kept = mutableListOf<Int>()
    boxes.indices.sortedByDescending { boxes[it].area }.forEach { index ->
        val b = boxes[index]
        if (b.area <= 0) return@forEach
        val duplicate = kept.any { previous ->
            val a = boxes[previous]
            val intersection = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val iou = intersection / (a.area + b.area - intersection)
            iou >= 0.55f || (ids[index] != null && ids[index] == ids[previous] && iou >= 0.15f)
        }
        if (!duplicate) kept += index
    }
    return kept.sorted()
}

/** A close screen-space match recovers short ID changes despite noisy distance estimates. */
internal fun faceAssociationCost(x: Float, y: Float, box: FaceRectangle): Float {
    if (box.width <= 0 || box.height <= 0) return Float.POSITIVE_INFINITY
    val dx = (x - (box.left + box.right) / 2f) / box.width
    val dy = (y - (box.top + box.bottom) / 2f) / box.height
    return dx * dx + dy * dy
}
