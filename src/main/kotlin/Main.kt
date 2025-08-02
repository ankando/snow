package plugin

import arc.util.CommandHandler
import arc.util.Strings
import mindustry.Vars
import mindustry.core.NetServer
import mindustry.game.Team
import mindustry.gen.AdminRequestCallPacket
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.mod.Plugin
import mindustry.net.Administration
import mindustry.net.Packets
import plugin.core.*
import plugin.snow.*

class Main : Plugin() {

    override fun init() {
        DataManager.init()
        EventManager.init()
        I18nManager.init()
        WebUploader.init()
        TimerManager.init()
        Vars.netServer.assigner = NetServer.TeamAssigner { player, _ -> assignTeam(player) }
        Vars.netServer.invalidHandler = invalidCommandHandler
        Vars.netServer.admins.addChatFilter(NetEvents::chat)
        with(Vars.net) {
            handleServer(AdminRequestCallPacket::class.java, NetEvents::adminRequest)
            handleServer(Packets.Connect::class.java, NetEvents::connect)
            handleServer(Packets.ConnectPacket::class.java, NetEvents::connectPacket)
        }
        Vars.netServer.admins.addActionFilter(::actionFilter)
    }

    private fun actionFilter(action: Administration.PlayerAction?): Boolean {
        action?.player ?: return false
        val tile = action.tile ?: return true

        val map = Vars.state?.map ?: return true
        val desc = map.description()

        val tagMap = TagUtil.getTagValues(desc)
        val prohibitedFloors = tagMap["prohibitfloor"] ?: return true

        val floorName = tile.floor().name.lowercase()
        return !prohibitedFloors.any { floorName.equals(it, ignoreCase = true) }
    }

    override fun registerClientCommands(handler: CommandHandler) {
        listOf("a", "t", "vote", "votekick", "sync").forEach(handler::removeCommand)
        ClientCommands.register(handler)
    }

    private fun assignTeam(player: Player?): Team {
        val rules = Vars.state.rules
        val desc = Vars.state.map.description()
        val tagMap = TagUtil.getTagValues(desc)
        val teamSizeLimit = tagMap["teamsize"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE

        fun chooseRandomTeam(): Team {
            val activeTeams = Vars.state.teams.active.map { it.team }.toSet()
            val candidates = Team.all.filter { it != Team.derelict && it !in activeTeams && !it.data().hasCore() }
            return candidates.randomOrNull() ?: Team.derelict
        }

        val defaultTeamPlayerCount = Groups.player.count { it.team() == rules.defaultTeam }

        return when {
            player == null -> rules.defaultTeam

            PermissionManager.isBanned(player.uuid()) -> chooseRandomTeam()

            !rules.pvp -> {
                if (defaultTeamPlayerCount >= teamSizeLimit) {
                    chooseRandomTeam()
                } else {
                    PlayerTeam.setTeam(player, rules.defaultTeam)
                    rules.defaultTeam
                }
            }

            else -> {
                val existing = PlayerTeam.getTeam(player.uuid())
                if (existing != null) {
                    existing
                } else {
                    val team = chooseRandomTeam()
                    PlayerTeam.markAutoAssigned(player.uuid())
                    team
                }
            }
        }
    }

    private val invalidCommandHandler = NetServer.InvalidCommandHandler { player, res ->
        val key = when (res.type) {
            CommandHandler.ResponseType.manyArguments -> "cmd.too_many_args"
            CommandHandler.ResponseType.fewArguments -> "cmd.too_few_args"
            else -> null
        }
        key?.let {
            return@InvalidCommandHandler "${PluginVars.WARN} $it ${PluginVars.INFO}${res.command.text} ${res.command.paramText}${PluginVars.RESET}"
        }
        val hint = Vars.netServer.clientCommands.commandList
            .minByOrNull { Strings.levenshtein(it.text, res.runCommand) }
            ?.takeIf { Strings.levenshtein(it.text, res.runCommand) < 3 }
        if (hint != null)
            "${PluginVars.INFO}${I18nManager.get("cmd.hint", player)} /${hint.text}${PluginVars.RESET}"
        else
            "${PluginVars.WARN}${I18nManager.get("cmd.unknown", player)}${PluginVars.RESET}"
    }

}
