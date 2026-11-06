/*
 * 3方向写真の登録リクエスト、JSON結果の判定、入力不足やサイズ超過の扱いを確認する単体テスト。
 */
package com.example.profilescouter

import java.net.ServerSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class RegistrationClientTest {
    @Test fun postsThreePhotosAndJapaneseFieldsToRegister() {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val executor = Executors.newSingleThreadExecutor()
        val received = executor.submit<List<String>> {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = DataInputStream(socket.getInputStream())
                val headerBytes = ByteArrayOutputStream()
                var tail = 0
                while (true) {
                    val byte = input.readUnsignedByte()
                    headerBytes.write(byte)
                    tail = (tail shl 8) or byte
                    if (tail == 0x0d0a0d0a) break
                    check(headerBytes.size() < 16000)
                }
                val headers = headerBytes.toString("UTF-8")
                val length = headers.lineSequence().first { it.startsWith("Content-Length:", true) }
                    .substringAfter(':').trim().toInt()
                val body = ByteArray(length)
                input.readFully(body)
                val response = """{"registered":true,"person_id":123}""".toByteArray()
                socket.getOutputStream().use {
                    it.write("HTTP/1.1 201 Created\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    it.write(response)
                }
                listOf(headers, String(body, Charsets.UTF_8))
            }
        }
        try {
            val photos = FaceDirection.entries.associateWith { "JPEG-${it.name}".toByteArray() }
            val id = RegistrationClient("127.0.0.1:${server.localPort}")
                .register(" 登録テスト ", "情報工学科", "音楽が好き", photos)
            val (headers, requestBody) = received.get(5, TimeUnit.SECONDS)
            val contentType = headers.lineSequence().first { it.startsWith("Content-Type:", true) }
                .substringAfter(':').trim()
            assertEquals(123L, id)
            assertTrue(headers.startsWith("POST /register HTTP/1.1"))
            assertTrue(headers.lineSequence().any { it.equals("Accept: application/json", true) })
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            assertTrue(requestBody.contains("name=\"name\"\r\n\r\n登録テスト\r\n"))
            assertTrue(requestBody.contains("name=\"department\"\r\n\r\n情報工学科"))
            assertTrue(requestBody.contains("name=\"description\"\r\n\r\n音楽が好き"))
            FaceDirection.entries.forEach {
                assertTrue(requestBody.contains("name=\"${it.field}\"; filename="))
                assertTrue(requestBody.contains("JPEG-${it.name}"))
            }
            assertTrue(requestBody.endsWith("--${contentType.substringAfter("boundary=")}--\r\n"))
        } finally { server.close(); executor.shutdownNow() }
    }

    @Test fun htmlSuccessPageIsNotARegistrationReceipt() {
        assertThrows(IllegalStateException::class.java) {
            parseRegistrationResponse(200, "text/html", "<p>登録しました</p>")
        }
    }
    @Test fun malformedReceiptIsNotAccepted() {
        listOf("true", "1.5", "-1", "\"123\"").forEach { id ->
            assertThrows(IllegalStateException::class.java) {
                parseRegistrationResponse(201, "application/json", """{"registered":true,"person_id":$id}""")
            }
        }
    }
    @Test fun inputErrorKeepsServerMessage() {
        val error = assertThrows(RegistrationRejected::class.java) {
            parseRegistrationResponse(400, "application/json; charset=utf-8", """{"registered":false,"error":"左側面の顔が見つかりません"}""")
        }
        assertEquals("左側面の顔が見つかりません", error.message)
    }
    @Test fun oversizeUsesReadableMessage() {
        val error = assertThrows(RegistrationRejected::class.java) {
            parseRegistrationResponse(413, "application/json", """{"registered":false}""")
        }
        assertTrue(error.message!!.contains("サイズ"))
    }
    @Test fun missingPhotoIsRejectedBeforeNetwork() {
        assertThrows(IllegalArgumentException::class.java) {
            RegistrationClient("127.0.0.1:1").register("名前", "", "", mapOf(FaceDirection.FRONT to byteArrayOf(1)))
        }
    }
    @Test fun webFormLengthLimitsAreSupported() {
        validateRegistration("名".repeat(100), "所".repeat(100), "文".repeat(1000))
        assertThrows(IllegalArgumentException::class.java) { validateRegistration("", "", "") }
        assertThrows(IllegalArgumentException::class.java) { validateRegistration("名前", "", "文".repeat(1001)) }
    }
}
