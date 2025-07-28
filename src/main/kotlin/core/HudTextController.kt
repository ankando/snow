package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.snow.PluginVars

object HudTextController {

enum class Mode(val displayName: String, val generator: (Player) -> String) {
        TIME("Time", fun(player): String {
            val totalSeconds = Vars.state.tick / 60
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "${PluginVars.INFO}%02d:%02d${PluginVars.RESET}".format(minutes.toInt(), seconds.toInt())
        }),

        COORDS("Location", fun(player): String {
            return "${PluginVars.INFO}(${player.tileX()}, ${player.tileY()})${PluginVars.RESET}"
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
