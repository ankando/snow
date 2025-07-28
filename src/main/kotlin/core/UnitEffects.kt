package plugin.core

import mindustry.content.Fx
import mindustry.entities.Effect
import mindustry.gen.Player
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

object UnitEffects {
    private val playerEffects = ConcurrentHashMap<String, Effect>()

    fun setEffect(player: Player, effect: Effect) {
        playerEffects[player.uuid()] = effect
    }

    fun getEffect(player: Player): Effect? = playerEffects[player.uuid()]

    fun clear(uuid: String) {
        playerEffects.remove(uuid)
    }

    fun allEffects(): List<Pair<String, Effect>> {
        return Fx::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Effect::class.java }
            .mapNotNull {
                try {
                    val effect = it.get(null) as? Effect ?: return@mapNotNull null
                    it.name to effect
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.first }
    }
}
