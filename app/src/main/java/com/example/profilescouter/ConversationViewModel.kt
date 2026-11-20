/*
 * 人物認識に連動する前景録音、音声の送信待ち・再送、会った人と共有履歴の状態を管理する。
 */
package com.example.profilescouter

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
    private val store = ConversationStore(application)
    private val preferences = application.getSharedPreferences("conversation_settings", 0)
    private val policy = ConversationPolicy()
    var enabled by mutableStateOf(false); private set
    var status by mutableStateOf("会話録音は停止中"); private set
    var recordingName by mutableStateOf<String?>(null); private set
    var uploadStatus by mutableStateOf(""); private set
    var graceSeconds by mutableIntStateOf(preferences.getInt("grace", 10)); private set
    var maxMinutes by mutableIntStateOf(preferences.getInt("max", 5)); private set
    val encounters = mutableStateListOf<Encounter>().apply { addAll(store.encounters()) }
    val pending = mutableStateListOf<PendingConversation>().apply { addAll(store.pending()) }
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
        pending.replaceAll { it.copy(error = it.error ?: "前回の送信結果を確認できません。再送できます") }
        viewModelScope.launch {
            while (isActive) {
                delay(250)
                if (enabled) handle(policy.tick(SystemClock.elapsedRealtime()), emptyList())
            }
        }
    }

    fun configure(grace: Int, minutes: Int) {
        if (recorder != null) return
        graceSeconds = grace.coerceIn(3, 30); maxMinutes = minutes.coerceIn(1, 10)
        policy.graceMs = graceSeconds * 1000L; policy.maxMs = maxMinutes * 60_000L
        preferences.edit().putInt("grace", graceSeconds).putInt("max", maxMinutes).apply()
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
        val audio = store.audio(id)
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
        if (!saved || duration < 1000 || store.audio(metadata.id).length() == 0L) {
            store.audio(metadata.id).delete(); status = "短すぎる録音は送信せず終了しました"; return
        }
        pending.add(metadata.copy(endedAt = metadata.startedAt + duration))
        if (!persist()) return
        status = "$reason。音声を送信します"; uploadWaiting()
    }

    fun retry(id: String) {
        val index = pending.indexOfFirst { it.id == id }
        if (index >= 0) { pending[index] = pending[index].copy(error = null); persist(); uploadWaiting() }
    }

    fun discard(id: String) {
        // Another recording may still be uploading; only this item's state matters.
        if (pending.firstOrNull { it.id == id }?.error == null) return
        pending.removeAll { it.id == id }; persist(); store.audio(id).delete()
    }

    private fun persist(): Boolean = try { store.save(encounters.toList(), pending.toList()); true }
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
                uploadStatus = "${item.name}の音声を処理中（PCで数分かかる場合があります）"
                try {
                    val latest = withContext(Dispatchers.IO) {
                        val client = ConversationClient(item.server)
                        client.upload(item, store.audio(item.id))
                        // A late retry may be older than another phone's recording.
                        // Display the shared latest record, not necessarily this upload.
                        runCatching { client.history(item.personId).entries.firstOrNull() }.getOrNull()
                    }
                    val key = "${item.server}|${item.personId}"
                    if (latest != null && (latestSummaries[key]?.endedAt ?: 0) <= latest.endedAt) latestSummaries[key] = latest
                    pending.removeAll { it.id == item.id }
                    if (persist()) store.audio(item.id).delete()
                    uploadStatus = "${item.name}の会話を保存しました"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    val index = pending.indexOfFirst { it.id == item.id }
                    if (index >= 0) pending[index] = item.copy(error = error.message ?: "送信できませんでした")
                    persist(); uploadStatus = "送信待ちに残しました。「会話と履歴」から再送できます"
                }
            }
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
                val page = withContext(Dispatchers.IO) { ConversationClient(person.server).history(person.personId, before) }
                if (historyKey == person.key) {
                    historyEntries = (if (more) historyEntries + page.entries else page.entries).distinctBy { it.id }
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
