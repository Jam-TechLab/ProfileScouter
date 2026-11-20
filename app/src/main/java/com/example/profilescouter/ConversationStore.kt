/*
 * この端末で会った人と送信待ちの音声を保存し、再起動後も再送できるようにする。
 */
package com.example.profilescouter

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal class ConversationStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "conversations").apply { mkdirs() }
    private val state = AtomicFile(File(directory, "state.json"))
    fun audio(id: String): File {
        require(java.util.UUID.fromString(id).toString() == id)
        return File(directory, "$id.m4a")
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
                if (j.isNull("error")) null else j.getString("error"))
        }.getOrNull() }.filter { runCatching { audio(it.id).isFile }.getOrDefault(false) }
    }
    fun save(encounters: List<Encounter>, pending: List<PendingConversation>) {
        val json = JSONObject().put("encounters", JSONArray(encounters.map { p -> JSONObject()
            .put("personId", p.personId).put("name", p.name).put("server", p.server).put("lastSeen", p.lastSeen) }))
            .put("pending", JSONArray(pending.map { p -> JSONObject().put("id", p.id).put("personId", p.personId)
                .put("name", p.name).put("server", p.server).put("startedAt", p.startedAt)
                .put("endedAt", p.endedAt).put("error", p.error ?: JSONObject.NULL) }))
        val stream = state.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); state.finishWrite(stream) }
        catch (error: Exception) { state.failWrite(stream); throw error }
    }
}
