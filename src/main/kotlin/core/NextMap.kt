package plugin.core

import mindustry.maps.Map

object NextMap {
    private var next: Map? = null

    fun set(map: Map) {
        next = map
    }

    fun get(): Map? {
        return next
    }

    fun clear() {
        next = null
    }
}
