package plugin.core

import mindustry.game.Gamemode

object TagUtil {

    fun getTags(desc: String): List<String> {
        val regex = Regex("""\[@([a-zA-Z0-9_-]+)(=[^]]+)?]""")
        return regex.findAll(desc.lowercase())
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    fun getMode(desc: String): Gamemode? {
        val modeTags = mapOf(
            "pvp" to Gamemode.pvp,
            "survival" to Gamemode.survival,
            "sandbox" to Gamemode.sandbox,
            "attack" to Gamemode.attack
        )

        var result: Gamemode? = null
        for (tag in getTags(desc)) {
            val mode = modeTags[tag] ?: continue
            if (result != null && result != mode) return null
            result = mode
        }
        return result
    }

    fun getTagValues(desc: String): Map<String, List<String>> {
        val regex = Regex("""\[@([a-zA-Z0-9_-]+)(=([^]]+))?]""")
        val result = mutableMapOf<String, List<String>>()

        regex.findAll(desc.lowercase()).forEach { match ->
            val key = match.groupValues[1]
            val raw = match.groupValues.getOrNull(3)
            val values = raw?.split(',')?.map { it.trim() } ?: emptyList()
            result[key] = values
        }

        return result
    }
}
