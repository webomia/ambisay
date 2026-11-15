package com.javier.raybangrok

import android.app.Application
import android.util.Log
import com.meta.wearable.dat.core.Wearables

class RayBanGrokApp : Application() {
    companion object {
        @Volatile var wearablesReady = false
            private set

        /** Inicializa el SDK de Meta una sola vez (tras conceder permisos Bluetooth). */
        fun initWearables(app: Application) {
            if (wearablesReady) return
            Wearables.initialize(app)
                .onSuccess { wearablesReady = true }
                .onFailure { error, _ -> Log.e("RayBanGrok", "DAT init falló: ${error.description}") }
        }
    }
}
