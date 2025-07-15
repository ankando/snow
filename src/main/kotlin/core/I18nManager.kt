package plugin.core

import mindustry.gen.Player
import plugin.core.DataManager.getPlayerDataByUuid

object I18nManager {
    private val languages = mutableMapOf<String, Map<String, String>>()
    private var default: Map<String, String> = emptyMap()
    private val supportedLanguages = setOf("en", "ru", "ja", "ko", "zh")

    fun init() {
        supportedLanguages.forEach { lang ->
            val fileName = if (lang == "en") "bundle.properties" else "bundle_${lang}.properties"
            languages[lang] = loadBundle(fileName).also {
                if (lang == "en") default = it
            }
        }
    }

    private fun loadBundle(filename: String): Map<String, String> {
        val stream = javaClass.classLoader.getResourceAsStream(filename) ?: return emptyMap()
        return stream.bufferedReader(Charsets.UTF_8)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
            .map {
                val (key, value) = it.split('=', limit = 2)
                key.trim() to value.trim()
            }
            .toMap()
    }

    fun get(key: String, player: Player?, resolveEscape: Boolean = true): String {
        val langCode = resolveLangCode(player)

        val primary = languages[langCode]?.get(key)
        val fallback = default[key]

        val value = when {
            !primary.isNullOrBlank() -> primary
            !fallback.isNullOrBlank() -> fallback
            else -> key
        }

        return if (resolveEscape) value.replace("\\n", "\n") else value
    }


    private fun resolveLangCode(player: Player?): String {
        val rawLang = getPlayerDataByUuid(player?.uuid() ?: "")?.lang
            ?: player?.locale()
            ?: "en"

        val code = rawLang.replace('-', '_').split('_')[0]
        return if (code in supportedLanguages) code else "en"
    }
}
