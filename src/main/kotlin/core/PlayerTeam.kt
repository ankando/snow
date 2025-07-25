package plugin.core

import mindustry.game.Team
import mindustry.gen.Player
import java.util.concurrent.ConcurrentHashMap

object PlayerTeamManager {
    private val playerTeams = ConcurrentHashMap<String, Team>()

    fun setTeam(player: Player, team: Team) {
        playerTeams[player.uuid()] = team
    }

    fun getTeam(uuid: String): Team? = playerTeams[uuid]

    fun clear() = playerTeams.clear()

    fun all(): Map<String, Team> = playerTeams
}
