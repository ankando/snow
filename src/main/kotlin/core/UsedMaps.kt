package plugin.core

import mindustry.maps.Map

object UsedMaps {
    private val used = mutableSetOf<String>()

    fun add(map: Map) {
        used += map.file.name()
    }

    fun isUsed(map: Map): Boolean = map.file.name() in used

    fun clear() = used.clear()

    fun all(): Set<String> = used.toSet()
}
