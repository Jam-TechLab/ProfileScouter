/*
 * 顔画像の切り抜き範囲と送信タイミングを管理し、PCの認識結果を人物ごとに保持する。
 */
package com.example.profilescouter

import android.graphics.Bitmap
import android.media.Image
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
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

internal fun parseFaceResponse(raw: String, packet: FacePacket): FaceRecognitionResult {
    val json = JSONObject(raw)
    val fullFormat = listOf("schemaVersion", "requestId", "trackId", "matched", "userId", "similarity", "profile")
        .any { json.has(it) }
    if (!fullFormat) {
        require(listOf("displayName", "affiliation", "bio").all { json.opt(it) is String }) {
            "簡易形式ではdisplayName・affiliation・bioを文字列で返してください"
        }
        val name = json.getString("displayName")
        require(name.isNotBlank()) { "displayNameは空にできません" }
        // A real DB ID is optional for old servers; never use a name or track ID as identity.
        val personId = if (json.has("person_id")) {
            val value = json.get("person_id").toString()
            require(registeredPersonId(value) != null) { "person_idは正の整数で返してください" }
            value
        } else ""
        return FaceRecognitionResult(true, null, PersonProfile(personId, name,
            json.getString("affiliation"), json.getString("bio")), simpleProfile = true)
    }
    require(listOf("schemaVersion", "requestId", "trackId", "matched", "userId", "similarity", "profile")
        .all { json.has(it) }) { "正式形式の必須項目が不足しています" }
    require(json.getInt("schemaVersion") == 1) { "未対応のschemaVersionです" }
    require(json.getString("requestId") == packet.requestId) { "requestIdが一致しません" }
    require(json.getString("trackId") == packet.trackId) { "trackIdが一致しません" }
    val matched = json.getBoolean("matched")
    val similarity = json.getDouble("similarity")
    require(similarity.isFinite() && similarity in 0.0..1.0) { "similarityは0〜1で返してください" }
    val profile = if (matched) {
        val userId = json.getString("userId")
        val p = json.getJSONObject("profile")
        val name = p.getString("displayName")
        require(userId.isNotBlank() && name.isNotBlank()) { "一致時はuserIdとdisplayNameが必要です" }
        PersonProfile(userId, name, p.optString("affiliation", ""), p.optString("bio", ""))
    } else {
        require(json.isNull("userId") && json.isNull("profile")) { "不一致時はuserIdとprofileをnullにしてください" }
        null
    }
    return FaceRecognitionResult(matched, similarity, profile)
}

/** All state and admission checks run on the main thread; image work and HTTP run off it. */
internal class FaceRecognitionPipeline(private val scope: CoroutineScope,
    private val onRecognizedFace: (String, PersonProfile, ByteArray) -> Unit = { _, _, _ -> }) {
    var enabled by mutableStateOf(true); private set
    var status by mutableStateOf("PC認識：顔の検出待ち"); private set
    val results = mutableStateMapOf<String, FaceRecognitionResult>()
    private val attempts = mutableMapOf<String, Long>()
    private var epoch = 0
    private var busy = false
    private var lastStarted = -1000L

    fun setRunning(value: Boolean) {
        epoch++
        enabled = value
        attempts.clear()
        results.clear()
        status = if (value) "PC認識：顔の検出待ち" else "PC認識は停止中"
    }

    fun reset() { setRunning(enabled) }

    fun updateConversationSummary(personId: Long, summary: String) {
        results.entries.toList().forEach { (key, result) ->
            if (registeredPersonId(result.profile?.userId.orEmpty()) == personId) {
                results[key] = result.copy(profile = result.profile!!.copy(bio = summary))
            }
        }
    }

    fun choose(candidates: List<FaceCandidate>, now: Long): FaceCandidate? {
        if (!enabled || busy || now - lastStarted < 1000) return null
        // Limit in-memory history even when many temporary tracking IDs are created.
        attempts.entries.removeAll { now - it.value > 60_000 }
        results.keys.retainAll(attempts.keys)
        return candidates.filter { candidate ->
            val wait = if (results[candidate.key]?.matched == true) 10_000L else 3000L
            now - (attempts[candidate.key] ?: -10_000L) >= wait
        }.minByOrNull { attempts[it.key] ?: -10_000L }
    }

    /** Takes ownership of image immediately; closes it as soon as JPEG creation finishes. */
    fun submit(image: Image, rotation: Int, candidate: FaceCandidate, now: Long, baseUrl: String,
        recognitionPath: String,
        isCurrent: (String) -> Boolean) {
        busy = true
        lastStarted = now
        attempts[candidate.key] = now
        val generation = epoch
        val timestamp = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val jpeg = try {
                    withContext(Dispatchers.Default) {
                        val full = cameraImageToBitmap(image, rotation)
                        try {
                            val b = candidate.crop
                            val crop = Bitmap.createBitmap(full, b.left, b.top, b.right - b.left, b.bottom - b.top)
                            try { bitmapToJpeg(crop, maxEdge = 512) }
                            finally { if (crop !== full) crop.recycle() }
                        } finally { full.recycle() }
                    }
                } finally { image.close() }
                if (!isCurrentGeneration(generation) || !isCurrent(candidate.key)) return@launch
                val packet = FacePacket(requestId, candidate.key)
                status = "顔${candidate.number}をPCへ送信中…"
                val raw = withContext(Dispatchers.IO) {
                    RecognitionClient(baseUrl, recognitionPath = recognitionPath).recognize(jpeg, requestId, packet.trackId, timestamp, "face_crop")
                }
                val response = parseFaceResponse(raw, packet)
                if (isCurrentGeneration(generation) && isCurrent(candidate.key)) {
                    results[candidate.key] = response
                    response.profile?.takeIf { response.matched }?.let { onRecognizedFace(baseUrl, it, jpeg) }
                    status = if (response.matched) "顔${candidate.number}：${response.profile!!.displayName}"
                        else "顔${candidate.number}：未登録"
                } else if (isCurrentGeneration(generation)) status = "古い顔への応答を破棄しました"
            } catch (exception: CancellationException) { throw exception }
            catch (exception: Exception) {
                if (isCurrentGeneration(generation)) status = "認識エラー：${exception.message ?: exception.javaClass.simpleName}"
            } finally { busy = false }
        }
    }

    private fun isCurrentGeneration(generation: Int) = epoch == generation && enabled
}
