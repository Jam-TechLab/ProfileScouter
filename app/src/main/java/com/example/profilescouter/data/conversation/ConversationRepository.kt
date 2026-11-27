/*
 * 会話履歴の通信と、人物一覧・顔アイコン・送信待ち音声の端末保存をまとめて扱う。
 */
package com.example.profilescouter.data.conversation

import com.example.profilescouter.core.camera.bitmapToJpeg
import com.example.profilescouter.model.ConversationEntry
import com.example.profilescouter.model.Encounter
import com.example.profilescouter.model.PendingConversation

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class ConversationRepository(context: Context) {
    private val directory = File(context.noBackupFilesDir, "conversations").apply { mkdirs() }
    private val state = AtomicFile(File(directory, "state.json"))
    fun upload(item: PendingConversation, onSent: () -> Unit): ConversationEntry {
        val client = ConversationClient(item.server)
        val uploaded = client.upload(item, audio(item.id), onSent)
        // 古い再送で他の端末の新しい会話を上書きしない。
        return runCatching { client.history(item.personId).entries.firstOrNull() }.getOrNull() ?: uploaded
    }
    fun history(person: Encounter, before: String?) = ConversationClient(person.server).history(person.personId, before)
    fun findResult(item: PendingConversation) = ConversationClient(item.server).findResult(item)
    fun audio(id: String): File {
        require(java.util.UUID.fromString(id).toString() == id)
        return File(directory, "$id.m4a")
    }
    fun avatar(server: String, personId: Long): File {
        require(personId > 0)
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(server.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "recognized-face-$key-$personId.jpg")
    }
    fun saveAvatar(server: String, personId: Long, jpeg: ByteArray) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "顔写真を画像として読み込めません" }
        val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 576) options.inSampleSize *= 2
        val image = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)) {
            "顔写真を画像として読み込めません"
        }
        val file = AtomicFile(avatar(server, personId))
        try {
            val bytes = bitmapToJpeg(image, maxEdge = 144)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        } finally { image.recycle() }
    }
    private fun read(): JSONObject = try {
        state.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    } catch (_: Exception) { JSONObject() }
    fun encounters(): List<Encounter> {
        val list = read().optJSONArray("encounters") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> runCatching {
            val j = list.getJSONObject(i)
            Encounter(j.getLong("personId"), j.getString("name"), j.getString("server"), j.getLong("lastSeen"))
        }.getOrNull() }
    }
    fun pending(): List<PendingConversation> {
        val list = read().optJSONArray("pending") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i -> runCatching {
            val j = list.getJSONObject(i)
            PendingConversation(j.getString("id"), j.getLong("personId"), j.getString("name"),
                j.getString("server"), j.getLong("startedAt"), j.getLong("endedAt"),
                if (j.isNull("error")) null else j.getString("error"),
                j.optBoolean("awaitingResult", true))
        }.getOrNull() }.filter { runCatching { audio(it.id).isFile }.getOrDefault(false) }
    }
    fun save(encounters: List<Encounter>, pending: List<PendingConversation>) {
        val json = JSONObject().put("encounters", JSONArray(encounters.map { p -> JSONObject()
            .put("personId", p.personId).put("name", p.name).put("server", p.server).put("lastSeen", p.lastSeen) }))
            .put("pending", JSONArray(pending.map { p -> JSONObject().put("id", p.id).put("personId", p.personId)
                .put("name", p.name).put("server", p.server).put("startedAt", p.startedAt)
                .put("endedAt", p.endedAt).put("error", p.error ?: JSONObject.NULL)
                .put("awaitingResult", p.awaitingResult) }))
        val stream = state.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); state.finishWrite(stream) }
        catch (error: Exception) { state.failWrite(stream); throw error }
    }
}
