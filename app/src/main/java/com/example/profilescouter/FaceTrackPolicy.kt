package com.example.profilescouter

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
