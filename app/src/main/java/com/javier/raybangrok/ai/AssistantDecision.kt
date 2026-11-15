package com.javier.raybangrok.ai

import com.javier.raybangrok.settings.Urgency

/** Respuesta estructurada que Grok devuelve en cada ventana (JSON Schema estricto). */
data class AssistantDecision(
    val shouldSpeak: Boolean,
    val urgency: Urgency,
    val message: String,
    /** Tarea en curso tal como Grok la entiende ahora (se realimenta en la siguiente llamada). */
    val currentTask: String,
    /** Resumen breve de lo que se ve (memoria visual en texto, mucho más barata que reenviar imágenes). */
    val sceneSummary: String,
    /** Grok indica si espera que algo cambie pronto (sugiere acortar el intervalo). */
    val expectChangeSoon: Boolean,
)
