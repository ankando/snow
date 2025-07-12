package plugin.snow

import arc.util.Timer
import mindustry.Vars
import mindustry.net.Administration
import plugin.core.DataManager
import plugin.core.VoteManager

object PluginTimer {
    private fun formatGameTime(): String {
        val minutes = (Vars.state.tick / 3600).toInt()
        return buildString {
            append(PluginVars.WARN)
            append("Game started $minutes minute")
            if (minutes != 1) append("s")
            append(" ago.")
            append(PluginVars.RESET)
        }
    }

    fun init() {
        Timer.schedule({
            if (!Vars.state.isGame) return@schedule

            VoteManager.endVotes()

            if (DataManager.needSave) {
                DataManager.saveAll()
            }

            Administration.Config.desc.set(formatGameTime())
        }, 5f, 5f)
    }
}
