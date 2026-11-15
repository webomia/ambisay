package com.javier.raybangrok.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Enruta micro y voz a las gafas usando el perfil manos libres (SCO). Así el SpeechRecognizer
 * escucha con los micrófonos de las gafas en lugar del móvil.
 */
class BluetoothAudioRouter(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun glassesConnected(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }

    /** Devuelve true si se activó SCO. */
    fun enableSco(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sco = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            sco != null && am.setCommunicationDevice(sco)
        } else {
            @Suppress("DEPRECATION")
            run { am.mode = AudioManager.MODE_IN_COMMUNICATION; am.startBluetoothSco(); am.isBluetoothScoOn = true; true }
        }
    }

    fun disableSco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") run { am.stopBluetoothSco(); am.isBluetoothScoOn = false; am.mode = AudioManager.MODE_NORMAL }
    }
}
