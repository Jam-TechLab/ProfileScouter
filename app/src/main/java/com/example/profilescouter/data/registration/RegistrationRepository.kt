/*
 * 3方向の顔写真とプロフィールを登録APIへ送り、JSON応答から登録の成否と登録IDを確認する。
 */
package com.example.profilescouter.data.registration

import com.example.profilescouter.core.network.normalizeServerUrl
import com.example.profilescouter.model.FaceDirection
import com.example.profilescouter.model.validateRegistration

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

internal class RegistrationRejected(message: String) : Exception(message)

/** Blocking I/O. One deliberate request, no automatic retries or redirects. */
internal class RegistrationRepository(baseUrl: String) {
    private val base = normalizeServerUrl(baseUrl)

    fun register(name: String, department: String, description: String,
        photos: Map<FaceDirection, ByteArray>): Long {
        validateRegistration(name, department, description)
        require(FaceDirection.entries.all { photos[it]?.isNotEmpty() == true }) { "3方向の写真を撮影してください" }
        val boundary = "ScouterRegistration-${UUID.randomUUID()}"
        val parts = registrationParts(boundary, name, department, description, photos)
        val length = parts.sumOf { it.size.toLong() }
        require(length <= 15L * 1024 * 1024) { "写真の合計が15MBを超えています。撮り直してください" }
        val connection = URI("$base/register").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use { output -> parts.forEach { output.write(it) } }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readTextLimited() }.orEmpty()
            return parseRegistrationResponse(code, connection.contentType.orEmpty(), response)
        } finally { connection.disconnect() }
    }
}

private fun java.io.Reader.readTextLimited(): String {
    val buffer = CharArray(65537)
    var size = 0
    while (size < buffer.size) {
        val count = read(buffer, size, buffer.size - size)
        if (count < 0) break
        size += count
    }
    check(size <= 65536) { "登録結果が大きすぎます。PC側で登録状況を確認してください" }
    return String(buffer, 0, size)
}

internal fun registrationParts(boundary: String, name: String, department: String,
    description: String, photos: Map<FaceDirection, ByteArray>): List<ByteArray> = buildList {
    fun text(value: String) = value.toByteArray(Charsets.UTF_8)
    mapOf("name" to name.trim(), "department" to department.trim(), "description" to description.trim())
        .forEach { (field, value) ->
            add(text("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"\r\n\r\n$value\r\n"))
        }
    FaceDirection.entries.forEach { direction ->
        add(text("--$boundary\r\nContent-Disposition: form-data; name=\"${direction.field}\"; filename=\"${direction.name.lowercase()}.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))
        add(requireNotNull(photos[direction]))
        add(text("\r\n"))
    }
    add(text("--$boundary--\r\n"))
}

internal fun parseRegistrationResponse(code: Int, contentType: String, response: String): Long {
    val mediaType = contentType.substringBefore(';').trim().lowercase()
    check(mediaType == "application/json" || mediaType.endsWith("+json")) {
        "PCがJSON以外を返しました（HTTP $code）。登録APIのJSON対応と、PC側の登録状況を確認してください"
    }
    val json = try { JSONObject(response) } catch (_: Exception) {
        throw IllegalStateException("登録結果のJSONを読み取れません。PC側で登録状況を確認してください")
    }
    if (code !in 200..299 || json.opt("registered") != true) {
        val message = when (code) {
            413 -> "写真のサイズが大きすぎます。撮り直してください"
            else -> (json.opt("error") as? String)?.takeIf { it.isNotBlank() }?.take(300)
                ?: "登録できませんでした（HTTP $code）"
        }
        if (code in 400..499 || (code in 200..299 && json.opt("registered") == false))
            throw RegistrationRejected(message)
        throw IllegalStateException(message)
    }
    val id = json.opt("person_id")
    check((id is Int || id is Long) && (id as Number).toLong() > 0) {
        "登録IDを確認できません。再送せず、PC側で登録状況を確認してください"
    }
    return (id as Number).toLong()
}
