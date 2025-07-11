package plugin.snow

import arc.util.Timer
import mindustry.Vars
import mindustry.net.Administration
import plugin.core.DataManager
import plugin.core.VoteManager.endVotes

object PluginTimer {
    private fun formatGameTime(): String {
        val ticks = Vars.state.tick.toLong()
        val totalSeconds = ticks / 60
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            append(PluginVars.WARN)
            if (hours > 0) append(String.format("%02d:", hours))
            append(String.format("%02d:%02d", minutes, seconds))
            append(PluginVars.RESET)
        }
    }

    fun init() {
        Timer.schedule({
            if (!Vars.state.isGame) return@schedule
            endVotes()
            if (DataManager.needSave) {
                DataManager.saveAll()
            }
            Administration.Config.desc.set(formatGameTime())
        }, 5f, 5f)
    }
}
