package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player

object HudTextController {

enum class Mode(val displayName: String, val generator: (Player) -> String) {
        TIME("Time", fun(player): String {
            val minutes = (Vars.state.tick / 3600f).toInt()
            val seconds = (Vars.state.tick / 60f).toInt()
            return "%02d:%02d".format(minutes, seconds)
        }),

        COORDS("Location", fun(player): String {
            return "(${player.tileX()}, ${player.tileY()})"
        });
    }

    private val activeModes = mutableMapOf<String, Mode>()

    fun setMode(player: Player, mode: Mode?) {
        val uuid = player.uuid()
        if (mode == null) {
            activeModes.remove(uuid)
            Call.hideHudText(player.con)
        } else {
            activeModes[uuid] = mode
        }
    }

    fun getMode(player: Player): Mode? = activeModes[player.uuid()]

    fun updateAllHudText() {
        Groups.player.each { player ->
            val mode = getMode(player) ?: return@each
            val text = mode.generator(player)
            Call.setHudText(player.con, text)
        }
    }

    fun availableModes(): List<Mode> = Mode.entries.toList()
}
