package plugin.core

import arc.func.Cons
import com.deepl.api.DeepLClient
import com.deepl.api.TextResult

object Translator {
    private const val API_KEY = "c0093f4a-3d4a-4ba5-b230-830460627211:fx"
    private val supported = setOf("EN", "RU", "JA", "KO", "ZH")

    private val client = DeepLClient(API_KEY)

    private fun normalizeLang(input: String): String {
        val u = input.trim().uppercase()
        return when {
            u.startsWith("EN") -> "EN"
            u.startsWith("RU") -> "RU"
            u.startsWith("JA") -> "JA"
            u.startsWith("KO") -> "KO"
            u.startsWith("ZH") -> "ZH"
            else -> u
        }
    }

    fun translate(
        text: String,
        from: String?,
        to: String,
        onResult: Cons<String>,
        onError: Runnable
    ) {
        Thread {
            val tgt = normalizeLang(to)
            if (tgt !in supported) {
                onError.run()
                return@Thread
            }

            val src = from?.trim()?.let {
                val u = it.uppercase()
                if (u.isBlank() || u == "AUTO") null else normalizeLang(u)
            }

            try {
                val res: TextResult = client.translateText(text, src, tgt)
                onResult.get(res.text)
            } catch (e: Exception) {
                e.printStackTrace()
                onError.run()
            }
        }.start()
    }
}
