/*
 * PCの認識JSONを検証し、共通の人物データへ変換する。
 */
package com.example.profilescouter.data.recognition

import com.example.profilescouter.model.FacePacket
import com.example.profilescouter.model.FaceRecognitionResult
import com.example.profilescouter.model.PersonProfile
import com.example.profilescouter.model.registeredPersonId

import org.json.JSONObject

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
