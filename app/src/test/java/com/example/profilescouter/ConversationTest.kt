/*
 * 人物ID・見失い猶予・録音の区切り、認識応答との互換、音声APIの通信を確認する。
 */
package com.example.profilescouter

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationTest {
    @Test fun briefLossAndReacquisitionKeepTheSameRecording() {
        val p = ConversationPolicy(10_000, 300_000)
        assertEquals(RecordingDecision.Start(7), p.observe(setOf(7), 1, 0))
        assertEquals(RecordingDecision.None, p.observe(emptySet(), 0, 3000))
        assertEquals(RecordingDecision.None, p.observe(setOf(7), 1, 8000))
        assertEquals(RecordingDecision.None, p.tick(17_999))
        assertTrue(p.tick(18_000) is RecordingDecision.Finish)
        assertEquals(RecordingDecision.None, p.tick(18_001))
    }
    @Test fun otherPersonAndMultipleFacesFinishTheOldTarget() {
        val p = ConversationPolicy()
        p.observe(setOf(1), 1, 0)
        assertTrue(p.observe(setOf(2), 1, 100) is RecordingDecision.Finish)
        assertEquals(RecordingDecision.Start(2), p.observe(setOf(2), 1, 200))
        assertTrue(p.observe(setOf(2), 2, 300) is RecordingDecision.Finish)
        assertEquals(RecordingDecision.None, p.observe(setOf(2, 3), 2, 400))
    }
    @Test fun maximumDurationSegmentsAndResetStopsTheTimer() {
        val p = ConversationPolicy(maxMs = 60_000)
        p.observe(setOf(1), 1, 0)
        assertTrue(p.observe(setOf(1), 1, 60_000) is RecordingDecision.Finish)
        assertEquals(RecordingDecision.Start(1), p.observe(setOf(1), 1, 60_001))
        p.reset()
        assertEquals(RecordingDecision.None, p.tick(999_999))
    }
    @Test fun noIdentityDoesNotStartRecording() {
        val p = ConversationPolicy()
        assertEquals(RecordingDecision.None, p.observe(emptySet(), 1, 0))
        assertNull(registeredPersonId("demo_1"))
        assertNull(registeredPersonId("0"))
        assertNull(registeredPersonId("-1"))
    }
    @Test fun simpleResponseCanCarryARealPersonIdAndOldResponseStillDisplays() {
        val packet = FacePacket("r", "t", 1, byteArrayOf(1), 1, FaceCrop(0, 0, 10, 10))
        val json = JSONObject().put("displayName", "Test").put("affiliation", "Group").put("bio", "Summary")
        assertEquals("", parseFaceResponse(json.toString(), packet).profile!!.userId)
        json.put("person_id", 7)
        val result = parseFaceResponse(json.toString(), packet)
        assertEquals("7", result.profile!!.userId)
        assertEquals("Summary", profileCardContent(1, result, RecognitionMode.LIVE).bio)
        json.put("person_id", 1.5)
        try { parseFaceResponse(json.toString(), packet); fail("Fractional ID accepted") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun uploadsAreBoundToThePersonAndConversationAndHistoryAcceptsOtherRecorders() {
        val id = "7e1b4192-60f0-4ac8-9df3-626cc978195f"
        val entry = JSONObject().put("conversation_id", id).put("person_id", 7)
            .put("started_at_ms", 1000).put("ended_at_ms", 2000).put("summary", "Test summary")
        val server = ServerSocket(0).apply { soTimeout = 5000 }
        val executor = Executors.newSingleThreadExecutor()
        var body = ""
        val serving = executor.submit {
            repeat(3) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().buffered()
                    fun line(): String {
                        val out = java.io.ByteArrayOutputStream()
                        while (true) {
                            val b = input.read(); check(b >= 0)
                            if (b == 10) return out.toString("ISO-8859-1").trimEnd('\r')
                            out.write(b)
                        }
                    }
                    val request = line()
                    val headers = mutableListOf<String>()
                    while (true) { val value = line(); if (value.isEmpty()) break; headers += value }
                    if (request.startsWith("POST")) {
                        val out = java.io.ByteArrayOutputStream()
                        if (headers.any { it.equals("Transfer-Encoding: chunked", ignoreCase = true) }) {
                            while (true) {
                                val length = line().substringBefore(';').toInt(16)
                                if (length == 0) { while (line().isNotEmpty()) { }; break }
                                repeat(length) { val b = input.read(); check(b >= 0); out.write(b) }
                                check(line().isEmpty())
                            }
                        } else {
                            val length = headers.first { it.startsWith("Content-Length:", true) }.substringAfter(':').trim().toInt()
                            repeat(length) { val b = input.read(); check(b >= 0); out.write(b) }
                        }
                        body = out.toString("ISO-8859-1")
                    }
                    val json = if (request.startsWith("GET")) JSONObject().put("person_id", 7)
                        .put("conversations", org.json.JSONArray().put(entry)).put("next_cursor", JSONObject.NULL) else entry
                    val bytes = json.toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }
        val audio = File.createTempFile("conversation-test", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val base = "http://127.0.0.1:${server.localPort}"
            val client = ConversationClient(base)
            val p = PendingConversation(id, 7, "Test", base, 1000, 2000)
            assertEquals("Test summary", client.upload(p, audio).summary)
            assertTrue(body.contains("name=\"person_id\"\r\n\r\n7\r\n"))
            assertTrue(body.contains("name=\"conversation_id\"\r\n\r\n$id\r\n"))
            assertTrue(body.contains("name=\"audio\"; filename=\"conversation.m4a\""))
            assertFalse(body.contains("recorder_id"))
            assertEquals(id, client.history(7).entries.single().id)
            try { client.upload(p.copy(personId = 8), audio); fail("Wrong person response accepted") }
            catch (_: IllegalArgumentException) { }
            serving.get(5, TimeUnit.SECONDS)
        } finally { server.close(); executor.shutdownNow(); audio.delete() }
    }
}
