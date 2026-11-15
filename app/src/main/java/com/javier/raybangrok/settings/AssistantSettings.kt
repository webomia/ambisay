package com.javier.raybangrok.settings

import android.content.Context

enum class FrameSourceType { META_GLASSES, PHONE_CAMERA }

enum class Urgency(val level: Int) {
    LOW(0), NORMAL(1), HIGH(2), CRITICAL(3);
    companion object {
        fun parse(s: String?): Urgency =
            entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) } ?: LOW
    }
}

/** Ajustes del asistente. Todos editables desde la UI y persistidos en SharedPreferences. */
data class AssistantSettings(
    val source: FrameSourceType = FrameSourceType.PHONE_CAMERA,
    /** true = Grok "mira" de forma continua; false = solo analiza cuando el usuario habla. */
    val continuousMode: Boolean = true,
    /** Cada cuántos segundos se evalúa una ventana de frames. */
    val intervalSec: Int = 5,
    /** Máximo del intervalo adaptativo cuando la escena está quieta. */
    val maxIntervalSec: Int = 20,
    /** 0..100. Más alto = reacciona a cambios de escena más pequeños (envía más). */
    val sensitivity: Int = 50,
    /** Aunque la escena no cambie, refrescar el contexto cada N segundos. */
    val heartbeatSec: Int = 60,
    /** Frames máximos por ventana enviados a Grok (1–3). */
    val maxFramesPerWindow: Int = 2,
    /** Tiempo mínimo entre intervenciones proactivas. */
    val cooldownSec: Int = 20,
    /** Urgencia mínima para que Grok hable por iniciativa propia. */
    val minUrgency: Urgency = Urgency.NORMAL,
    /** Silencio proactivo tras hablar el usuario (prioridad del usuario). */
    val userPriorityWindowSec: Int = 8,
    val listenToVoice: Boolean = true,
    /** Enrutar micro/altavoz por Bluetooth SCO (perfil manos libres de las gafas). */
    val useBluetoothSco: Boolean = false,
    /** Mostrar la respuesta en el visor (solo Meta Ray-Ban Display). */
    val useGlassesDisplay: Boolean = false,
    val languageTag: String = "es-ES",
) {
    /** Umbral de diferencia (0..1) a partir del cual se considera cambio de escena. */
    val sceneChangeThreshold: Double get() = 0.30 - (sensitivity.coerceIn(0, 100) / 100.0) * 0.27
}

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    fun load(): AssistantSettings {
        val d = AssistantSettings()
        return AssistantSettings(
            source = runCatching { FrameSourceType.valueOf(p.getString("source", d.source.name)!!) }.getOrDefault(d.source),
            continuousMode = p.getBoolean("continuousMode", d.continuousMode),
            intervalSec = p.getInt("intervalSec", d.intervalSec),
            maxIntervalSec = p.getInt("maxIntervalSec", d.maxIntervalSec),
            sensitivity = p.getInt("sensitivity", d.sensitivity),
            heartbeatSec = p.getInt("heartbeatSec", d.heartbeatSec),
            maxFramesPerWindow = p.getInt("maxFramesPerWindow", d.maxFramesPerWindow),
            cooldownSec = p.getInt("cooldownSec", d.cooldownSec),
            minUrgency = Urgency.parse(p.getString("minUrgency", d.minUrgency.name)),
            userPriorityWindowSec = p.getInt("userPriorityWindowSec", d.userPriorityWindowSec),
            listenToVoice = p.getBoolean("listenToVoice", d.listenToVoice),
            useBluetoothSco = p.getBoolean("useBluetoothSco", d.useBluetoothSco),
            useGlassesDisplay = p.getBoolean("useGlassesDisplay", d.useGlassesDisplay),
            languageTag = p.getString("languageTag", d.languageTag) ?: d.languageTag,
        )
    }

    fun save(s: AssistantSettings) {
        p.edit()
            .putString("source", s.source.name)
            .putBoolean("continuousMode", s.continuousMode)
            .putInt("intervalSec", s.intervalSec)
            .putInt("maxIntervalSec", s.maxIntervalSec)
            .putInt("sensitivity", s.sensitivity)
            .putInt("heartbeatSec", s.heartbeatSec)
            .putInt("maxFramesPerWindow", s.maxFramesPerWindow)
            .putInt("cooldownSec", s.cooldownSec)
            .putString("minUrgency", s.minUrgency.name)
            .putInt("userPriorityWindowSec", s.userPriorityWindowSec)
            .putBoolean("listenToVoice", s.listenToVoice)
            .putBoolean("useBluetoothSco", s.useBluetoothSco)
            .putBoolean("useGlassesDisplay", s.useGlassesDisplay)
            .putString("languageTag", s.languageTag)
            .apply()
    }
}
