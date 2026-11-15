package com.javier.raybangrok.session

import android.graphics.Bitmap
import com.javier.raybangrok.glasses.SourceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class Speaker { USER, GROK, SYSTEM }
data class LogEntry(val who: Speaker, val text: String, val ts: Long = System.currentTimeMillis())

data class MonitorStats(
    val windows: Int = 0,
    val sentToGrok: Int = 0,
    val skippedNoChange: Int = 0,
    val spoken: Int = 0,
    val silenced: Int = 0,
    val framesSent: Int = 0,
    val currentIntervalSec: Double = 0.0,
    val lastLatencyMs: Long = 0,
    val lastDiff: Double = 0.0,
    val lastVerdict: String = "",
)

data class AssistantUiState(
    val running: Boolean = false,
    val sourceName: String = "",
    val sourceState: SourceState = SourceState.IDLE,
    val currentTask: String = "",
    val partialSpeech: String = "",
    val lastFrame: Bitmap? = null,
    val stats: MonitorStats = MonitorStats(),
    val log: List<LogEntry> = emptyList(),
    val error: String? = null,
)

/** Estado compartido servicio ⇄ UI (proceso único). */
object AssistantBus {
    val state = MutableStateFlow(AssistantUiState())

    fun log(who: Speaker, text: String) = state.update {
        it.copy(log = (it.log + LogEntry(who, text)).takeLast(100))
    }
    fun stats(f: (MonitorStats) -> MonitorStats) = state.update { it.copy(stats = f(it.stats)) }
}
