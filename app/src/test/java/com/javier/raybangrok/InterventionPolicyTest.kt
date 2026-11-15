package com.javier.raybangrok

import com.javier.raybangrok.ai.AssistantDecision
import com.javier.raybangrok.session.InterventionPolicy
import com.javier.raybangrok.settings.Urgency
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterventionPolicyTest {
    private fun d(speak: Boolean, u: Urgency, msg: String) = AssistantDecision(speak, u, msg, "cocinar", "cocina", false)

    @Test fun silenceByDefault() {
        val p = InterventionPolicy()
        assertFalse(p.evaluate(d(false, Urgency.HIGH, "hola"), 100_000, false, false).speak)
    }

    @Test fun cooldownBlocksButCriticalPasses() {
        val p = InterventionPolicy(cooldownMs = 20_000)
        assertTrue(p.evaluate(d(true, Urgency.NORMAL, "Gira la tortilla ahora"), 100_000, false, false).speak)
        assertFalse(p.evaluate(d(true, Urgency.HIGH, "Baja el fuego un poco"), 105_000, false, false).speak)
        assertTrue(p.evaluate(d(true, Urgency.CRITICAL, "¡Cuidado, se quema el aceite!"), 106_000, false, false).speak)
    }

    @Test fun userPriorityAndUrgencyThreshold() {
        val p = InterventionPolicy(minUrgency = Urgency.NORMAL, userPriorityWindowMs = 8_000)
        assertFalse(p.evaluate(d(true, Urgency.LOW, "dato curioso"), 100_000, false, false).speak)
        p.onUserActivity(200_000)
        assertFalse(p.evaluate(d(true, Urgency.HIGH, "siguiente paso"), 203_000, false, false).speak)
        assertTrue(p.evaluate(d(false, Urgency.LOW, "Respuesta a tu pregunta"), 203_000, true, false).speak)
    }

    @Test fun dedupe() {
        val p = InterventionPolicy(cooldownMs = 0)
        assertTrue(p.evaluate(d(true, Urgency.NORMAL, "Te falta añadir la sal al agua"), 100_000, false, false).speak)
        assertFalse(p.evaluate(d(true, Urgency.NORMAL, "Te falta añadir la sal al agua."), 140_000, false, false).speak)
    }
}
