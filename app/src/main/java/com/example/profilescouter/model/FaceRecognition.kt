/*
 * 顔領域・人物プロフィール・認識結果の共通データを定義する。
 */
package com.example.profilescouter.model

import kotlin.math.ceil
import kotlin.math.floor

internal data class FaceCrop(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun paddedFaceCrop(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int): FaceCrop {
    require(right > left && bottom > top && width > 0 && height > 0)
    val padX = (right - left) * 0.25f
    val padY = (bottom - top) * 0.25f
    return FaceCrop(floor(left - padX).toInt().coerceIn(0, width - 1),
        floor(top - padY).toInt().coerceIn(0, height - 1),
        ceil(right + padX).toInt().coerceIn(1, width), ceil(bottom + padY).toInt().coerceIn(1, height))
}

internal data class FaceCandidate(val key: String, val number: Int, val crop: FaceCrop)
internal data class FacePacket(val requestId: String, val trackId: String)
internal data class PersonProfile(val userId: String, val displayName: String, val affiliation: String, val bio: String)
internal data class FaceRecognitionResult(val matched: Boolean, val similarity: Double?,
    val profile: PersonProfile?, val simpleProfile: Boolean = false)
