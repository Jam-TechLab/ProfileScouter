/*
 * カメラ画像の行間や画素間隔が異なる場合も、YUVデータを正しく並べ替えられるか確認する単体テスト。
 */
package com.example.profilescouter.core.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class YuvPackingTest {
    @Test fun handlesPaddedRowsAndInterleavedChromaWithoutChangingPositions() {
        val y = ByteBuffer.wrap(byteArrayOf(99, 1, 2, 3, 4, 0, 0, 5, 6, 7, 8)).apply { position(1) }
        val u = ByteBuffer.wrap(byteArrayOf(10, 0, 11))
        val v = ByteBuffer.wrap(byteArrayOf(20, 0, 21))
        val packed = packYuv420(4, 2, arrayOf(y, u, v), intArrayOf(6, 4, 4), intArrayOf(1, 2, 2))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11), packed)
        assertEquals(1, y.position())
    }

    @Test fun handlesPlanarChroma() {
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 5), packYuv420(2, 2,
            arrayOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), ByteBuffer.wrap(byteArrayOf(5)),
                ByteBuffer.wrap(byteArrayOf(6))), intArrayOf(2, 1, 1), intArrayOf(1, 1, 1)))
    }
}
