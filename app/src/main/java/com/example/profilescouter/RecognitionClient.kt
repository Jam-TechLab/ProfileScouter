package com.example.profilescouter

import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** Blocking I/O: call on Dispatchers.IO. No photo or response is saved to disk. */
internal class RecognitionClient(baseUrl: String, healthPath: String = "/health", recognitionPath: String = "/recognize") {
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

internal fun normalizeEndpointPath(value: String): String {
    val path = "/" + value.trim().trimStart('/')
    val uri = URI(path)
    require(!value.trim().startsWith("//") && !path.contains("://") && uri.rawQuery == null &&
        uri.rawFragment == null && uri.rawPath == path && !path.any { it.isWhitespace() }) {
        "/hello や /image のようなパスを入力してください"
    }
    return path
}

internal fun normalizeServerUrl(value: String): String {
    val input = value.trim().trimEnd('/')
    require(input.isNotBlank()) { "PCのIPアドレスとポートを入力してください" }
    val completed = if (input.contains("://")) input else "http://$input"
    val uri = try { URI(completed) } catch (_: Exception) {
        throw IllegalArgumentException("URLの形式を確認してください")
    }
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        uri.path.isNullOrEmpty() && (uri.port == -1 || uri.port in 1..65535)) {
        "192.168.1.10:8000 のように入力してください（/recognizeは不要）"
    }
    return uri.toString()
}
