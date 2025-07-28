package plugin.core

import mindustry.game.Team
import mindustry.gen.Player
import java.util.concurrent.ConcurrentHashMap

object PlayerTeam {
    private val playerTeams = ConcurrentHashMap<String, Team>()
    private val autoAssignedPlayers = mutableSetOf<String>()

    fun setTeam(player: Player, team: Team) {
        playerTeams[player.uuid()] = team
        autoAssignedPlayers.remove(player.uuid())
    }

    fun getTeam(uuid: String): Team? = playerTeams[uuid]

    fun wasAutoAssigned(uuid: String): Boolean = uuid in autoAssignedPlayers

    fun markAutoAssigned(uuid: String) {
        autoAssignedPlayers += uuid
    }

    fun clear() {
        playerTeams.clear()
        autoAssignedPlayers.clear()
    }

    fun all(): Map<String, Team> = playerTeams
}
