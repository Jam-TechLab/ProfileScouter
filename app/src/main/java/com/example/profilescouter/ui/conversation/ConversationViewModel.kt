/*
 * 人物認識に連動する前景録音、音声の送信待ち・再送、会った人と共有履歴の状態を管理する。
 */
package com.example.profilescouter.ui.conversation

import com.example.profilescouter.core.network.normalizeServerUrl
import com.example.profilescouter.data.conversation.AudioNotSentException
import com.example.profilescouter.data.conversation.ConversationRepository
import com.example.profilescouter.model.ConversationEntry
import com.example.profilescouter.model.ConversationPolicy
import com.example.profilescouter.model.Encounter
import com.example.profilescouter.model.PendingConversation
import com.example.profilescouter.model.PersonProfile
import com.example.profilescouter.model.RecordingDecision
import com.example.profilescouter.model.registeredPersonId

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.util.UUID

internal class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ConversationRepository(application)
    private val preferences = application.getSharedPreferences("conversation_settings", 0)
    private val policy = ConversationPolicy()
    var enabled by mutableStateOf(false); private set
    var status by mutableStateOf("会話録音は停止中"); private set
    var recordingName by mutableStateOf<String?>(null); private set
    var uploadStatus by mutableStateOf(""); private set
    var cameraUploadStatus by mutableStateOf(""); private set
    private var cameraNoticeJob: Job? = null
    var recordingSeconds by mutableIntStateOf(0); private set
    var uploadingId by mutableStateOf<String?>(null); private set
    var checkingId by mutableStateOf<String?>(null); private set
    var avatarRevision by mutableIntStateOf(0); private set
    private val savingAvatars = mutableSetOf<String>()
    val avatarErrors = mutableStateMapOf<String, String>()
    var lastRecording by mutableStateOf(""); private set
    var graceSeconds by mutableIntStateOf(preferences.getInt("grace_v2", 30)); private set
    var maxMinutes by mutableIntStateOf(preferences.getInt("max", 5)); private set
    val encounters = mutableStateListOf<Encounter>().apply { addAll(repository.encounters()) }
    val pending = mutableStateListOf<PendingConversation>().apply { addAll(repository.pending()) }
    val latestSummaries = mutableStateMapOf<String, ConversationEntry>()
    var historyEntries by mutableStateOf<List<ConversationEntry>>(emptyList()); private set
    var historyLoading by mutableStateOf(false); private set
    var historyError by mutableStateOf<String?>(null); private set
    var historyNext by mutableStateOf<String?>(null); private set
    private var historyKey: String? = null
    private var historyJob: Job? = null
    private var uploader: Job? = null
    private var recorder: MediaRecorder? = null
    private var active: PendingConversation? = null
    private var activeElapsed = 0L
    private var serverForObservation = ""

    init {
        // A retry is user initiated after app restart: never upload old audio silently.
        pending.replaceAll { it.copy(error = it.error ?: "前回の処理結果を確認できません。PCで処理中の場合があります") }
        viewModelScope.launch {
            while (isActive) {
                delay(250)
                if (recorder != null) recordingSeconds = ((SystemClock.elapsedRealtime() - activeElapsed) / 1000).toInt()
                if (enabled) handle(policy.tick(SystemClock.elapsedRealtime()), emptyList())
            }
        }
    }

    fun configure(grace: Int, minutes: Int) {
        if (recorder != null) return
        graceSeconds = grace.coerceIn(10, 120); maxMinutes = minutes.coerceIn(1, 10)
        policy.graceMs = graceSeconds * 1000L; policy.maxMs = maxMinutes * 60_000L
        preferences.edit().putInt("grace_v2", graceSeconds).putInt("max", maxMinutes).apply()
    }

    fun avatar(person: Encounter) = repository.avatar(person.server, person.personId)

    fun cacheFace(server: String, profile: PersonProfile, jpeg: ByteArray) {
        val id = registeredPersonId(profile.userId) ?: return
        val normalized = runCatching { normalizeServerUrl(server) }.getOrNull() ?: return
        val key = "$normalized|$id"
        if (repository.avatar(normalized, id).isFile || !savingAvatars.add(key)) return
        avatarErrors.remove(key)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.saveAvatar(normalized, id, jpeg)
                }
                avatarRevision++
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { avatarErrors[key] = error.message ?: "顔写真を保存できませんでした" }
            finally { savingAvatars.remove(key) }
        }
    }

    fun arm() {
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "マイクの許可が必要です"; return
        }
        configure(graceSeconds, maxMinutes)
        policy.reset(); enabled = true; status = "1人の登録人物を認識すると録音します"
    }

    fun disable() {
        enabled = false; policy.reset(); finish("録音を停止しました")
        if (recorder == null) status = "会話録音は停止中"
    }

    fun foregroundEnded() { disable() }

    /** Called only for faces actually detected now; retained world anchors are not observations. */
    fun observe(profiles: List<PersonProfile>, faceCount: Int, server: String, live: Boolean) {
        if (!live) { if (enabled || recorder != null) disable(); return }
        val normalized = runCatching { normalizeServerUrl(server) }.getOrNull() ?: return
        if (active != null && active!!.server != normalized) { disable(); status = "接続先の変更により録音を停止しました"; return }
        serverForObservation = normalized
        val wall = System.currentTimeMillis()
        val known = profiles.filter { registeredPersonId(it.userId) != null }.distinctBy { it.userId }
        var dirty = false
        known.forEach { profile ->
            val id = registeredPersonId(profile.userId)!!
            val index = encounters.indexOfFirst { it.server == normalized && it.personId == id }
            if (index < 0 || wall - encounters[index].lastSeen >= 10_000 || encounters[index].name != profile.displayName) {
                val encounter = Encounter(id, profile.displayName, normalized, wall)
                if (index >= 0) encounters[index] = encounter else encounters.add(encounter)
                dirty = true
            }
        }
        if (dirty) persist()
        if (!enabled) return
        if (known.isEmpty() && faceCount == 1 && recorder == null) status = "人物IDの認識待ち（PCのperson_id対応が必要です）"
        if (faceCount > 1 && recorder == null) status = "複数の顔を検出中：1人だけ映してください"
        handle(policy.observe(known.mapNotNull { registeredPersonId(it.userId) }.toSet(), faceCount,
            SystemClock.elapsedRealtime()), known)
    }

    private fun handle(decision: RecordingDecision, profiles: List<PersonProfile>) {
        when (decision) {
            is RecordingDecision.Start -> {
                val profile = profiles.firstOrNull { registeredPersonId(it.userId) == decision.personId } ?: return
                start(profile)
            }
            is RecordingDecision.Finish -> finish(decision.reason)
            RecordingDecision.None -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun start(profile: PersonProfile) {
        if (recorder != null) return
        val id = UUID.randomUUID().toString()
        val audio = repository.audio(id)
        var next: MediaRecorder? = null
        try {
            next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(getApplication()) else MediaRecorder()
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1); next.setAudioSamplingRate(44100); next.setAudioEncodingBitRate(64000)
            next.setOutputFile(audio.absolutePath)
            next.prepare(); next.start()
            recorder = next
            active = PendingConversation(id, registeredPersonId(profile.userId)!!, profile.displayName,
                serverForObservation, System.currentTimeMillis(), 0)
            activeElapsed = SystemClock.elapsedRealtime()
            recordingSeconds = 0
            recordingName = profile.displayName; status = "${profile.displayName}との会話を録音中"
        } catch (_: Exception) {
            next?.release(); audio.delete(); policy.reset(); enabled = false
            status = "録音を開始できませんでした。マイクの許可や他の録音アプリを確認してください"
        }
    }

    private fun finish(reason: String) {
        val current = recorder ?: return
        val metadata = active!!
        recorder = null; active = null; recordingName = null
        val saved = try { current.stop(); true } catch (_: RuntimeException) { false }
        finally { current.release() }
        val duration = SystemClock.elapsedRealtime() - activeElapsed
        lastRecording = "${metadata.name} · ID ${metadata.personId} · ${duration / 1000}秒\n$reason"
        if (!saved || duration < 1000 || repository.audio(metadata.id).length() == 0L) {
            repository.audio(metadata.id).delete(); status = "短すぎる録音は送信せず終了しました"; return
        }
        pending.add(metadata.copy(endedAt = metadata.startedAt + duration))
        if (!persist()) return
        status = "$reason。音声を送信します"; uploadWaiting()
    }

    fun retry(id: String, allowUnconfirmed: Boolean = false) {
        val index = pending.indexOfFirst { it.id == id }
        if (checkingId == id) return
        if (index >= 0 && pending[index].error != null &&
            (!pending[index].awaitingResult || allowUnconfirmed)) {
            pending[index] = pending[index].copy(error = null)
            if (persist()) uploadWaiting()
        }
    }

    fun audioDetails(item: PendingConversation): String {
        val kb = repository.audio(item.id).length() / 1024
        return "ID ${item.personId} · ${(item.endedAt - item.startedAt) / 1000}秒 · $kb KB"
    }

    val recordingLabel: String get() = active?.let {
        "${it.name}との会話を録音中 · %d:%02d".format(recordingSeconds / 60, recordingSeconds % 60)
    } ?: status

    val recordingPersonId: Long? get() = active?.personId

    fun discard(id: String) {
        // Another recording may still be uploading; only this item's state matters.
        if (checkingId == id || pending.firstOrNull { it.id == id }?.error == null) return
        pending.removeAll { it.id == id }; persist(); repository.audio(id).delete()
    }

    private fun persist(): Boolean = try { repository.save(encounters.toList(), pending.toList()); true }
    catch (_: Exception) {
        enabled = false; policy.reset()
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null; active = null; recordingName = null
        status = "端末への保存に失敗しました。空き容量を確認してください"; false
    }

    private fun uploadWaiting() {
        if (uploader?.isActive == true) return
        uploader = viewModelScope.launch {
            while (isActive) {
                val item = pending.firstOrNull { it.error == null } ?: break
                uploadingId = item.id
                showCameraNotice("音声を送信中")
                // Persist before starting HTTP: a lost response does not mean the PC rejected the audio.
                val index = pending.indexOfFirst { it.id == item.id }
                pending[index] = item.copy(awaitingResult = true)
                if (!persist()) { uploadingId = null; break }
                uploadStatus = "${item.name}の音声を送信中"
                try {
                    val latest = withContext(Dispatchers.IO) {
                        repository.upload(item) {
                            viewModelScope.launch {
                                if (uploadingId == item.id) {
                                    uploadStatus = "${item.name}の処理結果待ち · 5分以上かかる場合があります（再送不要）"
                                    showCameraNotice("送信完了", temporary = true)
                                }
                            }
                        }
                    }
                    val key = "${item.server}|${item.personId}"
                    if ((latestSummaries[key]?.endedAt ?: 0) <= latest.endedAt) latestSummaries[key] = latest
                    pending.removeAll { it.id == item.id }
                    if (persist()) repository.audio(item.id).delete()
                    uploadStatus = "${item.name}の要約を受信しました · 履歴から確認できます"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    val index = pending.indexOfFirst { it.id == item.id }
                    if (index >= 0) pending[index] = item.copy(awaitingResult = item.awaitingResult || error !is AudioNotSentException,
                        error = error.message ?: "PCの処理結果を確認できませんでした")
                    persist()
                    uploadStatus = if (error is AudioNotSentException && !item.awaitingResult)
                        "PCへ接続できませんでした · 音声は端末に保存しています"
                    else "結果未確認 · PCで処理が続いている場合があります。「結果を確認」を使ってください"
                    showCameraNotice(if (error is AudioNotSentException) "PCへ接続できませんでした" else "送信結果は会話と履歴で確認できます", temporary = true)
                } finally { uploadingId = null }
            }
        }
    }

    private fun showCameraNotice(message: String, temporary: Boolean = false) {
        cameraNoticeJob?.cancel()
        cameraUploadStatus = message
        if (temporary) cameraNoticeJob = viewModelScope.launch {
            delay(3000)
            cameraUploadStatus = ""
        }
    }

    fun checkResult(id: String) {
        if (checkingId != null) return
        val item = pending.firstOrNull { it.id == id && it.error != null } ?: return
        checkingId = id
        viewModelScope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { repository.findResult(item) }
                if (entry == null) {
                    val index = pending.indexOfFirst { it.id == id }
                    if (index >= 0) pending[index] = item.copy(awaitingResult = true,
                        error = "履歴に結果はまだありません。処理中か失敗したかは、この応答だけでは判断できません。PC側の状態を確認してください")
                    persist()
                    uploadStatus = "結果はまだ確認できません · 音声は再送していません"
                } else {
                    val key = "${item.server}|${item.personId}"
                    if ((latestSummaries[key]?.endedAt ?: 0) <= entry.endedAt) latestSummaries[key] = entry
                    pending.removeAll { it.id == id }
                    if (persist()) repository.audio(id).delete()
                    uploadStatus = "${item.name}の保存済み要約を確認しました · 再送していません"
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val index = pending.indexOfFirst { it.id == id }
                if (index >= 0) pending[index] = item.copy(awaitingResult = true,
                    error = "結果の確認に失敗：${error.message ?: error.javaClass.simpleName}")
                persist(); uploadStatus = "結果を確認できませんでした · 音声は再送していません"
            } finally { checkingId = null }
        }
    }

    fun loadHistory(person: Encounter, more: Boolean = false) {
        if (more && historyLoading) return
        historyJob?.cancel()
        if (!more) { historyEntries = emptyList(); historyNext = null }
        historyKey = person.key; historyLoading = true; historyError = null
        val before = if (more) historyNext else null
        historyJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.history(person, before) }
                if (historyKey == person.key) {
                    historyEntries = (if (more) historyEntries + page.entries else page.entries).distinctBy { it.id }
                        .sortedWith(compareByDescending<ConversationEntry> { it.endedAt }.thenByDescending { it.id })
                    historyNext = page.next
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (historyKey == person.key) historyError = error.message ?: "履歴を取得できませんでした" }
            finally { if (isActive && historyKey == person.key) historyLoading = false }
        }
    }

    override fun onCleared() {
        // Normally finalized on Activity ON_STOP; unexpected shutdown must release the microphone.
        runCatching { recorder?.stop() }; recorder?.release(); recorder = null
        super.onCleared()
    }
}
