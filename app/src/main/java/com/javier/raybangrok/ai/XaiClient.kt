package com.javier.raybangrok.ai

import android.util.Base64
import com.javier.raybangrok.settings.Urgency
import com.javier.raybangrok.vision.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cliente mínimo de la API de xAI (https://docs.x.ai).
 *
 * Usa la Responses API (POST https://api.x.ai/v1/responses), que la documentación de xAI marca como
 * la principal (Chat Completions aparece como "Legacy"):
 *  - imágenes: content item {"type":"input_image","image_url":"data:image/jpeg;base64,..."}
 *  - texto:    {"type":"input_text","text":"..."}
 *  - salida estructurada: text.format = {type:"json_schema", name, schema, strict:true}
 *  - store=false (xAI recomienda no guardar historial cuando se envían imágenes)
 * Auth: cabecera "Authorization: Bearer $XAI_API_KEY".
 *
 * La llamada es cancelable: si el usuario habla, la llamada proactiva en curso se cancela.
 */
class XaiClient(
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://api.x.ai/v1",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val configured: Boolean get() = apiKey.isNotBlank()

    suspend fun analyze(
        frames: List<Frame>,
        context: String,
        userUtterance: String?,
        languageTag: String,
    ): AssistantDecision = withContext(Dispatchers.IO) {
        require(configured) { "Falta XAI_API_KEY en local.properties" }

        val userContent = JSONArray()
        val header = buildString {
            appendLine(context)
            appendLine()
            if (userUtterance != null) {
                appendLine("EL USUARIO ACABA DE DECIR: \"$userUtterance\"")
                appendLine("Responde a su petición usando lo que ves. should_speak debe ser true.")
            } else {
                appendLine("Ventana automática del modo continuo (${frames.size} frame(s), el último es el actual).")
                appendLine("Decide si merece la pena interrumpir al usuario.")
            }
        }
        userContent.put(JSONObject().put("type", "input_text").put("text", header))
        frames.forEach { f ->
            val b64 = Base64.encodeToString(f.toJpeg(), Base64.NO_WRAP)
            userContent.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", "data:image/jpeg;base64,$b64")
                    // "detail" aparece en la doc de xAI con valor "high"; "low" ahorra tokens.
                    // TODO: verificar que xAI acepta "low" (formato compatible OpenAI).
                    .put("detail", "low")
            )
        }

        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("input", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(languageTag)))
                .put(JSONObject().put("role", "user").put("content", userContent)))
            .put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema")
                .put("name", "assistant_decision")
                .put("strict", true)
                .put("schema", SCHEMA)))

        val req = Request.Builder()
            .url("$baseUrl/responses")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val raw = http.newCall(req).await()
        parse(raw)
    }

    private fun parse(raw: String): AssistantDecision {
        val root = JSONObject(raw)
        val text = extractOutputText(root) ?: error("Respuesta sin texto: ${raw.take(300)}")
        val j = JSONObject(text)
        return AssistantDecision(
            shouldSpeak = j.optBoolean("should_speak", false),
            urgency = Urgency.parse(j.optString("urgency")),
            message = j.optString("message").trim(),
            currentTask = j.optString("current_task").trim(),
            sceneSummary = j.optString("scene_summary").trim(),
            expectChangeSoon = j.optBoolean("expect_change_soon", false),
        )
    }

    /** Responses API: output[] → item type "message" → content[] → type "output_text" → text. */
    private fun extractOutputText(root: JSONObject): String? {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: return null
        for (i in 0 until output.length()) {
            val item = output.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (k in 0 until content.length()) {
                val c = content.getJSONObject(k)
                if (c.optString("type") == "output_text") return c.optString("text")
            }
        }
        return null
    }

    private fun systemPrompt(lang: String) = """
        Eres Grok, un asistente que ve a través de las gafas inteligentes del usuario (primera persona)
        y le ayuda con la tarea que está haciendo en ese momento. Responde siempre en el idioma $lang.
        Recibes frames periódicos de la cámara de las gafas, el contexto acumulado y, a veces, lo que
        el usuario ha dicho.

        Reglas de intervención proactiva (cuando el usuario NO ha hablado):
        - Por defecto, NO hables (should_speak=false). El silencio es lo normal.
        - Habla solo si aportas algo útil y oportuno: un error o peligro (urgency=critical/high),
          el siguiente paso de la tarea cuando parece atascado (normal), o un dato relevante (low).
        - No describas la escena por describirla. No repitas lo que ya dijiste.
        - Mensajes muy cortos (máximo 2 frases), pensados para oírse por los altavoces de las gafas.
        Cuando el usuario SÍ ha hablado: responde siempre (should_speak=true), breve y concreto.

        Mantén actualizado current_task (qué intenta hacer el usuario) y scene_summary (una frase
        sobre lo que se ve ahora). expect_change_soon=true si crees que la situación cambiará en
        los próximos segundos (p. ej. está cocinando algo al fuego, cruzando una calle).
    """.trimIndent()

    companion object {
        val SCHEMA: JSONObject = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "should_speak": {"type": "boolean"},
                "urgency": {"type": "string", "enum": ["low", "normal", "high", "critical"]},
                "message": {"type": "string"},
                "current_task": {"type": "string"},
                "scene_summary": {"type": "string"},
                "expect_change_soon": {"type": "boolean"}
              },
              "required": ["should_speak", "urgency", "message", "current_task", "scene_summary", "expect_change_soon"],
              "additionalProperties": false
            }
            """.trimIndent()
        )
    }
}

/** OkHttp → corrutina cancelable (cancelar la corrutina cancela la petición HTTP). */
private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                val s = it.body?.string().orEmpty()
                if (it.isSuccessful) cont.resume(s)
                else cont.resumeWithException(IOException("xAI HTTP ${it.code}: ${s.take(300)}"))
            }
        }
    })
}
