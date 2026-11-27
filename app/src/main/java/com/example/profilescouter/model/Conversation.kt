/*
 * 会話の対象人物・履歴・送信待ちデータと、認識状況から録音開始・終了を決める規則を定義する。
 */
package com.example.profilescouter.model


internal data class Encounter(val personId: Long, val name: String, val server: String, val lastSeen: Long) {
    val key: String get() = "$server|$personId"
}
internal data class PendingConversation(val id: String, val personId: Long, val name: String,
    val server: String, val startedAt: Long, val endedAt: Long, val error: String? = null,
    val awaitingResult: Boolean = false)
internal data class ConversationEntry(val id: String, val personId: Long, val startedAt: Long,
    val endedAt: Long, val summary: String)
internal data class ConversationPage(val entries: List<ConversationEntry>, val next: String?)

internal fun registeredPersonId(value: String): Long? = value.toLongOrNull()?.takeIf { it > 0 }

internal sealed interface RecordingDecision {
    data object None : RecordingDecision
    data class Start(val personId: Long) : RecordingDecision
    data class Finish(val reason: String) : RecordingDecision
}

/** Elapsed realtime only: wall-clock changes must not end a conversation early. */
internal class ConversationPolicy(var graceMs: Long = 10_000, var maxMs: Long = 300_000) {
    var target: Long? = null; private set
    private var lastSeen = 0L
    private var started = 0L
    fun reset() { target = null }
    fun observe(ids: Set<Long>, faceCount: Int, now: Long): RecordingDecision {
        if (faceCount > 1) return finish("複数の顔を検出したため終了")
        val only = ids.singleOrNull()?.takeIf { faceCount == 1 }
        val current = target
        if (current == null && only != null) {
            target = only; lastSeen = now; started = now
            return RecordingDecision.Start(only)
        }
        if (current != null && only != null && current != only) return finish("別の人物を認識したため終了")
        if (current != null && only == current) lastSeen = now
        return tick(now)
    }
    fun tick(now: Long): RecordingDecision = when {
        target == null -> RecordingDecision.None
        now - started >= maxMs -> finish("録音時間の上限で区切りました")
        now - lastSeen >= graceMs -> finish("相手を見失ったため終了")
        else -> RecordingDecision.None
    }
    private fun finish(reason: String): RecordingDecision {
        if (target == null) return RecordingDecision.None
        target = null
        return RecordingDecision.Finish(reason)
    }
}
