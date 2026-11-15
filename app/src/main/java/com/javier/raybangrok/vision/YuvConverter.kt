package com.javier.raybangrok.vision

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * Convierte un buffer I420 (YUV 4:2:0 planar: Y, luego U, luego V) a Bitmap ARGB.
 * El sample oficial CameraAccess de Meta (v0.5–0.8) documenta que VideoFrame.buffer sin comprimir
 * contiene "raw I420 video data". Si una versión futura del SDK cambia el formato, ajustar aquí.
 *
 * Para ahorrar CPU se submuestrea por [step] (2 = mitad de resolución).
 */
object YuvConverter {
    fun i420ToBitmap(buffer: ByteBuffer, width: Int, height: Int, step: Int = 2): Bitmap? {
        val ySize = width * height
        val uvW = (width + 1) / 2
        val uvH = (height + 1) / 2
        val needed = ySize + 2 * uvW * uvH
        val dup = buffer.duplicate()
        if (dup.remaining() < needed) return null
        val data = ByteArray(needed)
        dup.get(data)

        val outW = width / step
        val outH = height / step
        val px = IntArray(outW * outH)
        val uOff = ySize
        val vOff = ySize + uvW * uvH
        var i = 0
        for (oy in 0 until outH) {
            val y = oy * step
            for (ox in 0 until outW) {
                val x = ox * step
                val yy = (data[y * width + x].toInt() and 0xFF) - 16
                val uvIndex = (y / 2) * uvW + (x / 2)
                val u = (data[uOff + uvIndex].toInt() and 0xFF) - 128
                val v = (data[vOff + uvIndex].toInt() and 0xFF) - 128
                val c = 1192 * maxOf(yy, 0)
                val r = ((c + 1634 * v) shr 10).coerceIn(0, 255)
                val g = ((c - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
                val b = ((c + 2066 * u) shr 10).coerceIn(0, 255)
                px[i++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, outW, outH, Bitmap.Config.ARGB_8888)
    }
}
