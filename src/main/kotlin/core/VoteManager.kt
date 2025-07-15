package plugin.core

import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.core.PermissionManager.isNormal
import plugin.snow.PluginVars
import kotlin.math.ceil
import kotlin.math.roundToInt

object VoteManager{

    private var globalVote: VoteSession? = null
    private val teamVotes = mutableMapOf<Team, VoteSession>()

    val globalVoteSession get() = globalVote
    val globalVoteCreator  get() = globalVote?.creator
    val globalVoteExcluded get() = globalVote?.excluded ?: emptySet()

    fun clearVote(){
        globalVote = null
        teamVotes.clear()
    }


    fun createGlobalVote(
        creator: Player,
        excludePlayers: List<Player>? = null,
        autoPassIfNoVoter: Boolean   = true,
        callback: (Boolean) -> Unit
    ){
        if(!Vars.state.isGame || !isNormal(creator.uuid()) || globalVote != null) return

        val exclude = excludePlayers?.mapTo(HashSet()){ it.uuid() } ?: emptySet()
        val voters  = buildVoterSet(creator, exclude)

        if(voters.isEmpty()){ callback(autoPassIfNoVoter); return }

        globalVote = VoteSession(voters, mutableSetOf(), System.currentTimeMillis(),
            creator, callback, exclude)
    }

    fun createTeamVote(
        creator: Player,
        autoPassIfNoVoter: Boolean = true,
        callback: (Boolean) -> Unit
    ){
        val team = creator.team()
        if(!Vars.state.isGame || !isNormal(creator.uuid()) || teamVotes.containsKey(team)) return

        val voters = buildVoterSet(creator, emptySet()).filterTo(mutableSetOf()){
            Groups.player.find{ p -> p.uuid() == it }?.team() == team
        }

        if(voters.isEmpty()){ callback(autoPassIfNoVoter); return }

        teamVotes[team] = VoteSession(voters, mutableSetOf(), System.currentTimeMillis(),
            creator, callback, emptySet())
    }

    fun addVote(uuid: String){ globalVote?.takeIf { uuid in it.voters }?.voted?.add(uuid) }
    fun addVote(uuid: String, team: Team){
        teamVotes[team]?.takeIf { uuid in it.voters }?.voted?.add(uuid)
    }


    fun endVotes(){
        globalVote?.also { if(checkVote(it, PluginVars.RATIO)) globalVote = null }
        teamVotes.entries.removeAll { checkVote(it.value, PluginVars.TEAM_RATIO) }
    }

    private fun buildVoterSet(creator: Player, exclude: Set<String>) =
        Groups.player.asSequence()
            .filter { isNormal(it.uuid()) && it.uuid() != creator.uuid() && it.uuid() !in exclude }
            .map   { it.uuid() }
            .toMutableSet()

    private fun checkVote(session: VoteSession, ratio: Double): Boolean {
        val total     = session.voters.size
        val required  = if (total <= 2) total else ceil(total * ratio).toInt()
        val elapsedMs = System.currentTimeMillis() - session.startTime
        val timeout   = elapsedMs > PluginVars.MENU_CONFIRM_TIMEOUT_SEC * 1000
        val yesVotes  = session.voted.size
        val allVoted  = yesVotes >= total

        val passed = yesVotes >= required
        if (!(passed || timeout || allVoted)) return false

        val percent = if (total == 0) 100 else (yesVotes * 100.0 / total).roundToInt()
        val info = "($percent%, $yesVotes/$required)"
        val color = if (passed) PluginVars.SUCCESS else PluginVars.WARN

        Groups.player.each {
            val msg = when {
                passed -> I18nManager.get("vote.success", it)
                else -> I18nManager.get("vote.failed", it)
            }
            Call.announce(it.con,
                "$color${session.creator.name()}${PluginVars.RESET} " +
                        "$msg $info${PluginVars.RESET}"
            )
        }

        session.callback(passed)
        return true
    }



    data class VoteSession(
        val voters: Set<String>,
        val voted: MutableSet<String>,
        val startTime: Long,
        val creator: Player,
        val callback: (Boolean) -> Unit,
        val excluded: Set<String>
    )
}
