package com.javier.raybangrok.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
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
        // Si el permiso Bluetooth ya estaba concedido, inicializa el SDK de Meta ya, antes de que
        // llegue la vuelta del registro desde Meta AI (si no, la respuesta se pierde).
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            RayBanGrokApp.initWearables(application)
            observeWearables()
        }
        androidPerms.launch(perms.toTypedArray())

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

    private var observing = false
    private fun observeWearables() {
        if (observing || !RayBanGrokApp.wearablesReady) return
        observing = true
        lifecycleScope.launch { Wearables.registrationState.collect { registration.value = it } }
        lifecycleScope.launch {
            Wearables.registrationErrorStream.collect {
                Log.e("RayBanGrok", "Registro falló: $it / ${it.description}")
                toast("Registro: ${it.description}. ¿Está activado el modo desarrollador en Meta AI?")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
