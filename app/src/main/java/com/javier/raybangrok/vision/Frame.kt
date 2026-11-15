package com.javier.raybangrok.vision

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Un frame muestreado del stream.
 * [bitmap] ya reducido (lado largo ≤ 768 px) para no gastar tokens/ancho de banda.
 * [thumb] luminancia 32x24 para detección de cambio de escena barata.
 */
class Frame(val bitmap: Bitmap, val thumb: IntArray, val timestampMs: Long) {
    fun toJpeg(quality: Int = 70): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray()
        }

    companion object {
        const val THUMB_W = 32
        const val THUMB_H = 24
        private const val MAX_SIDE = 768

        fun from(src: Bitmap, timestampMs: Long = System.currentTimeMillis()): Frame {
            val scale = MAX_SIDE.toFloat() / maxOf(src.width, src.height)
            val bmp = if (scale < 1f)
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
            else src
            val small = Bitmap.createScaledBitmap(bmp, THUMB_W, THUMB_H, true)
            val px = IntArray(THUMB_W * THUMB_H)
            small.getPixels(px, 0, THUMB_W, 0, 0, THUMB_W, THUMB_H)
            if (small !== bmp) small.recycle()
            val luma = IntArray(px.size) { i ->
                val c = px[i]
                (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            return Frame(bmp, luma, timestampMs)
        }
    }
}
