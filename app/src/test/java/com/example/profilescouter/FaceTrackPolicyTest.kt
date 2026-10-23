package com.example.profilescouter

import org.junit.Assert.*
import org.junit.Test

class FaceTrackPolicyTest {
    @Test fun oneFalseDetectionNeverReachesConfirmation() {
        assertEquals(2, consecutiveHits(1, 150))
        assertEquals(3, consecutiveHits(2, 150))
        assertEquals(3, consecutiveHits(2, 650))
        assertEquals(2, consecutiveHits(1, 900))
        assertEquals(1, consecutiveHits(1, 1300))
    }

    @Test fun temporaryIdsAreReusedOnlyDuringContinuousTracking() {
        assertTrue(canReuseTrackingId(8, 8, 200))
        assertFalse(canReuseTrackingId(8, 9, 200))
        assertFalse(canReuseTrackingId(null, null, 200))
        assertTrue(canReuseTrackingId(8, 8, 1600))
        assertFalse(canReuseTrackingId(8, 8, 2100))
    }

    @Test fun falseDetectionsAndLostCardsAreCleanedUp() {
        assertTrue(isTrackExpired(1, 1600))
        assertFalse(isTrackExpired(3, 1000))
        assertTrue(isTrackExpired(3, 5100))
    }

    @Test fun clippedFacesMustNotUpdateDepthOrWorldPosition() {
        assertTrue(isFaceFullyVisible(100f, 100f, 200f, 250f, 640, 480))
        assertFalse(isFaceFullyVisible(0f, 100f, 120f, 250f, 640, 480))
        assertFalse(isFaceFullyVisible(530f, 100f, 639f, 250f, 640, 480))
        assertFalse(isFaceFullyVisible(100f, 0f, 200f, 150f, 640, 480))
    }
}
