package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.core.PermissionManager.isNormal
import plugin.snow.PluginVars
import kotlin.math.ceil
import kotlin.math.roundToInt

object VoteManager {
    private var globalVote: VoteSession? = null
    private val teamVotes = mutableMapOf<Int, VoteSession>()

    val globalVoteSession: VoteSession? get() = globalVote
    val globalVoteCreator: Player? get() = globalVote?.creator
    val globalVoteExcluded: Set<String> get() = globalVote?.excluded ?: emptySet()

    fun clearVote() {
        globalVote = null
        teamVotes.clear()
    }

    fun createVote(
        isTeamVote: Boolean,
        creator: Player,
        excludePlayers: List<Player>? = null,
        callback: (Boolean) -> Unit
    ) {
        if (!Vars.state.isGame || !isNormal(creator.uuid())) return
        if (Groups.player.count { isNormal(it.uuid()) } <= 1) {
            Call.announce(creator.con, "${PluginVars.ERROR}${I18nManager.get("vote.notenough", creator)}${PluginVars.RESET}")
            return
        }

        val excludeUuids = excludePlayers?.mapTo(HashSet()) { it.uuid() } ?: emptySet()
        val teamId = if (isTeamVote) creator.team().id else -1

        if (!isTeamVote && globalVote != null) {
            Call.announce(creator.con, "${PluginVars.WARN}${I18nManager.get("vote.globalbusy", creator)}${PluginVars.RESET}")
            return
        }
        if (isTeamVote && teamVotes[teamId] != null) {
            Call.announce(creator.con, "${PluginVars.WARN}${I18nManager.get("vote.teambusy", creator)}${PluginVars.RESET}")
            return
        }

        val voters = Groups.player.filter {
            it != creator && isNormal(it.uuid()) && it.uuid() !in excludeUuids && (!isTeamVote || it.team().id == teamId)
        }

        if (voters.isEmpty()) {
            Call.announce(creator.con, "${PluginVars.ERROR}${I18nManager.get("vote.novoters", creator)}${PluginVars.RESET}")
            return
        }

        val session = VoteSession(
            isTeamVote,
            teamId,
            voters.map { it.uuid() },
            mutableSetOf(),
            0,
            System.currentTimeMillis(),
            creator,
            callback,
            excludeUuids
        )
        if (isTeamVote) teamVotes[teamId] = session else globalVote = session
    }

    fun addVote(uuid: String) {
        val session = globalVote ?: return
        if (uuid in session.voted) return
        session.voted.add(uuid)
        session.ok++
    }

    fun endVotes() {
        globalVote?.let(::checkVote)
        teamVotes.values.toList().forEach(::checkVote)
    }

    private fun checkVote(session: VoteSession) {
        val total = session.voters.size
        val timeout = PluginVars.MENU_CONFIRM_TIMEOUT_SEC * 1000
        val elapsed = System.currentTimeMillis() - session.startTime
        val allVoted = session.voted.size >= total

        val required = if (total <= 2) total else ceil((total + 1) * (if (session.team) PluginVars.TEAM_RATIO else PluginVars.RATIO)).toInt()
        val passed = session.ok >= required || allVoted || elapsed > timeout
        if (!passed) return

        val percent = if (total == 0) 0 else (session.ok * 100.0 / total).roundToInt()

        if (session.ok >= required) {
            Groups.player.each {
                Call.announce(it.con, "${PluginVars.SUCCESS}${I18nManager.get("vote.success", it)}${PluginVars.RESET}")
            }
            session.callback(true)
        } else {
            Groups.player.each {
                Call.announce(
                    it.con,
                    "${PluginVars.WARN}${session.creator.name()}${I18nManager.get("vote.failed", it)} ${I18nManager.get("vote.action", it)} ($percent%)${PluginVars.RESET}"
                )
            }
            session.callback(false)
        }

        if (session.teamId == -1) globalVote = null else teamVotes.remove(session.teamId)
    }

    data class VoteSession(
        val team: Boolean,
        val teamId: Int,
        val voters: List<String>,
        val voted: MutableSet<String>,
        var ok: Int,
        val startTime: Long,
        val creator: Player,
        val callback: (Boolean) -> Unit,
        val excluded: Set<String>
    )
}