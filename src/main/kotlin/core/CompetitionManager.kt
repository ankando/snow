package plugin.core

import arc.Events
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.maps.Map

object CompetitionManager {
    private var competitionState = 0
    private var matchIndex = 0

    private val teamPlayers = Array(2) { mutableListOf<String>() }
    private val teamScores = IntArray(2) { 0 }
    private val competitionMaps = mutableListOf<Map>()

    fun getCompetitionState(): Int = competitionState

    fun setCompetitionState(state: Int) {
        competitionState = state
    }

    fun getCurrentMatchIndex(): Int = matchIndex

    fun initializeCompetitionMode() {
        val candidateMaps = competitionMaps.filter { it != Vars.state.map }
        val next = if (candidateMaps.isEmpty()) null else candidateMaps.random()

        next?.let {
            Vars.maps.setNextMapOverride(it)
            competitionState = 1
            matchIndex = 0
            resetTeams()
            Events.fire(EventType.GameOverEvent(Team.derelict))
        }
    }

    fun markTeamSelectionStarted() {
        competitionState = 1
    }

    fun markCompetitionStarted() {
        competitionState = 2
        matchIndex = 1
    }

    fun increaseMatchIndex() {
        if (competitionState != 2) return
        matchIndex++
        if (matchIndex > 3) {
            resetCompetition()
        }
    }

    fun resetCompetition() {
        competitionState = 0
        matchIndex = 0
        resetTeams()
        competitionMaps.clear()
    }

    private fun resetTeams() {
        teamPlayers.forEach { it.clear() }
        teamScores.fill(0)
    }

    fun assignPlayerToTeam(teamId: Int, uuid: String) {
        if (teamId in 0..1) teamPlayers[teamId].add(uuid)
    }

    fun getPlayersOfTeam(teamId: Int): List<String> {
        return if (teamId in 0..1) teamPlayers[teamId] else emptyList()
    }

    fun addWin(teamId: Int) {
        if (teamId in 0..1) teamScores[teamId]++
    }

    fun getScore(teamId: Int): Int {
        return if (teamId in 0..1) teamScores[teamId] else 0
    }

    fun getAllScores(): Pair<Int, Int> {
        return teamScores[0] to teamScores[1]
    }

    fun addMap(map: Map) {
        if (map !in competitionMaps) {
            competitionMaps.add(map)
        }
    }

    fun removeMap(map: Map) {
        competitionMaps.remove(map)
    }

    fun getAllMaps(): List<Map> {
        return competitionMaps.toList()
    }
}
