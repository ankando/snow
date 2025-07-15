package plugin.core

import arc.func.Cons
import arc.util.Http
import arc.util.Strings
import arc.util.serialization.JsonReader

object Translator {
    private val reader = JsonReader()
    fun translate(
        text: String,
        from: String,
        to: String,
        onResult: Cons<String>,
        onError: Runnable
    ) {
        val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&dt=t"
        val query = "tl=$to&sl=$from&q=${Strings.encode(text)}"

        Http.post(url, query)
            .error { onError.run() }
            .submit { response ->
                val resultText = reader.parse(response.resultAsString).get(0).get(0).asString()
                onResult.get(resultText)
            }
    }
}