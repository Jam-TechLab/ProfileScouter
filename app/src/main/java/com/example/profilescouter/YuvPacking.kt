package com.example.profilescouter

import java.nio.ByteBuffer

/** Cache Image plane properties once. Avoid JNI getters for every pixel. */
internal fun packYuv420(width: Int, height: Int, buffers: Array<ByteBuffer>,
    rowStrides: IntArray, pixelStrides: IntArray): ByteArray {
    require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
    val output = ByteArray(width * height * 3 / 2)
    val planes = buffers.map { it.duplicate() }
    val starts = planes.map { it.position() }
    for (row in 0 until height) {
        val offset = starts[0] + row * rowStrides[0]
        if (pixelStrides[0] == 1) {
            planes[0].position(offset)
            planes[0].get(output, row * width, width)
        } else for (col in 0 until width)
            output[row * width + col] = planes[0].get(offset + col * pixelStrides[0])
    }
    var index = width * height
    for (row in 0 until height / 2) for (col in 0 until width / 2) {
        output[index++] = planes[2].get(starts[2] + row * rowStrides[2] + col * pixelStrides[2])
        output[index++] = planes[1].get(starts[1] + row * rowStrides[1] + col * pixelStrides[1])
    }
    return output
}
