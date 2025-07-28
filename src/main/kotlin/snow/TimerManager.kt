package plugin.snow

import arc.graphics.Color
import arc.util.Timer
import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import plugin.core.DataManager
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
            val effect = UnitEffects.getEffect(player)
            if (effect != null) {
                Call.effect(effect, player.x, player.y, 0f, Color.white)
            }
            HudTextController.updateAllHudText(player)
        }

    }
}
