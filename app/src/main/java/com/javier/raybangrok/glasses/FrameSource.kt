package com.javier.raybangrok.glasses

import com.javier.raybangrok.vision.Frame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class SourceState { IDLE, CONNECTING, STREAMING, PAUSED, ERROR }

/** Fuente de vídeo abstracta: gafas Meta (DAT SDK) o cámara del móvil (pruebas). */
interface FrameSource {
    val name: String
    val state: StateFlow<SourceState>
    /** Frames ya muestreados (≈ [sampleFps]) y reducidos. */
    val frames: SharedFlow<Frame>
    val lastError: StateFlow<String?>
    fun start()
    fun stop()
}

/** Salida opcional al visor de las gafas (solo Meta Ray-Ban Display). */
interface GlassesDisplay {
    val available: Boolean
    suspend fun show(title: String, message: String)
    suspend fun clear()
}

object NoDisplay : GlassesDisplay {
    override val available = false
    override suspend fun show(title: String, message: String) = Unit
    override suspend fun clear() = Unit
}
