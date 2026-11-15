# RayBan ✕ Grok

App Android nativa (Kotlin + Jetpack Compose) que convierte las gafas **Ray-Ban Meta** en los **ojos de Grok**:
mientras el asistente está activo, la app recibe de forma continua el vídeo de la cámara de las gafas, decide
qué frames merece la pena enviar a la API de xAI y deja que Grok intervenga por iniciativa propia (o
responda cuando le hablas) por el audio de las gafas, o en el visor si son unas Meta Ray-Ban Display.

> Estado: esqueleto funcional que **compila** (`assembleDebug`) y pasa sus tests unitarios. No se ha probado
> aún con gafas reales ni con una clave de xAI. Documentación consultada el 16-11-2026.

---

## ⚠️ Aviso de privacidad

Mientras el asistente está activo, la app **capta de forma continua la imagen de la cámara de las gafas** (y el audio
del micrófono para entender lo que dices) y **envía fotogramas y texto a los servidores de xAI** para analizarlos.

- La cámara puede captar a **otras personas** y lugares. El LED de las gafas se enciende al usar la cámara, pero aun así
  avisa a quien tengas cerca, pide permiso cuando corresponda y **no lo uses donde esté prohibido grabar** (o donde la
  ley exija consentimiento).
- La app no guarda vídeo en el móvil: los frames viven en un buffer en memoria y se descartan. Las peticiones a xAI
  se envían con `store:false`, pero salen de tu dispositivo: revisa la política de datos de xAI (https://docs.x.ai) y
  las condiciones del Meta Wearables Device Access Toolkit.
- Eres responsable del uso que hagas de la app y de cumplir la normativa aplicable (por ejemplo, el RGPD en la UE).
- Para la cámara: detén el asistente (botón *Parar* de la notificación) cuando no lo necesites.

---

## 1. APIs que usa (verificadas)

### Meta Wearables Device Access Toolkit (DAT) — conexión con las gafas
- Repo Android: https://github.com/facebook/meta-wearables-dat-android (v1.0.1, 9-oct-2026; v1.0.0, 24-sep-2026)
- Docs: https://wearables.developer.meta.com/docs/getting-started-toolkit/
- API reference: https://wearables.developer.meta.com/docs/reference/android/dat/latest
- Estado: **developer preview**. Android 10+ (minSdk 29) e iOS 17.2+. Requiere Kotlin 2.2 (por eso el proyecto usa Kotlin 2.2.21).
- Se publica en **Maven Central** (`com.meta.wearable:mwdat-core|camera|display|mockdevice|…`): no hace falta token.
- Gafas soportadas: Ray-Ban Meta (Gen 1 y 2), Ray-Ban Meta Optics, Meta Ray-Ban Display, Oakley Meta HSTN y Vanguard.
- Qué da: sesión con el dispositivo (`Wearables.createSession` → `DeviceSession`), **streaming de vídeo**
  (`session.addCamera(StreamConfiguration)` → `camera.stream.videoStream`, 360x640 / 504x896 / 720x1280 a 2/7/15/24/30 fps),
  foto, **audio PCM del micro con el stream** (experimental: no se puede publicar en producción todavía),
  **visor** de Meta Ray-Ban Display (`session.addDisplay()` → `display.sendContent { flexBox { text(...) } }`),
  y módulos experimentales `inputs`, `motion`, `speech` (transcripción en las gafas) y voice invocations.
- Requisitos: app **Meta AI** instalada en el móvil, gafas emparejadas, **Developer Mode** activado en Meta AI para
  builds de desarrollo (entonces `APPLICATION_ID`/`CLIENT_TOKEN` pueden ser `0`). Para distribuir, crear la app,
  organización y canales de release en el **Wearables Developer Center**.

### API de xAI (Grok)
- Docs: https://docs.x.ai/overview · Modelos: https://docs.x.ai/docs/models · REST: https://api.x.ai/docs/
- **Responses API** `POST https://api.x.ai/v1/responses` (la doc marca Chat Completions como *Legacy*), con
  imágenes `{"type":"input_image","image_url":"data:image/jpeg;base64,..."}` → https://docs.x.ai/docs/guides/image-understanding
- **Structured Outputs** (`text.format = {type:"json_schema", strict:true}`) → https://docs.x.ai/docs/guides/structured-outputs
- Auth: `Authorization: Bearer $XAI_API_KEY`. Modelo por defecto: `grok-4.7` (configurable).
- Voz (no usada aún en el código, ver pendientes): Speech-to-Speech realtime `wss://api.x.ai/v1/realtime?model=grok-voice-latest`
  (0,08 $/min), TTS `POST /v1/tts` (15 $/1M caracteres), STT streaming `wss://api.x.ai/v1/stt` (0,20 $/h).
  https://docs.x.ai/developers/model-capabilities/audio/voice

### Android
- `SpeechRecognizer` (STT continuo), `TextToSpeech` (salida por el audio Bluetooth de las gafas),
  `AudioManager.setCommunicationDevice` (SCO, micro de las gafas), CameraX (fuente de prueba), foreground service.

---

## 2. Arquitectura

```
 Gafas Ray-Ban Meta ──BT/Wi-Fi──► DAT SDK (MetaGlassesSource)  ┐
 Cámara del móvil (PhoneCameraSource, pruebas) ────────────────┤ FrameSource (7 fps → muestreo ~2 fps, ≤768 px)
                                                               ▼
                                                     FrameRingBuffer (40 frames)
                                                               │  cada intervalo (adaptativo)
                                                               ▼
   SpeechRecognizer ──frase──►  ContinuousMonitor ── SceneChangeDetector (diff luma 32x24, keyframes)
   (prioridad usuario)              │       ▲
                                    │       └─ TaskContext (tarea en curso + resúmenes de escena + diálogo, solo texto)
                                    ▼
                               XaiClient  ──► POST /v1/responses (frames JPEG + contexto, JSON schema)
                                    │
                                    ▼  {should_speak, urgency, message, current_task, scene_summary, expect_change_soon}
                            InterventionPolicy (umbral urgencia, cooldown, prioridad usuario, anti-repetición)
                                    │
                     ┌──────────────┴──────────────┐
                     ▼                             ▼
          TextToSpeech → audio BT gafas     GlassesDisplay (Meta Ray-Ban Display)
```

Todo vive en `AssistantService` (servicio en primer plano), así sigue funcionando con la pantalla bloqueada.

| Paquete | Contenido |
|---|---|
| `glasses/` | `FrameSource` (interfaz), `MetaGlassesSource` (DAT SDK real), `PhoneCameraSource` (CameraX), `GlassesDisplay` |
| `vision/` | `Frame`, `FrameRingBuffer`, `SceneChangeDetector`, `YuvConverter` (I420 → Bitmap) |
| `ai/` | `XaiClient` (Responses API + JSON Schema), `AssistantDecision`, `TaskContext` |
| `session/` | `ContinuousMonitor` (bucle), `InterventionPolicy` (cuándo hablar), `AssistantBus` (estado para la UI) |
| `audio/` | `SpeechInput` (STT), `SpeechOutput` (TTS), `BluetoothAudioRouter` (SCO) |
| `service/` | `AssistantService` (foreground service, wake lock, notificación con "Parar") |
| `ui/` | `MainActivity` (registro Meta AI, permisos), `MainScreen` (Compose) |
| `settings/` | `AssistantSettings` + `SettingsStore` |

### Modo continuo (las gafas como ojos permanentes)
1. **Captura permanente**: mientras el servicio está activo, el stream de la cámara de las gafas (LOW, 7 fps) llega
   sin parar; se muestrea a ~2 fps y se guarda en un **buffer circular**.
2. **Ventanas**: cada *intervalo base* (2–30 s, por defecto 5 s) se evalúa la ventana de frames recibidos.
3. **Detección de cambio de escena**: se compara el último frame con el último enviado (miniatura de luminancia
   32x24). Si la diferencia está por debajo del umbral (slider *Sensibilidad*) y no toca *refresco forzado*
   (heartbeat, por defecto 60 s), la ventana se descarta **sin coste**.
4. **Keyframes**: si hay cambio, se envían 1–3 frames representativos (el actual + cortes de escena dentro de la ventana).
5. **Contexto de tarea en curso**: cada respuesta actualiza `current_task` y un resumen de escena; el historial se
   reenvía como **texto** (no imágenes antiguas) → coste por llamada acotado.
6. **Intervención proactiva**: Grok responde con JSON `should_speak / urgency / message`. La app habla solo si
   `should_speak` y urgencia ≥ *Urgencia mínima*, fuera del *cooldown*, sin repetir lo ya dicho, y si el usuario no
   está hablando (o habló hace < 8 s). `critical` se salta cooldown y prioridad y corta el TTS.
7. **Intervalo adaptativo**: si Grok espera cambios (`expect_change_soon`) o hay urgencia, vuelve al intervalo base;
   tras varias ventanas tranquilas lo alarga ×1,5 hasta el *intervalo máx.*
8. **Voz del usuario**: entrada adicional con prioridad. Al empezar a hablar se silencian los avisos; al terminar la
   frase se cancela la llamada proactiva en curso, se corta el TTS y se pregunta a Grok con el frame actual.
   El STT se pausa mientras habla el TTS (no se oye a sí mismo).
9. Con el interruptor **Modo continuo** apagado, la app solo consulta a Grok cuando hablas.

**Streaming real a xAI**: la Responses API es petición/respuesta; la API realtime de voz (`/v1/realtime`) es
audio↔audio y su documentación no menciona entrada de vídeo/imágenes, por eso se usa muestreo por ventanas.

---

## 3. Puesta en marcha

1. Abrir la carpeta en **Android Studio** (JDK 17). Copiar `local.properties.example` → `local.properties` y poner `sdk.dir`.
2. **Clave de xAI**: crearla en https://console.x.ai y añadir `XAI_API_KEY=xai-...` a `local.properties`
   (o variable de entorno `XAI_API_KEY`). Se inyecta en `BuildConfig`; **nunca** está en el código ni en git.
   ⚠️ Ojo: al inyectarse en `BuildConfig`, la clave queda **dentro del APK** que compiles. Úsalo solo para pruebas
   personales y **no compartas ese APK**; para distribuir hace falta un backend propio o ephemeral tokens.
   Opcional: `XAI_MODEL=` otro modelo con visión.
3. **Meta (Developer Mode)**:
   - Instalar la app **Meta AI**, emparejar las gafas y actualizar firmware.
   - En Meta AI: Ajustes → *App info* → pulsar varias veces la versión para activar **Developer Mode**
     (ver https://wearables.developer.meta.com/docs/getting-started-toolkit/).
   - Con Developer Mode, `mwdat_application_id=0` y `mwdat_client_token=0` sirven.
   - Para distribuir a otras personas: crear cuenta/organización en el **Wearables Developer Center**
     (https://wearables.developer.meta.com), registrar la app, pedir los permisos (cámara; audio streaming es
     experimental), y poner su `APPLICATION_ID` y `CLIENT_TOKEN` en `local.properties`.
4. Instalar el APK, aceptar permisos (Bluetooth, micro, cámara, notificaciones).
5. **Conectar gafas** (abre Meta AI para registrar la app) → **Permiso cámara** (en Meta AI) → Fuente *Gafas* →
   **Iniciar asistente**. Sin gafas: Fuente *Cámara móvil*.

---

## 4. Pendiente / TODO
- [ ] Probar en hardware real (Ray-Ban Meta Gen 2) y ajustar umbrales por defecto.
- [ ] Confirmar que `VideoFrame.buffer` sin comprimir sigue siendo **I420** en 1.0.1 (el sample 0.8 de Meta lo dice; `YuvConverter`).
- [ ] Confirmar si una misma `DeviceSession` admite **cámara + display** a la vez (Ray-Ban Display) o hacen falta dos sesiones.
- [ ] Diálogo para aceptar `RegistrationRequest` iniciadas desde Meta AI (`Wearables.handleIntent`, ahora solo muestra un toast).
- [ ] Verificar que xAI acepta `"detail":"low"` en `input_image` (la doc solo muestra `"high"`).
- [ ] Opción de usar el **micrófono de las gafas vía DAT** (`StreamConfiguration.audioCodec = AudioCodec.PCM(...)`,
      experimental) + **STT de xAI** (`wss://api.x.ai/v1/stt`) en lugar del SpeechRecognizer.
- [ ] Opción de **TTS de xAI** (`POST /v1/tts`, voces más naturales) o **Grok Voice realtime** para respuestas habladas.
      Para clientes móviles xAI recomienda **ephemeral tokens** en lugar de la clave directa.
- [ ] Para producción: no meter la clave en el APK; usar un backend propio o ephemeral tokens.
- [ ] Detección de cambio más robusta (embeddings ligeros on-device) y modo "solo cuando hay movimiento de cabeza" (`mwdat-motion`).
- [ ] Icono, tema y textos; el módulo `mwdat-mockdevice` (MockDeviceKit) para tests sin gafas está solo en debug, sin pantalla aún.
- [ ] Audio streaming DAT es experimental y no se puede publicar en canales de producción todavía.

## 5. Limitaciones
- **Latencia**: muestreo + subida + inferencia + TTS ≈ 1–5 s según modelo y red. Los modelos de razonamiento son más lentos;
  prueba un modelo más rápido en `XAI_MODEL` si hace falta. No sirve para avisos de seguridad en tiempo real estricto.
- **Coste**: cada ventana enviada = 1–3 imágenes + ~1 k tokens de texto. Con intervalo 5 s y sin filtrar serían ~720
  llamadas/hora; el filtro de cambio de escena, el heartbeat y el intervalo adaptativo lo reducen mucho. Las métricas en
  pantalla muestran ventanas enviadas vs. descartadas. Precios: https://docs.x.ai/docs/models
- **Batería**: el streaming de cámara consume bastante en las gafas (la doc recomienda baja resolución/fps) y en el
  móvil (decodificación, red, wake lock). Usa LOW/7 fps (ya configurado) y para el asistente cuando no lo necesites.
- **Bluetooth**: el stream puede pausarse (toque en la patilla, quitarse las gafas, otra experiencia de Meta); la app
  refleja `PAUSED` y espera a que el SDK reanude.
- El toolkit está en **developer preview**: las APIs pueden cambiar entre versiones.

## 6. Compilar
```
./gradlew assembleDebug        # APK en app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # tests de InterventionPolicy
```
Gradle 8.13 · AGP 8.13.0 · Kotlin 2.2.21 · compileSdk/targetSdk 36 · minSdk 29 · JDK 17.

## 7. Licencia

Distribuido bajo licencia MIT. Ver [LICENSE](LICENSE).

Este proyecto no está afiliado a Meta, Ray-Ban ni xAI. Las marcas pertenecen a sus respectivos propietarios.
