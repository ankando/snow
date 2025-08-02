package plugin.snow

import arc.graphics.Color
import arc.util.Timer
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.net.Administration
import plugin.core.DataManager
import plugin.core.Emoji
import plugin.core.HudTextController
import plugin.core.UnitEffects
import plugin.core.VoteManager

object TimerManager {
    private fun formatGameTime(): String {
        val ticks = Vars.state.tick.toLong()
        val totalSeconds = ticks / 60
        val minutes = (totalSeconds / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()

        return buildString {
            append(PluginVars.WARN)
            append(String.format("%02d:%02d", minutes, seconds))
            append(PluginVars.RESET)
        }
    }

    fun init() {
        Timer.schedule(::runPeriodicTasks, 0f, 2f)
    }

    private fun runPeriodicTasks() {
        if (!Vars.state.isGame) return
        Administration.Config.desc.set(formatGameTime())
        VoteManager.endVotes()
        if (DataManager.needSave) {
            DataManager.saveAll()
        }
        Groups.player.each { player ->
            Emoji.removePrint(player.uuid(), 5)
            val effect = UnitEffects.getEffect(player)
            val unit = player.unit()
            if (effect != null && unit != null && !unit.dead) {
                Call.effect(effect, player.x, player.y, unit.rotation, Color.white)
            }
            HudTextController.updateAllHudText(player)
        }

    }
}
