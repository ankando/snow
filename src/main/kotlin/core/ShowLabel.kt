package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import plugin.snow.PluginVars

object ShowLabel {

    fun showMapLabel(player: Player) {
        val build = Groups.build.find { it is CoreBuild && it.team == player.team() }
        val core = build as? CoreBuild

        val mapName = Vars.state.map.name()
        val rawAuthor = Vars.state.map.author()
        val mapDesc = Vars.state.map.description()
        val mapAuthor = if (rawAuthor.isNullOrBlank()) "" else "\n\n${PluginVars.GRAY}$rawAuthor${PluginVars.RESET}"
        var text = "${PluginVars.INFO}$mapName${PluginVars.RESET} $mapAuthor"
        if (!mapDesc.isNullOrBlank()) {
            text += "\n\n${PluginVars.SECONDARY}${wrapText(mapDesc.trim())}${PluginVars.RESET}"
        }

        val x = core?.x ?: (Vars.world.unitWidth() / 2f)
        val y = core?.y ?: (Vars.world.unitHeight() / 2f)

        Call.label(player.con, text, 30f, x, y)
    }

    private fun wrapText(text: String, lineLength: Int = 40): String {
        val words = text.split(" ")
        val wrapped = StringBuilder()
        var line = StringBuilder()

        for (word in words) {
            if (line.length + word.length + 1 > lineLength) {
                wrapped.append(line.toString().trim()).append("\n")
                line = StringBuilder()
            }
            line.append(word).append(" ")
        }

        if (line.isNotEmpty()) {
            wrapped.append(line.toString().trim())
        }

        return wrapped.toString()
    }
}
