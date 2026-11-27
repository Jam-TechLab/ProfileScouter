/*
 * AR画面の人物ごとの認識状態と送信タイミングを管理する画面専用の状態ホルダー。
 */
package com.example.profilescouter.ui.scouter

import com.example.profilescouter.core.camera.bitmapToJpeg
import com.example.profilescouter.core.camera.cameraImageToBitmap
import com.example.profilescouter.data.recognition.RecognitionRepository
import com.example.profilescouter.data.recognition.parseFaceResponse
import com.example.profilescouter.model.FaceCandidate
import com.example.profilescouter.model.FacePacket
import com.example.profilescouter.model.FaceRecognitionResult
import com.example.profilescouter.model.PersonProfile
import com.example.profilescouter.model.registeredPersonId

import android.graphics.Bitmap
import android.media.Image
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.UUID

/** All state and admission checks run on the main thread; image work and HTTP run off it. */
internal class ScouterState(private val scope: CoroutineScope,
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
                    RecognitionRepository(baseUrl, recognitionPath = recognitionPath).recognize(jpeg, requestId, packet.trackId, timestamp, "face_crop")
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
