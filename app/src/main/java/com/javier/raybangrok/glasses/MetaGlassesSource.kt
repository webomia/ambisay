package com.javier.raybangrok.glasses

import android.util.Log
import com.javier.raybangrok.vision.Frame
import com.javier.raybangrok.vision.YuvConverter
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.display.Display
import com.meta.wearable.dat.display.addDisplay
import com.meta.wearable.dat.display.views.FlexBoxBackground
import com.meta.wearable.dat.display.views.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fuente de vídeo real: Ray-Ban Meta / Oakley Meta / Meta Ray-Ban Display mediante el
 * Meta Wearables Device Access Toolkit (com.meta.wearable:mwdat-*, v1.0.1, developer preview).
 *
 * Flujo (según la documentación oficial):
 *  Wearables.initialize → startRegistration (app Meta AI) → permiso CAMERA (Meta AI) →
 *  createSession(AutoDeviceSelector) → session.start() → STARTED → session.addCamera(config) →
 *  camera.stream.start() → camera.stream.videoStream (VideoFrame I420).
 *
 * Para el modo continuo pedimos poca resolución/FPS (LOW, 7 fps): menos batería en las gafas,
 * menos Bluetooth y mejor calidad por frame. Después submuestreamos a [sampleFps].
 */
class MetaGlassesSource(
    private val scope: CoroutineScope,
    private val sampleFps: Double = 2.0,
    private val wantDisplay: Boolean = false,
) : FrameSource {
    override val name = "Gafas Meta"
    private val _state = MutableStateFlow(SourceState.IDLE)
    override val state = _state.asStateFlow()
    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 4)
    override val frames = _frames.asSharedFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError = _lastError.asStateFlow()

    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var display: Display? = null
    private val jobs = mutableListOf<Job>()
    private var lastEmitMs = 0L

    val glassesDisplay: GlassesDisplay = object : GlassesDisplay {
        override val available get() = display != null
        override suspend fun show(title: String, message: String) {
            val d = display ?: return
            d.sendContent {
                flexBox(gap = 8, padding = 24, background = FlexBoxBackground.CARD) {
                    text(title, style = TextStyle.HEADING)
                    text(message, style = TextStyle.BODY)
                }
            }.onFailure { e, _ -> Log.w(TAG, "Display: ${e.description}") }
        }
        override suspend fun clear() { display?.clearDisplay() }
    }

    override fun start() {
        if (session != null) return
        if (Wearables.registrationState.value != RegistrationState.REGISTERED) {
            fail("La app no está registrada en Meta AI. Pulsa 'Conectar gafas' primero.")
            return
        }
        _state.value = SourceState.CONNECTING
        scope.launch {
            val granted = Wearables.checkPermissionStatus(Permission.CAMERA).getOrNull() == PermissionStatus.Granted
            if (!granted) {
                fail("Falta el permiso de cámara de las gafas (se concede en la app Meta AI).")
                return@launch
            }
            Wearables.createSession(AutoDeviceSelector()).fold(
                { s -> attach(s) },
                { e, _ -> fail("No se pudo crear la sesión: ${e.description}") },
            )
        }
    }

    private fun attach(s: DeviceSession) {
        session = s
        jobs += scope.launch { s.errors.collect { e -> _lastError.value = e.description } }
        jobs += scope.launch {
            s.state.collect { st ->
                when (st) {
                    DeviceSessionState.STARTED -> if (camera == null) addCamera(s)
                    DeviceSessionState.PAUSED -> _state.value = SourceState.PAUSED
                    DeviceSessionState.STOPPED -> { cleanup(); if (_state.value != SourceState.ERROR) _state.value = SourceState.IDLE }
                    else -> Unit
                }
            }
        }
        s.start() // fire-and-forget; fallos por s.errors
    }

    private fun addCamera(s: DeviceSession) {
        s.addCamera(StreamConfiguration(videoQuality = VideoQuality.LOW, frameRate = 7)).fold(
            { cam ->
                camera = cam
                jobs += scope.launch(Dispatchers.Default) {
                    cam.stream.videoStream.collect { vf ->
                        if (vf.isCompressed) return@collect
                        val now = System.currentTimeMillis()
                        if (now - lastEmitMs < (1000 / sampleFps)) return@collect
                        lastEmitMs = now
                        YuvConverter.i420ToBitmap(vf.buffer, vf.width, vf.height)?.let {
                            _frames.tryEmit(Frame.from(it, now))
                        }
                    }
                }
                jobs += scope.launch {
                    cam.stream.state.collect { st ->
                        _state.value = when (st) {
                            StreamState.STREAMING -> SourceState.STREAMING
                            StreamState.PAUSED -> SourceState.PAUSED
                            else -> _state.value
                        }
                    }
                }
                jobs += scope.launch { cam.stream.errorStream.collect { e -> _lastError.value = e.description } }
                cam.stream.start().onFailure { e, _ -> fail("Stream: ${e.description}") }
                if (wantDisplay) tryAttachDisplay(s)
            },
            { e, _ -> fail("addCamera: ${e.description}") },
        )
    }

    /**
     * Solo Meta Ray-Ban Display. TODO: confirmar en hardware si una misma DeviceSession admite
     * cámara + display a la vez; si no, abrir una segunda sesión con
     * AutoDeviceSelector(filter = { it.isDisplayCapable() }).
     */
    private fun tryAttachDisplay(s: DeviceSession) {
        s.addDisplay().fold(
            { d -> display = d },
            { e, _ -> Log.i(TAG, "Display no disponible: ${e.description}") },
        )
    }

    override fun stop() {
        camera?.stop()
        display?.stop()
        session?.stop()
        cleanup()
        _state.value = SourceState.IDLE
    }

    private fun cleanup() {
        jobs.forEach { it.cancel() }; jobs.clear()
        camera = null; display = null; session = null
    }

    private fun fail(msg: String) {
        Log.e(TAG, msg)
        _lastError.value = msg
        _state.value = SourceState.ERROR
    }

    companion object { private const val TAG = "MetaGlassesSource" }
}
