/*
 * 認識用PCへの接続確認と顔画像のHTTP送信、接続先アドレスとパスの検証を担当する。
 */
package com.example.profilescouter.data.recognition

import com.example.profilescouter.core.network.normalizeEndpointPath
import com.example.profilescouter.core.network.normalizeServerUrl

import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Blocking I/O: call on Dispatchers.IO. No photo or response is saved to disk. */
internal class RecognitionRepository(baseUrl: String, healthPath: String = "/health", recognitionPath: String = "/recognize") {
    private val base = normalizeServerUrl(baseUrl)
    private val healthEndpoint = normalizeEndpointPath(healthPath)
    private val recognitionEndpoint = normalizeEndpointPath(recognitionPath)

    fun health(): String = request(healthEndpoint, "GET")

    fun recognize(jpeg: ByteArray, requestId: String, trackId: String = "manual",
        capturedAtMs: Long = System.currentTimeMillis(), imageKind: String = "full_frame"): String {
        require(jpeg.isNotEmpty()) { "送信画像が空です" }
        require(Regex("[A-Za-z0-9_-]{1,128}").matches(requestId) &&
            Regex("[A-Za-z0-9_-]{1,128}").matches(trackId))
        require(imageKind in listOf("face_crop", "full_frame"))
        val boundary = "Scouter-${UUID.randomUUID()}"
        return request(recognitionEndpoint, "POST", "multipart/form-data; boundary=$boundary") { output ->
            mapOf("schemaVersion" to "1", "trackId" to trackId,
                "capturedAtMs" to capturedAtMs.toString(), "imageKind" to imageKind).forEach { (name, value) ->
                output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
            }
            output.write(("--$boundary\r\nContent-Disposition: form-data; name=\"requestId\"\r\n\r\n" +
                "$requestId\r\n--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"image\"; filename=\"capture.jpg\"\r\n" +
                "Content-Type: image/jpeg\r\n\r\n").toByteArray(Charsets.UTF_8))
            output.write(jpeg)
            output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }
    }

    private fun request(path: String, method: String, contentType: String? = null,
        body: ((java.io.OutputStream) -> Unit)? = null): String {
        val connection = URI("$base$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.outputStream.use(body)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val chars = CharArray(16000)
                var total = 0
                while (total < chars.size) {
                    val count = reader.read(chars, total, chars.size - total)
                    if (count < 0) break
                    total += count
                }
                String(chars, 0, total)
            }.orEmpty()
            check(code in 200..299) { "HTTP $code\n$result" }
            return result
        } finally { connection.disconnect() }
    }
}
