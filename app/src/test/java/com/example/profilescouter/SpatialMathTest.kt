/*
 * 画像座標の回転、空間座標の投影、画面外・カメラ背後の扱いと遠近感を確認する単体テスト。
 */
package com.example.profilescouter

import org.junit.Assert.*
import org.junit.Test

class SpatialMathTest {
    @Test fun rotatedCoordinatesReturnToSensorImage() {
        assertArrayEquals(floatArrayOf(100f, 200f), toRawImage(100f, 200f, 640, 480, 0), 0.001f)
        assertArrayEquals(floatArrayOf(100f, 200f), toRawImage(280f, 100f, 640, 480, 90), 0.001f)
        assertArrayEquals(floatArrayOf(100f, 200f), toRawImage(540f, 280f, 640, 480, 180), 0.001f)
        assertArrayEquals(floatArrayOf(100f, 200f), toRawImage(200f, 540f, 640, 480, 270), 0.001f)
    }

    // Perspective projection with clip.w = -camera.z.
    private val projection = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f,
        0f, 0f, -1f, -1f, 0f, 0f, -0.1f, 0f)

    @Test fun offScreenProfileIsNotMovedInsideViewport() {
        val result = project(floatArrayOf(3f, 0f, -2f), projection, 1000f, 800f)!!
        assertEquals(1250f, result[0], 0.001f)
        assertEquals(400f, result[1], 0.001f)
    }

    @Test fun objectsBehindCameraAreHidden() {
        assertNull(project(floatArrayOf(0f, 0f, 2f), projection, 1000f, 800f))
    }

    @Test fun profileGetsSmallerWithDistance() {
        val near = project(floatArrayOf(1f, 0f, -2f), projection, 1000f, 800f)!!
        val far = project(floatArrayOf(1f, 0f, -4f), projection, 1000f, 800f)!!
        assertEquals((near[0] - 500f) / 2, far[0] - 500f, 0.001f)
    }
}
