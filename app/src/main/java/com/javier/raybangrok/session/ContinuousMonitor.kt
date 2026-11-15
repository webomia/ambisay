package com.javier.raybangrok.session

import android.util.Log
import com.javier.raybangrok.ai.AssistantDecision
import com.javier.raybangrok.ai.TaskContext
import com.javier.raybangrok.ai.XaiClient
import com.javier.raybangrok.glasses.FrameSource
import com.javier.raybangrok.glasses.GlassesDisplay
import com.javier.raybangrok.settings.AssistantSettings
import com.javier.raybangrok.settings.Urgency
import com.javier.raybangrok.vision.Frame
import com.javier.raybangrok.vision.FrameRingBuffer
import com.javier.raybangrok.vision.SceneChangeDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Motor del modo continuo: las gafas son los "ojos" de Grok mientras el asistente está activo.
 *
 *   stream (7 fps) → muestreo (~2 fps) → FrameRingBuffer
 *        ↓ cada intervalo (adaptativo)
 *   ventana = frames desde la última evaluación
 *   diff(último, último enviado) < umbral y sin heartbeat → se descarta (0 coste)
 *   si no → keyframes (1–3) + TaskContext (texto) → Grok (JSON: should_speak, urgency, message…)
 *        ↓
 *   InterventionPolicy (umbral de urgencia, cooldown, prioridad del usuario, dedupe) → TTS / visor
 *
 * La voz del usuario es una entrada adicional con prioridad: cancela la llamada proactiva en curso,
 * corta el TTS y lanza una petición inmediata con el frame actual.
 */
