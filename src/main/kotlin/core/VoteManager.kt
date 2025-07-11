package plugin.core

import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.snow.PluginVars
import kotlin.math.ceil
import kotlin.math.roundToInt

object VoteManager {
    private var globalVote: VoteSession? = null
    private val teamVotes = mutableMapOf<Int, VoteSession>()

    fun getGlobalVoteSession(): VoteSession? = globalVote

    fun getGlobalVoteCreator(): Player? = globalVote?.creator
    fun getGlobalVoteExcluded(): Set<String> = globalVote?.excluded ?: emptySet()
    fun clearVote() {
        globalVote = null
        teamVotes.clear()
    }

    fun createVote(
        team: Boolean,
        p: Player,
        title: String,
        desc: String,
        excludePlayers: List<Player>? = null,
        callback: (Boolean) -> Unit
    ) {
        if (Groups.player.count { PermissionManager.isNormal(it.uuid()) } <= 1) {
            Call.announce(
                p.con,
                "${PluginVars.ERROR}${I18nManager.get("vote.notenough", p)}${PluginVars.RESET}"
            )
            return
        }
        val excludeUuids = excludePlayers?.map { it.uuid() }?.toSet() ?: emptySet()
        val teamId = if (team) p.team().id else -1
        if (!team && globalVote != null) {
            Call.announce(
                p.con,
                "${PluginVars.WARN}${I18nManager.get("vote.globalbusy", p)}${PluginVars.RESET}"
            )
            return
        }
        if (team && teamVotes[teamId] != null) {
            Call.announce(
                p.con,
                "${PluginVars.WARN}${I18nManager.get("vote.teambusy", p)}${PluginVars.RESET}"
            )
            return
        }

        val voters = if (team) {
            Groups.player.filter {
                it.team().id == teamId && it != p &&
                        it.uuid() !in excludeUuids &&
                        PermissionManager.isNormal(it.uuid())
            }
        } else {
            Groups.player.filter {
                it != p &&
                        it.uuid() !in excludeUuids &&
                        PermissionManager.isNormal(it.uuid())
            }
        }

        if (voters.isEmpty()) {
            Call.announce(
                p.con,
                "${PluginVars.ERROR}${I18nManager.get("vote.novoters", p)}${PluginVars.RESET}"
            )
            return
        }

        val session = VoteSession(
            team,
            teamId,
            voters.map { it.uuid() },
            mutableSetOf(),
            0,
            System.currentTimeMillis(),
            p,
            callback,
            excludeUuids
        )
        if (team) teamVotes[teamId] = session else globalVote = session

        val voteMenu = MenusManage.createConfirmMenu(
            title = title,
            desc = desc,
            onResult = { player, choice ->
                if (choice == 0) addVote(player.uuid())
            },
            yesText = PluginVars.SUCCESS + I18nManager.get("vote.ok", null) + PluginVars.RESET,
            noText = PluginVars.WARN + I18nManager.get("vote.no", null) + PluginVars.RESET
        )
        voters.forEach { voteMenu(it) }
    }

    fun addVote(uuid: String) {
        val session = globalVote ?: return
        if (uuid in session.voted) return
        session.voted.add(uuid)
        session.ok++
    }

    fun endVotes() {
        globalVote?.let { checkVote(it) }
        teamVotes.values.toList().forEach { checkVote(it) }
    }

    private fun checkVote(session: VoteSession) {
        val total = session.voters.size
        val okCount = session.ok
        val timeout = PluginVars.MENU_CONFIRM_TIMEOUT_SEC * 1000
        val elapsed = System.currentTimeMillis() - session.startTime
        val allVoted = session.voted.size >= total

        val passRatio = if (session.team) PluginVars.TEAM_RATIO else PluginVars.RATIO
        val passCount =
            if (session.voters.size <= 2) session.voters.size else ceil(passRatio * (session.voters.size + 1)).toInt()

        val done = okCount >= passCount || allVoted || elapsed > timeout
        if (!done) return

        val percent = if (total == 0) 0 else (okCount * 100.0 / total).roundToInt()
        val creator = session.creator

        if (okCount >= passCount) {
            Groups.player.each { p ->
                Call.announce(
                    p.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("vote.SUCCESS", p)}${PluginVars.RESET}"
                )
            }
            session.callback(true)
        } else {
            Groups.player.each { p ->
                Call.announce(
                    p.con,
                    "${PluginVars.WARN}${creator.name()}${
                        I18nManager.get(
                            "vote.failed",
                            p
                        )
                    } ${I18nManager.get("vote.action", p)} ($percent%)${PluginVars.RESET}"
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
