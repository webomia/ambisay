package com.javier.raybangrok.vision

import kotlin.math.abs

/**
 * Detección de cambio de escena muy barata (sin ML): diferencia media absoluta de luminancia
 * entre miniaturas 32x24, normalizada a 0..1, combinada con la fracción de píxeles que cambian.
 */
object SceneChangeDetector {
    private const val PIXEL_DELTA = 25

    fun diff(a: IntArray?, b: IntArray?): Double {
        if (a == null || b == null || a.size != b.size) return 1.0
        var sum = 0L
        var changed = 0
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            sum += d
            if (d > PIXEL_DELTA) changed++
        }
        val meanAbs = sum.toDouble() / a.size / 255.0
        val fraction = changed.toDouble() / a.size
        return (0.5 * meanAbs * 4).coerceAtMost(0.5) + 0.5 * fraction
    }

    /**
     * Elige hasta [max] frames representativos de la ventana: siempre el último y, hacia atrás,
     * los que difieran del ya elegido más que [threshold] (cortes de escena dentro de la ventana).
     */
    fun pickKeyframes(window: List<Frame>, threshold: Double, max: Int): List<Frame> {
        if (window.isEmpty()) return emptyList()
        val picked = mutableListOf(window.last())
        for (f in window.asReversed().drop(1)) {
            if (picked.size >= max) break
            if (diff(f.thumb, picked.last().thumb) >= threshold) picked += f
        }
        return picked.reversed()
    }

    /** Movimiento dentro de la ventana: máxima diferencia entre frames consecutivos. */
    fun motion(window: List<Frame>): Double =
        window.zipWithNext { x, y -> diff(x.thumb, y.thumb) }.maxOrNull() ?: 0.0
}
