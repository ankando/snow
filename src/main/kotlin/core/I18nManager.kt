package plugin.core

import mindustry.gen.Player
import plugin.core.DataManager.getPlayerDataByUuid

object I18nManager {
    private val langs = mutableMapOf<String, Map<String, String>>()
    private var default: Map<String, String> = emptyMap()
    private val supportedLangs = setOf("en", "ru", "ja", "ko", "zh_CN")

    fun init() {
        supportedLangs.forEach { lang ->
            val fileName = if (lang == "en") "bundle.properties" else "bundle_${lang}.properties"
            langs[lang] = loadBundle(fileName).also {
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
        val value = langs[langCode]?.get(key)
            ?: default[key]
            ?: key

        return if (resolveEscape) value.replace("\\n", "\n") else value
    }

    private fun resolveLangCode(player: Player?): String {
        val rawLang = getPlayerDataByUuid(player?.uuid() ?: "")?.lang
            ?: player?.locale()
            ?: "en"

        val code = rawLang.replace('-', '_')
        return if (code in supportedLangs) code else "en"
    }
}
