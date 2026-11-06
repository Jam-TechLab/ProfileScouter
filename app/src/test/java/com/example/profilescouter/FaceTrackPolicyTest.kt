/*
 * 顔の重複抑制、追跡IDの再利用、見失った対象の終了条件と座標変換を確認する単体テスト。
 */
package com.example.profilescouter

import org.junit.Assert.*
import org.junit.Test

class FaceTrackPolicyTest {
    @Test fun twoBoxesOnOneFaceBecomeOne() {
        assertEquals(listOf(0), uniqueFaceIndices(listOf(FaceRectangle(0f,0f,100f,100f),
            FaceRectangle(4f,5f,103f,102f)), listOf(1,2)))
    }
    @Test fun twoNearbyPeopleRemainSeparate() {
        assertEquals(listOf(0,1), uniqueFaceIndices(listOf(FaceRectangle(0f,0f,100f,100f),
            FaceRectangle(70f,0f,170f,100f)), listOf(1,2)))
    }
    @Test fun associationRequiresFaceProximity() {
        val box = FaceRectangle(100f,100f,200f,200f)
        assertEquals(0f, faceAssociationCost(150f,150f,box), 0.001f)
        assertTrue(faceAssociationCost(300f,150f,box) > 0.64f)
    }
    @Test fun projectionRotationIsInverseOfRawConversion() {
        for (rotation in listOf(0,90,180,270)) {
            val upright = toUprightImage(100f,200f,640,480,rotation)
            assertArrayEquals(floatArrayOf(100f,200f), toRawImage(upright[0],upright[1],640,480,rotation),0.001f)
        }
    }
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
