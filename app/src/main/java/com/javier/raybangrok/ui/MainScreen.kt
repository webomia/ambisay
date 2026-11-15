package com.javier.raybangrok.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.javier.raybangrok.session.AssistantUiState
import com.javier.raybangrok.session.Speaker
import com.javier.raybangrok.settings.AssistantSettings
import com.javier.raybangrok.settings.FrameSourceType
import com.javier.raybangrok.settings.Urgency
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    bus: StateFlow<AssistantUiState>,
    registration: StateFlow<RegistrationState?>,
    initialSettings: AssistantSettings,
    onSettingsChange: (AssistantSettings) -> Unit,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
    onRequestGlassesCamera: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val ui by bus.collectAsState()
    val reg by registration.collectAsState()
    var s by remember { mutableStateOf(initialSettings) }
    fun update(n: AssistantSettings) { s = n; onSettingsChange(n) }

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("RayBan ✕ Grok", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            // ---- Conexión gafas ----
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gafas Meta: ${reg?.name ?: "SDK sin inicializar"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (reg == RegistrationState.REGISTERED) OutlinedButton(onClick = onDisconnectGlasses) { Text("Desconectar") }
                        else Button(onClick = onConnectGlasses) { Text("Conectar gafas") }
                        OutlinedButton(onClick = onRequestGlassesCamera, enabled = reg == RegistrationState.REGISTERED) { Text("Permiso cámara") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fuente:")
                        Spacer(Modifier.width(8.dp))
                        FilterChip(s.source == FrameSourceType.META_GLASSES, { update(s.copy(source = FrameSourceType.META_GLASSES)) }, { Text("Gafas") })
                        Spacer(Modifier.width(8.dp))
                        FilterChip(s.source == FrameSourceType.PHONE_CAMERA, { update(s.copy(source = FrameSourceType.PHONE_CAMERA)) }, { Text("Cámara móvil") })
                    }
                }
            }

            // ---- Asistente ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.running) Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Parar asistente") }
                else Button(onClick = onStart) { Text("Iniciar asistente") }
                Text("${ui.sourceName} · ${ui.sourceState}")
            }
            ui.error?.let { Text("⚠️ $it", color = MaterialTheme.colorScheme.error) }

            ui.lastFrame?.let {
                Image(it.asImageBitmap(), contentDescription = "Lo que ve Grok", Modifier.fillMaxWidth().heightIn(max = 220.dp))
            }
            Text("Tarea en curso: ${ui.currentTask.ifBlank { "—" }}", fontWeight = FontWeight.SemiBold)
            if (ui.partialSpeech.isNotBlank()) Text("🎙️ ${ui.partialSpeech}")

            // ---- Modo continuo ----
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SwitchRow("Modo continuo (Grok mira siempre)", s.continuousMode) { update(s.copy(continuousMode = it)) }
                    SwitchRow("Escuchar mi voz", s.listenToVoice) { update(s.copy(listenToVoice = it)) }
                    SwitchRow("Micro/voz por Bluetooth SCO (gafas)", s.useBluetoothSco) { update(s.copy(useBluetoothSco = it)) }
                    SwitchRow("Mostrar en visor (Ray-Ban Display)", s.useGlassesDisplay) { update(s.copy(useGlassesDisplay = it)) }
                    SliderRow("Intervalo base", s.intervalSec, 2..30, "s") { update(s.copy(intervalSec = it, maxIntervalSec = maxOf(s.maxIntervalSec, it))) }
                    SliderRow("Intervalo máx. (escena quieta)", s.maxIntervalSec, s.intervalSec..120, "s") { update(s.copy(maxIntervalSec = it)) }
                    SliderRow("Sensibilidad a cambios", s.sensitivity, 0..100, "%") { update(s.copy(sensitivity = it)) }
                    SliderRow("Refresco forzado", s.heartbeatSec, 15..300, "s") { update(s.copy(heartbeatSec = it)) }
                    SliderRow("Frames por ventana", s.maxFramesPerWindow, 1..3, "") { update(s.copy(maxFramesPerWindow = it)) }
                    SliderRow("Cooldown entre avisos", s.cooldownSec, 0..180, "s") { update(s.copy(cooldownSec = it)) }
                    Text("Urgencia mínima para hablar sola:")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Urgency.entries.forEach { u ->
                            FilterChip(s.minUrgency == u, { update(s.copy(minUrgency = u)) }, { Text(u.name.lowercase()) })
                        }
                    }
                }
            }

            // ---- Métricas ----
            val st = ui.stats
            Text(
                "Ventanas ${st.windows} · enviadas ${st.sentToGrok} (${st.framesSent} frames) · sin cambio ${st.skippedNoChange}\n" +
                    "Habló ${st.spoken} · calló ${st.silenced} · intervalo ${"%.1f".format(st.currentIntervalSec)}s · " +
                    "latencia ${st.lastLatencyMs}ms · diff ${"%.2f".format(st.lastDiff)} (umbral ${"%.2f".format(s.sceneChangeThreshold)})\n" +
                    "Última decisión: ${st.lastVerdict}",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- Transcripción ----
            Text("Conversación", fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(ui.log.reversed()) { e ->
                    val who = when (e.who) { Speaker.USER -> "Tú"; Speaker.GROK -> "Grok"; Speaker.SYSTEM -> "·" }
                    Text("$who: ${e.text}", Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value$unit", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat().coerceAtLeast(range.first + 1f),
        )
    }
}
