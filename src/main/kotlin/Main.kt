package plugin

import arc.util.CommandHandler
import arc.util.Strings
import mindustry.Vars
import mindustry.core.NetServer
import mindustry.game.Team
import mindustry.gen.AdminRequestCallPacket
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.mod.Plugin
import mindustry.net.Administration.ActionType
import mindustry.net.Packets
import plugin.core.*
import plugin.snow.ClientCommands
import plugin.snow.EventManager
import plugin.snow.NetEvents
import plugin.snow.PluginMenus.showTeamMenu
import plugin.snow.PluginTimer
import plugin.snow.PluginVars

class Main : Plugin() {

    private val invalidCommandHandler = NetServer.InvalidCommandHandler { player, res ->
        val key = when (res.type) {
            CommandHandler.ResponseType.manyArguments -> "cmd.too_many_args"
            CommandHandler.ResponseType.fewArguments -> "cmd.too_few_args"
            else -> null
        }

        key?.let {
            return@InvalidCommandHandler "${PluginVars.WARN}${I18nManager.get(it, player)} ${PluginVars.INFO}${res.command.text} ${res.command.paramText}${PluginVars.RESET}"
        }

        val hint = Vars.netServer.clientCommands.commandList
            .minByOrNull { Strings.levenshtein(it.text, res.runCommand) }
            ?.takeIf { Strings.levenshtein(it.text, res.runCommand) < 3 }

        return@InvalidCommandHandler if (hint != null)
            "${PluginVars.INFO}${I18nManager.get("cmd.hint", player)} /${hint.text}${PluginVars.RESET}"
        else
            "${PluginVars.WARN}${I18nManager.get("cmd.unknown", player)}${PluginVars.RESET}"
    }

    private fun assignTeam(player: Player?): Team {
        if (player == null) return Vars.state.rules.defaultTeam
        val uuid = player.uuid()
        if (!PermissionManager.isNormal(uuid)) return Team.derelict

        if (!Vars.state.rules.pvp) return Vars.state.rules.defaultTeam

        return PlayerTeamManager.getTeam(uuid)?.also { team ->
            if (!team.data().hasCore() && team != Team.derelict) {
                Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("team.lost", player)}${PluginVars.RESET}")
            }
        } ?: run {
            showTeamMenu(player)
            Team.derelict
        }
    }

    override fun init() {
        DataManager.init()
        EventManager.init()
        I18nManager.init()
        PluginTimer.init()
        WebUploader.init()

        Vars.netServer.assigner = NetServer.TeamAssigner { player, _ -> assignTeam(player) }
        Vars.netServer.invalidHandler = invalidCommandHandler
        Vars.netServer.admins.addChatFilter(NetEvents::chat)

        with(Vars.net) {
            handleServer(AdminRequestCallPacket::class.java, NetEvents::adminRequest)
            handleServer(Packets.Connect::class.java, NetEvents::connect)
            handleServer(Packets.ConnectPacket::class.java, NetEvents::connectPacket)
        }

        Vars.netServer.admins.addActionFilter { action ->
            val player = action?.player ?: return@addActionFilter false
            if (player.unit() == null || player.unit().dead()) return@addActionFilter false
            if (!PermissionManager.isNormal(player.uuid())) return@addActionFilter false
            if (!player.team().data().hasCore() || player.team() == Team.derelict) return@addActionFilter false
            if (PermissionManager.isBanned(player.uuid())) return@addActionFilter false

            if (action.type == ActionType.breakBlock) {
                action.tile?.let { RevertBuild.recordRemove(player, it) }
            }
            true
        }
    }

    override fun registerClientCommands(handler: CommandHandler) {
        listOf("a", "t", "vote", "votekick", "sync").forEach(handler::removeCommand)
        ClientCommands.register(handler)
    }
}