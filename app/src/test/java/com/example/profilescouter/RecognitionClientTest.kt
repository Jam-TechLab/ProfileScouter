package com.example.profilescouter

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecognitionClientTest {
    @Test fun customEndpointsUseExactPaths() {
        val hello = serve { base -> RecognitionClient(base, "/hello", "/image").health() }
        assertTrue(hello.toString(Charsets.UTF_8).startsWith("GET /hello HTTP/1.1"))
        val image = serve { base -> RecognitionClient(base, "/hello", "/image").recognize(byteArrayOf(1), "r-1") }
        assertTrue(image.toString(Charsets.UTF_8).startsWith("POST /image HTTP/1.1"))
        assertEquals("/image", normalizeEndpointPath("image"))
    }
    private fun serve(responseCode: Int = 200, response: String = "{\"status\":\"ok\"}",
        action: (String) -> Unit): ByteArray {
        val server = ServerSocket(0)
        server.soTimeout = 5000
        val executor = Executors.newSingleThreadExecutor()
        val request = executor.submit<ByteArray> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().buffered()
                val header = java.io.ByteArrayOutputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) {
                    val byte = input.read()
                    check(byte >= 0)
                    header.write(byte)
                    tail = (tail + byte.toChar()).takeLast(4)
                }
                val headerText = header.toString("UTF-8")
                val length = Regex("Content-Length: (\\d+)", RegexOption.IGNORE_CASE)
                    .find(headerText)?.groupValues?.get(1)?.toInt() ?: 0
                val body = ByteArray(length)
                var count = 0
                while (count < length) {
                    val read = input.read(body, count, length - count)
                    check(read > 0)
                    count += read
                }
                val bytes = response.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $responseCode Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
                header.toByteArray() + body
            }
        }
        return try {
            action("http://127.0.0.1:${server.localPort}")
            request.get(5, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun healthCallsTheExpectedEndpoint() {
        val wire = serve { base -> assertEquals("{\"status\":\"ok\"}", RecognitionClient(base).health()) }
        assertTrue(wire.toString(Charsets.UTF_8).startsWith("GET /health HTTP/1.1"))
    }

    @Test fun jpegIsUploadedWithAgreedFieldNamesAndRequestId() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        val wire = serve(response = "{\"matched\":true}") { base ->
            assertEquals("{\"matched\":true}", RecognitionClient(base).recognize(jpeg, "test-42", "track-7", 123L, "face_crop"))
        }.toString(Charsets.ISO_8859_1)
        assertTrue(wire.startsWith("POST /recognize HTTP/1.1"))
        assertTrue(wire.contains("name=\"requestId\"\r\n\r\ntest-42\r\n"))
        assertTrue(wire.contains("name=\"schemaVersion\"\r\n\r\n1\r\n"))
        assertTrue(wire.contains("name=\"trackId\"\r\n\r\ntrack-7\r\n"))
        assertTrue(wire.contains("name=\"capturedAtMs\"\r\n\r\n123\r\n"))
        assertTrue(wire.contains("name=\"imageKind\"\r\n\r\nface_crop\r\n"))
        assertTrue(wire.contains("name=\"image\"; filename=\"capture.jpg\""))
        assertTrue(wire.contains("Content-Type: image/jpeg\r\n\r\n${jpeg.toString(Charsets.ISO_8859_1)}"))
    }

    @Test fun serverErrorsKeepStatusAndExplanation() {
        serve(422, "missing image") { base ->
            try { RecognitionClient(base).health(); fail("Expected HTTP failure") }
            catch (exception: IllegalStateException) {
                assertTrue(exception.message!!.contains("422"))
                assertTrue(exception.message!!.contains("missing image"))
            }
        }
    }

    @Test fun settingsUseBaseUrlAndRejectEndpointPaths() {
        assertEquals("http://192.168.1.10:8000", normalizeServerUrl(" http://192.168.1.10:8000/ "))
        assertEquals("http://192.168.1.10:8000", normalizeServerUrl("192.168.1.10:8000"))
        assertEquals("https://example.com", normalizeServerUrl("https://example.com"))
        listOf("", "http://host/recognize", "file:///tmp/a", "http://u:p@host", "http://host?x=1")
            .forEach { invalid ->
                try { normalizeServerUrl(invalid); fail("Should reject $invalid") }
                catch (_: IllegalArgumentException) { }
            }
    }
}
