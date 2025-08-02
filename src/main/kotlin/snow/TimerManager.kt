package plugin.snow

import arc.graphics.Color
import arc.util.Timer
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import plugin.core.DataManager
import plugin.core.Emoji
import plugin.core.HudTextController
import plugin.core.UnitEffects
import plugin.core.VoteManager

object TimerManager {
    fun init() {
        Timer.schedule(::runPeriodicTasks, 0f, 2f)
    }

    private fun runPeriodicTasks() {
        if (!Vars.state.isGame) return
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
