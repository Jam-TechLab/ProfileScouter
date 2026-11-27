/*
 * 人物ID付き音声の送信と、人物ごとの共有会話履歴の取得・応答検証を担当する。
 */
package com.example.profilescouter

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

internal class AudioNotSentException(cause: IOException) : IOException(
    "音声送信前にPCへ接続できませんでした。PCのIP・ポート、同じネットワークか、サーバーの起動状態を確認してください。詳細：${cause.message}", cause)

internal class ConversationClient(server: String) {
    private val base = normalizeServerUrl(server)
    private fun request(path: String, write: ((java.io.OutputStream, String) -> Unit)? = null,
        onSent: () -> Unit = {}): JSONObject {
        val connection = URI(base + path).toURL().openConnection() as HttpURLConnection
        var bodyStarted = false
        try {
            connection.connectTimeout = 30_000
            // CPU Whisper may take several minutes; failed uploads stay on the phone.
            connection.readTimeout = if (write != null) 1_800_000 else 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (write != null) {
                val boundary = "Conversation-${UUID.randomUUID()}"
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.setChunkedStreamingMode(65536)
                connection.outputStream.use { bodyStarted = true; write(it, boundary) }
                onSent()
            }
            val code = connection.responseCode
            val input = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = input?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val chars = CharArray(512_000)
                var total = 0
                while (total < chars.size) {
                    val n = reader.read(chars, total, chars.size - total)
                    if (n < 0) break
                    total += n
                }
                check(total < chars.size) { "会話履歴の応答が大きすぎます" }
                String(chars, 0, total)
            }.orEmpty()
            check(code in 200..299) { "HTTP $code：${result.take(300)}" }
            return JSONObject(result)
        } catch (error: IOException) {
            if (write != null && !bodyStarted) throw AudioNotSentException(error)
            throw error
        } finally { connection.disconnect() }
    }
    fun upload(pending: PendingConversation, audio: File, onSent: () -> Unit = {}): ConversationEntry {
        require(audio.isFile && audio.length() > 0) { "録音ファイルが見つからないか、空です" }
        require(audio.length() <= 15L * 1024 * 1024) { "音声が送信上限の15 MBを超えています" }
        val json = request("/conversations", write = { output, boundary ->
            val values = mapOf("conversation_id" to pending.id, "person_id" to pending.personId.toString(),
                "started_at_ms" to pending.startedAt.toString(), "ended_at_ms" to pending.endedAt.toString())
            values.forEach { (key, value) -> output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".toByteArray()) }
            output.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"conversation.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n".toByteArray())
            audio.inputStream().use { it.copyTo(output) }
            output.write("\r\n--$boundary--\r\n".toByteArray())
        }, onSent = onSent)
        val entry = parseConversation(json)
        require(entry.id == pending.id && entry.personId == pending.personId &&
            entry.startedAt == pending.startedAt && entry.endedAt == pending.endedAt) { "音声と応答の対象が一致しません" }
        return entry
    }
    fun history(personId: Long, before: String? = null): ConversationPage {
        require(personId > 0)
        if (before != null) require(UUID.fromString(before).toString() == before)
        val json = request("/persons/$personId/conversations?limit=50" + (before?.let { "&before=$it" } ?: ""))
        require(json.getLong("person_id") == personId) { "履歴の人物IDが一致しません" }
        val array = json.getJSONArray("conversations")
        val entries = (0 until array.length()).map { parseConversation(array.getJSONObject(it)) }
        require(entries.all { it.personId == personId }) { "別の人物の会話が含まれています" }
        val next = if (json.isNull("next_cursor")) null else json.getString("next_cursor")
        if (next != null) require(UUID.fromString(next).toString() == next)
        return ConversationPage(entries, next)
    }

    /** 結果の確認だけを行い、音声を再送しない。履歴を会話IDで照合する。 */
    fun findResult(pending: PendingConversation): ConversationEntry? {
        var before: String? = null
        val cursors = mutableSetOf<String>()
        do {
            val page = history(pending.personId, before)
            page.entries.firstOrNull { it.id == pending.id }?.let { entry ->
                require(entry.startedAt == pending.startedAt && entry.endedAt == pending.endedAt) {
                    "会話IDと録音時刻が一致しません"
                }
                return entry
            }
            if (page.entries.any { it.endedAt < pending.endedAt }) return null
            before = page.next
            require(before == null || cursors.add(before)) { "履歴の読み込み位置が繰り返されています" }
        } while (before != null)
        return null
    }
}