class ContinuousMonitor(
    private val scope: CoroutineScope,
    private val source: FrameSource,
    private val xai: XaiClient,
    private val display: GlassesDisplay,
    private val speak: (text: String, interrupt: Boolean) -> Unit,
    private val stopSpeaking: () -> Unit,
    private val ttsBusy: () -> Boolean,
    initialSettings: AssistantSettings,
) {
    @Volatile var settings: AssistantSettings = initialSettings
        set(value) { field = value; applyPolicy() }

    private val buffer = FrameRingBuffer(capacity = 40)
    private val task = TaskContext()
    private val policy = InterventionPolicy()
    private val requestLock = Mutex()

    private var ingestJob: Job? = null
    private var loopJob: Job? = null
    private var proactiveJob: Job? = null

    private var lastWindowEndMs = 0L
    private var lastSentThumb: IntArray? = null
    private var lastAnalyzedMs = 0L
    private var currentIntervalMs = initialSettings.intervalSec * 1000L
    private var quietStreak = 0

    init { applyPolicy() }

    private fun applyPolicy() {
        val s = settings
        policy.cooldownMs = s.cooldownSec * 1000L
        policy.minUrgency = s.minUrgency
        policy.userPriorityWindowMs = s.userPriorityWindowSec * 1000L
    }

    fun start() {
        task.reset(); buffer.clear()
        currentIntervalMs = settings.intervalSec * 1000L
        ingestJob = scope.launch {
            source.frames.collect { f ->
                buffer.add(f)
                AssistantBus.state.update { it.copy(lastFrame = f.bitmap) }
            }
        }
        loopJob = scope.launch {
            while (isActive) {
                delay(currentIntervalMs)
                if (settings.continuousMode) tick()
            }
        }
    }

    fun stop() {
        proactiveJob?.cancel(); loopJob?.cancel(); ingestJob?.cancel()
        buffer.clear()
    }

    /** Una ventana del modo continuo. */
    private fun tick() {
        val s = settings
        val now = System.currentTimeMillis()
        val window = buffer.since(lastWindowEndMs)
        lastWindowEndMs = now
        AssistantBus.stats { it.copy(windows = it.windows + 1, currentIntervalSec = currentIntervalMs / 1000.0) }
        val latest = window.lastOrNull() ?: return

        if (requestLock.isLocked || ttsBusy()) return // no apilar peticiones ni pisar al TTS

        val diff = SceneChangeDetector.diff(latest.thumb, lastSentThumb)
        val heartbeatDue = now - lastAnalyzedMs >= s.heartbeatSec * 1000L
        AssistantBus.stats { it.copy(lastDiff = diff) }

        if (diff < s.sceneChangeThreshold && !heartbeatDue) {
            AssistantBus.stats { it.copy(skippedNoChange = it.skippedNoChange + 1) }
            backOff()
            return
        }
        val keyframes = SceneChangeDetector.pickKeyframes(window, s.sceneChangeThreshold, s.maxFramesPerWindow.coerceIn(1, 3))
        proactiveJob = scope.launch { runRequest(keyframes, userUtterance = null) }
    }

    /** Llamado por el STT con la frase final del usuario. */
    fun onUserUtterance(text: String) {
        val now = System.currentTimeMillis()
        policy.onUserActivity(now)
        AssistantBus.log(Speaker.USER, text)
        proactiveJob?.cancel()          // prioridad del usuario
        stopSpeaking()
        val frames = listOfNotNull(buffer.latest())
        scope.launch { runRequest(frames, userUtterance = text) }
    }

    /** El usuario empieza a hablar: silenciar intervenciones proactivas. */
    fun onUserSpeechStart() = policy.onUserActivity(System.currentTimeMillis())

    private suspend fun runRequest(frames: List<Frame>, userUtterance: String?) {
        requestLock.withLock {
            val s = settings
            val t0 = System.currentTimeMillis()
            try {
                if (userUtterance != null) task.addUser(userUtterance)
                val d = xai.analyze(frames, task.render(), userUtterance, s.languageTag)
                val now = System.currentTimeMillis()
                lastAnalyzedMs = now
                frames.lastOrNull()?.let { lastSentThumb = it.thumb }
                task.update(d, now)
                AssistantBus.state.update { it.copy(currentTask = task.currentTask, error = null) }
                AssistantBus.stats {
                    it.copy(sentToGrok = it.sentToGrok + 1, framesSent = it.framesSent + frames.size, lastLatencyMs = now - t0)
                }
                deliver(d, userTurn = userUtterance != null, now = now)
                adapt(d)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "xAI", e)
                AssistantBus.state.update { it.copy(error = e.message) }
                backOff()
            }
        }
    }

    private suspend fun deliver(d: AssistantDecision, userTurn: Boolean, now: Long) {
        val v = policy.evaluate(d, now, userTurn, ttsBusy())
        AssistantBus.stats { it.copy(lastVerdict = v.reason) }
        if (!v.speak) {
            AssistantBus.stats { it.copy(silenced = it.silenced + 1) }
            return
        }
        task.addAssistant(d.message)
        AssistantBus.log(Speaker.GROK, if (userTurn) d.message else "[${d.urgency.name.lowercase()}] ${d.message}")
        AssistantBus.stats { it.copy(spoken = it.spoken + 1) }
        speak(d.message, userTurn || d.urgency == Urgency.CRITICAL)
        if (settings.useGlassesDisplay && display.available) display.show("Grok", d.message)
    }

    /** Intervalo adaptativo: rápido si hay acción o Grok espera cambios; lento si todo está quieto. */
    private fun adapt(d: AssistantDecision) {
        val base = settings.intervalSec * 1000L
        if (d.expectChangeSoon || d.urgency.level >= Urgency.HIGH.level || d.shouldSpeak) {
            quietStreak = 0
            currentIntervalMs = base
        } else backOff()
    }

    private fun backOff() {
        quietStreak++
        if (quietStreak >= 3) {
            currentIntervalMs = (currentIntervalMs * 1.5).toLong()
                .coerceAtMost(settings.maxIntervalSec * 1000L)
                .coerceAtLeast(settings.intervalSec * 1000L)
        }
    }

    companion object { private const val TAG = "ContinuousMonitor" }
}
