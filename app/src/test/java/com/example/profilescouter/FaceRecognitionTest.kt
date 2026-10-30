package com.example.profilescouter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FaceRecognitionTest {
    @Test fun simpleThreeFieldProfileIsAcceptedWithoutInventingIdsOrScores() {
        val json = JSONObject().put("displayName", "山田").put("affiliation", "軽音").put("bio", "ギター")
        val result = parseFaceResponse(json.toString(), packet)
        assertEquals("山田", result.profile!!.displayName)
        assertTrue(result.simpleProfile)
        assertEquals("", result.profile.userId)
        assertNull(result.similarity)
        assertEquals("PROFILE · 受信済み", profileCardContent(1, result, RecognitionMode.LIVE).badge)
        rejects { parseFaceResponse(json.put("requestId", "wrong-id").toString(), packet) }
    }

    @Test fun malformedSimpleProfilesAreRejected() {
        rejects { parseFaceResponse("{\"displayName\":\"山田\",\"affiliation\":null,\"bio\":\"\"}", packet) }
        rejects { parseFaceResponse("{\"displayName\":\"\",\"affiliation\":\"\",\"bio\":\"\"}", packet) }
    }
    private val packet = FacePacket("request-1", "track-1", 123L, byteArrayOf(1), 1, FaceCrop(0, 0, 100, 100))
    private fun response(): JSONObject = JSONObject()
        .put("schemaVersion", 1).put("requestId", "request-1").put("trackId", "track-1")
        .put("matched", true).put("userId", "user-1").put("similarity", 0.9)
        .put("profile", JSONObject().put("displayName", "テスト人物").put("affiliation", "班A").put("bio", "こんにちは"))

    @Test fun matchedResponseProvidesProfile() {
        val result = parseFaceResponse(response().toString(), packet)
        assertTrue(result.matched)
        assertEquals("テスト人物", result.profile!!.displayName)
        assertEquals("user-1", result.profile.userId)
    }

    @Test fun unmatchedResponseDoesNotLeakAProfile() {
        val json = response().put("matched", false).put("userId", JSONObject.NULL).put("profile", JSONObject.NULL)
        assertNull(parseFaceResponse(json.toString(), packet).profile)
        json.put("profile", JSONObject().put("displayName", "must not be shown"))
        rejects { parseFaceResponse(json.toString(), packet) }
    }

    @Test fun mismatchedIdsAndUnsupportedVersionsAreRejected() {
        rejects { parseFaceResponse(response().put("requestId", "old-request").toString(), packet) }
        rejects { parseFaceResponse(response().put("trackId", "different-face").toString(), packet) }
        rejects { parseFaceResponse(response().put("schemaVersion", 2).toString(), packet) }
        rejects { parseFaceResponse(response().put("similarity", 90).toString(), packet) }
    }

    @Test fun cropIncludes25PercentMarginAndStaysInsideImage() {
        assertEquals(FaceCrop(75, 50, 225, 350), paddedFaceCrop(100, 100, 200, 300, 640, 480))
        assertEquals(FaceCrop(0, 0, 125, 125), paddedFaceCrop(0, 0, 100, 100, 640, 480))
        assertEquals(FaceCrop(515, 355, 640, 480), paddedFaceCrop(540, 380, 640, 480, 640, 480))
    }

    @Test fun pipelineStartsOfflineAndModeChangesClearResults() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val pipeline = FaceRecognitionPipeline(scope)
            val candidates = listOf(FaceCandidate("track-1", 1, 12, FaceCrop(0, 0, 100, 100)))
            assertNull(pipeline.choose(candidates, 10000))
            pipeline.changeMode(RecognitionMode.MOCK)
            assertEquals("track-1", pipeline.choose(candidates, 10000)!!.key)
            pipeline.results["track-1"] = parseFaceResponse(response().toString(), packet)
            pipeline.changeMode(RecognitionMode.LIVE)
            assertTrue(pipeline.results.isEmpty())
            pipeline.changeMode(RecognitionMode.OFF)
            assertNull(pipeline.choose(candidates, 20000))
        } finally { scope.cancel() }
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejected response") }
        catch (_: IllegalArgumentException) { }
    }
}
