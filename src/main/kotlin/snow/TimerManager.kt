package plugin.snow

import arc.util.Timer
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Groups
import plugin.core.DataManager
import plugin.core.PermissionManager.isBanned
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
            if ((isBanned(player.uuid()) || player.team() == Team.derelict) && player.unit() != null) {
                player.clearUnit()
            }
        }
    }
}
