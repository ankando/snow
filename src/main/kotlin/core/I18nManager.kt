package plugin.core

import mindustry.gen.Player
import plugin.core.DataManager.getPlayerDataByUuid
import plugin.snow.PluginVars

object I18nManager {
    private val langs = mutableMapOf<String, Map<String, String>>()
    private var default: Map<String, String> = emptyMap()
    private val supportedLangs = listOf("en", "ru", "ja", "zh_CN")

    fun init() {
        supportedLangs.forEach { lang ->
            if (lang == "en") loadBundle("bundle.properties", "en")
            else loadBundle("bundle_${lang}.properties", lang)
        }
    }

    private fun loadBundle(filename: String, lang: String) {
        val stream = javaClass.classLoader.getResourceAsStream(filename)
        if (stream == null) {
            if (lang == "en") default = emptyMap()
            langs[lang] = emptyMap()
            return
        }
        val lines = stream.bufferedReader(Charsets.UTF_8).readLines()
        val map = lines.mapNotNull {
            val idx = it.indexOf('=')
            if (idx != -1) it.substring(0, idx).trim() to it.substring(idx + 1).trim() else null
        }.toMap()
        if (lang == "en") default = map
        langs[lang] = map
    }

    fun get(key: String, player: Player?): String {
        val lang = player?.let { getPlayerDataByUuid(it.uuid())?.lang } ?: "en"
        val code = lang.replace('-', '_')
        val finalCode = if (supportedLangs.contains(code)) code else "en"

        val value = langs[finalCode]?.get(key)
        if (!value.isNullOrEmpty()) return value
        val def = default[key]
        if (!def.isNullOrEmpty()) return def
        return PluginVars.WARN + key + PluginVars.RESET
    }
}
