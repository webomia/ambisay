package com.javier.raybangrok.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.javier.raybangrok.BuildConfig
import com.javier.raybangrok.RayBanGrokApp
import com.javier.raybangrok.ai.XaiClient
import com.javier.raybangrok.audio.BluetoothAudioRouter
import com.javier.raybangrok.audio.SpeechInput
import com.javier.raybangrok.audio.SpeechOutput
import com.javier.raybangrok.glasses.FrameSource
import com.javier.raybangrok.glasses.MetaGlassesSource
import com.javier.raybangrok.glasses.NoDisplay
import com.javier.raybangrok.glasses.PhoneCameraSource
import com.javier.raybangrok.session.AssistantBus
import com.javier.raybangrok.session.ContinuousMonitor
import com.javier.raybangrok.session.Speaker
import com.javier.raybangrok.settings.FrameSourceType
import com.javier.raybangrok.settings.SettingsStore
import com.javier.raybangrok.ui.MainActivity
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Servicio en primer plano que mantiene vivo el modo continuo con la pantalla apagada o la app en
 * segundo plano (tipo connectedDevice para las gafas, + camera/microphone si se usan).
 */
class AssistantService : LifecycleService() {

    private var source: FrameSource? = null
    private var monitor: ContinuousMonitor? = null
    private var tts: SpeechOutput? = null
    private var stt: SpeechInput? = null
    private var router: BluetoothAudioRouter? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val settings = SettingsStore(this).load()
        enterForeground(settings.source)
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_UPDATE_SETTINGS -> { monitor?.settings = SettingsStore(this).load(); return START_STICKY }
        }
        if (monitor != null) return START_STICKY
        startAssistant()
        return START_STICKY
    }

    private fun startAssistant() {
        val s = SettingsStore(this).load()
        RayBanGrokApp.initWearables(application)

        val xai = XaiClient(BuildConfig.XAI_API_KEY, BuildConfig.XAI_MODEL)
        if (!xai.configured) AssistantBus.log(Speaker.SYSTEM, "⚠️ Falta XAI_API_KEY en local.properties: no se llamará a Grok.")

        val src: FrameSource
        val display = if (s.source == FrameSourceType.META_GLASSES) {
            val m = MetaGlassesSource(lifecycleScope, wantDisplay = s.useGlassesDisplay)
            src = m; m.glassesDisplay
        } else {
            src = PhoneCameraSource(this, this); NoDisplay
        }
        source = src

        router = BluetoothAudioRouter(this).also { r ->
            if (s.useBluetoothSco && r.enableSco()) AssistantBus.log(Speaker.SYSTEM, "Audio por Bluetooth SCO (gafas).")
        }
        val out = SpeechOutput(this, s.languageTag).also { it.useCommunicationChannel = s.useBluetoothSco }
        tts = out

        val mon = ContinuousMonitor(
            scope = lifecycleScope,
            source = src,
            xai = xai,
            display = display,
            speak = { text, interrupt -> out.speak(text, interrupt) },
            stopSpeaking = { out.stop() },
            ttsBusy = { out.speaking.value },
            initialSettings = s,
        )
        monitor = mon

        if (s.listenToVoice && hasPerm(Manifest.permission.RECORD_AUDIO)) {
            stt = SpeechInput(
                this, s.languageTag,
                onSpeechStart = { mon.onUserSpeechStart() },
                onPartial = { p -> AssistantBus.state.update { it.copy(partialSpeech = p) } },
                onFinal = { f -> AssistantBus.state.update { it.copy(partialSpeech = "") }; mon.onUserUtterance(f) },
            ).also { it.start() }
            // No escucharse a sí mismo: pausar STT mientras habla el TTS.
            lifecycleScope.launch { out.speaking.collect { stt?.setPaused(it) } }
        }

        lifecycleScope.launch { src.state.collect { st -> AssistantBus.state.update { it.copy(sourceState = st) } } }
        lifecycleScope.launch { src.lastError.collect { e -> if (e != null) AssistantBus.state.update { it.copy(error = e) } } }

        acquireWakeLock()
        src.start()
        mon.start()
        AssistantBus.state.update { it.copy(running = true, sourceName = src.name, error = null) }
        AssistantBus.log(Speaker.SYSTEM, "Asistente iniciado (${src.name}, modo ${if (s.continuousMode) "continuo" else "solo voz"}).")
    }

    override fun onDestroy() {
        monitor?.stop(); source?.stop(); stt?.stop(); tts?.shutdown()
        router?.disableSco()
        wakeLock?.let { if (it.isHeld) it.release() }
        AssistantBus.state.update { it.copy(running = false, partialSpeech = "") }
        AssistantBus.log(Speaker.SYSTEM, "Asistente detenido.")
        super.onDestroy()
    }

    private fun enterForeground(src: FrameSourceType) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Asistente Grok", NotificationManager.IMPORTANCE_LOW))
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (src == FrameSourceType.PHONE_CAMERA && hasPerm(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        startForeground(NOTIF_ID, notification(), type)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AssistantService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Grok está mirando")
            .setContentText("Modo asistente activo con las gafas")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Parar", stop)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RayBanGrok::Assistant").apply { acquire(3 * 60 * 60 * 1000L) }
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL = "assistant"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "com.javier.raybangrok.STOP"
        private const val ACTION_UPDATE_SETTINGS = "com.javier.raybangrok.UPDATE_SETTINGS"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java))
        fun stop(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java).setAction(ACTION_STOP))
        fun pushSettings(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, AssistantService::class.java).setAction(ACTION_UPDATE_SETTINGS))
    }
}
