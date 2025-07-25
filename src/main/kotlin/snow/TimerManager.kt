package plugin.snow

import arc.util.Timer
import plugin.core.DataManager
import plugin.core.VoteManager

object TimerManager {

    fun init() {
        Timer.schedule(::runPeriodicTasks, 0f, 2f)
    }

    private fun runPeriodicTasks() {
        VoteManager.endVotes()
        if (DataManager.needSave) {
            DataManager.saveAll()
        }
    }
}
