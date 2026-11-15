package com.javier.raybangrok.audio

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * TTS de Android. Las Ray-Ban Meta se emparejan con el móvil como auriculares Bluetooth (A2DP/HFP),
 * así que el audio de la app sale por sus altavoces de oído abierto si son el dispositivo activo.
 * Con [BluetoothAudioRouter] en modo SCO, se usa USAGE_VOICE_COMMUNICATION para ir por el canal de
 * llamada (latencia menor y compatible con el micro de las gafas).
 */
class SpeechOutput(context: Context, languageTag: String) {
    private val _speaking = MutableStateFlow(false)
    val speaking = _speaking.asStateFlow()
    private var ready = false
    var useCommunicationChannel = false
        set(value) { field = value; applyAttributes() }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.forLanguageTag(languageTag)
            applyAttributes()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _speaking.value = false }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { _speaking.value = false }
        })
    }

    private fun applyAttributes() {
        if (!ready) return
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (useCommunicationChannel) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
    }

    /** [interrupt]=true corta lo que se esté diciendo (intervenciones críticas o respuesta al usuario). */
    fun speak(text: String, interrupt: Boolean) {
        if (!ready || text.isBlank()) return
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, UUID.randomUUID().toString())
    }

    fun stop() { tts.stop(); _speaking.value = false }
    fun shutdown() { tts.stop(); tts.shutdown() }
}
