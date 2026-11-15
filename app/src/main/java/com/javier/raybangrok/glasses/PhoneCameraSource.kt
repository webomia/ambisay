package com.javier.raybangrok.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.javier.raybangrok.vision.Frame
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** Fuente de prueba sin gafas: cámara trasera del móvil vía CameraX. */
class PhoneCameraSource(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val sampleFps: Double = 2.0,
) : FrameSource {
    override val name = "Cámara del móvil"
    private val _state = MutableStateFlow(SourceState.IDLE)
    override val state = _state.asStateFlow()
    private val _frames = MutableSharedFlow<Frame>(extraBufferCapacity = 4)
    override val frames = _frames.asSharedFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError = _lastError.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var lastEmitMs = 0L

    override fun start() {
        _state.value = SourceState.CONNECTING
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= 1000 / sampleFps) {
                        lastEmitMs = now
                        val bmp = image.toBitmap()
                        val rot = image.imageInfo.rotationDegrees
                        val upright = if (rot != 0) Bitmap.createBitmap(
                            bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true
                        ) else bmp
                        _frames.tryEmit(Frame.from(upright, now))
                    }
                    image.close()
                }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                _state.value = SourceState.STREAMING
            } catch (e: Exception) {
                _lastError.value = "Cámara: ${e.message}"
                _state.value = SourceState.ERROR
            }
        }, ContextCompat.getMainExecutor(context))
    }

    override fun stop() {
        provider?.unbindAll()
        provider = null
        _state.value = SourceState.IDLE
    }
}
