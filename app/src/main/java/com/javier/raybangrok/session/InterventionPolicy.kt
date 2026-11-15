package com.javier.raybangrok.session

import com.javier.raybangrok.ai.AssistantDecision
import com.javier.raybangrok.settings.Urgency

/**
 * Decide si una respuesta de Grok se dice en voz alta. Lógica pura (sin Android) → testeable.
 *
 *  - Turno del usuario: se habla siempre que haya mensaje.
 *  - Proactivo: should_speak && urgencia ≥ umbral && fuera de cooldown && sin prioridad del usuario
 *    && no duplicado. CRITICAL se salta cooldown y prioridad del usuario.
 */
class InterventionPolicy(
    var cooldownMs: Long = 20_000,
    var minUrgency: Urgency = Urgency.NORMAL,
    var userPriorityWindowMs: Long = 8_000,
    private val dedupeWindowMs: Long = 120_000,
) {
    var lastSpokeAtMs = Long.MIN_VALUE / 2; private set
    var lastUserActivityMs = Long.MIN_VALUE / 2; private set
    private var lastMessage = ""

    data class Verdict(val speak: Boolean, val reason: String)

    fun onUserActivity(now: Long) { lastUserActivityMs = now }

    fun evaluate(d: AssistantDecision, now: Long, userTurn: Boolean, ttsBusy: Boolean): Verdict {
        if (d.message.isBlank()) return Verdict(false, "mensaje vacío")
        if (userTurn) return Verdict(true, "respuesta al usuario").also { commit(d, now) }
        if (!d.shouldSpeak) return Verdict(false, "Grok decide callar")
        val critical = d.urgency == Urgency.CRITICAL
        if (d.urgency.level < minUrgency.level) return Verdict(false, "urgencia ${d.urgency} < ${minUrgency}")
        if (!critical && ttsBusy) return Verdict(false, "ya está hablando")
        if (!critical && now - lastUserActivityMs < userPriorityWindowMs) return Verdict(false, "prioridad del usuario")
        if (!critical && now - lastSpokeAtMs < cooldownMs) return Verdict(false, "cooldown")
        if (now - lastSpokeAtMs < dedupeWindowMs && similar(d.message, lastMessage))
            return Verdict(false, "repetido")
        commit(d, now)
        return Verdict(true, "intervención ${d.urgency}")
    }

    private fun commit(d: AssistantDecision, now: Long) {
        lastSpokeAtMs = now
        lastMessage = d.message
    }

    companion object {
        /** Similitud de Jaccard sobre palabras ≥ 0.7 → se considera la misma frase. */
        fun similar(a: String, b: String): Boolean {
            if (a.isBlank() || b.isBlank()) return false
            val wa = a.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            val wb = b.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            if (wa.isEmpty() || wb.isEmpty()) return a.equals(b, ignoreCase = true)
            return wa.intersect(wb).size.toDouble() / wa.union(wb).size >= 0.7
        }
    }
}
