package com.javier.raybangrok.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Escucha continua con el SpeechRecognizer de Android (se reinicia tras cada resultado o error).
 * Debe usarse desde el hilo principal. Se pausa mientras habla el TTS para no oírse a sí mismo.
 *
 * Alternativas documentadas: módulo experimental mwdat-speech del SDK de Meta (transcripción en las
 * gafas) o STT streaming de xAI (wss://api.x.ai/v1/stt). Ver README.
 */
class SpeechInput(
    private val context: Context,
    private val languageTag: String,
    private val onSpeechStart: () -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var paused = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active || !available) return
        active = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(listener) }
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    fun setPaused(p: Boolean) {
        if (paused == p) return
        paused = p
        if (p) recognizer?.cancel() else restartSoon(300)
    }

    private fun listen() {
        if (!active || paused) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        runCatching { recognizer?.startListening(intent) }
    }

    private fun restartSoon(delayMs: Long = 250) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = onSpeechStart()
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 300)
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onFinal)
            restartSoon()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }?.let(onPartial)
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
