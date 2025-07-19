package plugin.core

import arc.func.Cons
import arc.util.Http
import arc.util.serialization.JsonReader

object Translator {
    private val reader = JsonReader()
    private const val ENDPOINT = "http://127.0.0.1:1188/translate"

    fun translate(
        text: String,
        from: String,
        to: String,
        onResult: Cons<String>,
        onError: Runnable
    ) {
        val escaped = text.replace("\"", "\\\"")
        val body =
            """{"text":"$escaped","source_lang":"${from.uppercase()}","target_lang":"${to.uppercase()}"}"""

        Http.post(ENDPOINT)
            .header("Content-Type", "application/json")
            .content(body)
            .error { onError.run() }
            .submit { res ->
                runCatching { reader.parse(res.resultAsString).getString("data") }
                    .onSuccess { onResult.get(it) }
                    .onFailure { onError.run() }
            }
    }
}
