package com.javier.raybangrok.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import com.javier.raybangrok.RayBanGrokApp
import com.javier.raybangrok.service.AssistantService
import com.javier.raybangrok.session.AssistantBus
import com.javier.raybangrok.settings.SettingsStore
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val registration = MutableStateFlow<RegistrationState?>(null)

    private val androidPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.BLUETOOTH_CONNECT] != false) {
            RayBanGrokApp.initWearables(application)
            observeWearables()
        }
    }

    /** Permiso de cámara de las gafas: lo concede el usuario en la app Meta AI. */
    private val glassesPerm = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        toast(if (status == PermissionStatus.Granted) "Cámara de las gafas autorizada" else "Permiso de cámara denegado")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        androidPerms.launch(perms.toTypedArray())
        handleDatIntent(intent)

        val store = SettingsStore(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen(
                    bus = AssistantBus.state,
                    registration = registration,
                    initialSettings = store.load(),
                    onSettingsChange = { s ->
                        store.save(s)
                        if (AssistantBus.state.value.running) AssistantService.pushSettings(this)
                    },
                    onConnectGlasses = {
                        if (!RayBanGrokApp.wearablesReady) toast("SDK de Meta no inicializado (¿permiso Bluetooth?)")
                        else Wearables.startRegistration(this)
                    },
                    onDisconnectGlasses = { if (RayBanGrokApp.wearablesReady) Wearables.startUnregistration(this) },
                    onRequestGlassesCamera = {
                        if (RayBanGrokApp.wearablesReady) glassesPerm.launch(Permission.CAMERA)
                    },
                    onStart = { AssistantService.start(this) },
                    onStop = { AssistantService.stop(this) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDatIntent(intent)
    }

    /** Peticiones de registro iniciadas desde la app Meta AI (deep link raybangrok://). */
    private fun handleDatIntent(intent: Intent?) {
        if (intent == null || !RayBanGrokApp.wearablesReady) return
        Wearables.handleIntent(intent) { request ->
            // TODO: mostrar diálogo de confirmación; RegistrationRequest expone aceptar/cancelar
            // (ver API reference de RegistrationRequest antes de llamarlo).
            toast("Solicitud de registro recibida desde Meta AI: $request")
        }
    }

    private var observing = false
    private fun observeWearables() {
        if (observing || !RayBanGrokApp.wearablesReady) return
        observing = true
        lifecycleScope.launch { Wearables.registrationState.collect { registration.value = it } }
        lifecycleScope.launch { Wearables.registrationErrorStream.collect { toast("Registro: ${it.description}") } }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
