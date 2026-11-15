package com.javier.raybangrok.ai

/**
 * Contexto de "tarea en curso" que se actualiza en cada ventana. Solo texto: las imágenes antiguas
 * NO se reenvían; se resumen en [sceneLog] para mantener coste y latencia acotados.
 */
class TaskContext(private val maxScenes: Int = 6, private val maxTurns: Int = 8) {
    var currentTask: String = "desconocida"
        private set
    private val sceneLog = ArrayDeque<Pair<Long, String>>()
    private val dialog = ArrayDeque<Pair<String, String>>() // rol -> texto
    private val spoken = ArrayDeque<String>()

    @Synchronized fun update(d: AssistantDecision, now: Long = System.currentTimeMillis()) {
        if (d.currentTask.isNotBlank()) currentTask = d.currentTask
        if (d.sceneSummary.isNotBlank()) {
            sceneLog.addLast(now to d.sceneSummary)
            while (sceneLog.size > maxScenes) sceneLog.removeFirst()
        }
    }

    @Synchronized fun addUser(text: String) = addTurn("usuario", text)
    @Synchronized fun addAssistant(text: String) {
        addTurn("asistente", text)
        spoken.addLast(text); while (spoken.size > 5) spoken.removeFirst()
    }

    private fun addTurn(role: String, text: String) {
        dialog.addLast(role to text)
        while (dialog.size > maxTurns) dialog.removeFirst()
    }

    @Synchronized fun render(now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("TAREA EN CURSO: $currentTask")
        if (sceneLog.isNotEmpty()) {
            appendLine("ESCENAS RECIENTES (más antigua → más reciente):")
            sceneLog.forEach { (t, s) -> appendLine("- hace ${(now - t) / 1000}s: $s") }
        }
        if (dialog.isNotEmpty()) {
            appendLine("CONVERSACIÓN RECIENTE:")
            dialog.forEach { (r, t) -> appendLine("- $r: $t") }
        }
        if (spoken.isNotEmpty()) {
            appendLine("YA DIJISTE (no lo repitas salvo que sea crítico):")
            spoken.forEach { appendLine("- $it") }
        }
    }

    @Synchronized fun reset() {
        currentTask = "desconocida"; sceneLog.clear(); dialog.clear(); spoken.clear()
    }
}
