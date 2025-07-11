package plugin

import arc.util.CommandHandler
import arc.util.Strings
import mindustry.Vars
import mindustry.ai.BlockIndexer
import mindustry.content.Blocks
import mindustry.core.NetServer
import mindustry.game.Team
import mindustry.gen.AdminRequestCallPacket
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.mod.Plugin
import mindustry.net.Administration.ActionType
import mindustry.net.Packets
import mindustry.type.Item
import mindustry.world.Tile
import plugin.core.*
import plugin.snow.ClientCommands
import plugin.snow.EventManager
import plugin.snow.PluginMenus.showTeamMenu
import plugin.snow.PluginTimer
import plugin.snow.PluginVars
import kotlin.math.abs

class MyBlockIndexer : BlockIndexer() {
    override fun findClosestOre(xp: Float, yp: Float, item: Item): Tile? {
        val cx = (xp / Vars.tilesize).toInt()
        val cy = (yp / Vars.tilesize).toInt()
        val maxR = maxOf(Vars.world.width(), Vars.world.height())

        for (r in 0..maxR) {
            for (dx in -r..r) {
                for (dy in -r..r) {
                    if (abs(dx) != r && abs(dy) != r) continue
                    val x = cx + dx
                    val y = cy + dy
                    val tile = Vars.world.tile(x, y) ?: continue
                    if (tile.block() === Blocks.air && tile.drop() === item) {
                        return tile
                    }
                }
            }
        }
        return null
    }

}

class Main : Plugin() {
    private val calmInvalidHandler = NetServer.InvalidCommandHandler { player, res ->
        when (res.type) {
            CommandHandler.ResponseType.manyArguments ->
                "${PluginVars.WARN}${
                    I18nManager.get(
                        "cmd.too_many_args",
                        player
                    )
                } ${PluginVars.INFO}${res.command.text} ${res.command.paramText}${PluginVars.RESET}"

            CommandHandler.ResponseType.fewArguments ->
                "${PluginVars.WARN}${
                    I18nManager.get(
                        "cmd.too_few_args",
                        player
                    )
                } ${PluginVars.INFO}${res.command.text} ${res.command.paramText}${PluginVars.RESET}"

            else -> {
                var best = 3
                var hint: CommandHandler.Command? = null
                for (cmd in Vars.netServer.clientCommands.commandList) {
                    val d = Strings.levenshtein(cmd.text, res.runCommand)
                    if (d < best) {
                        best = d; hint = cmd
                    }
                }
                if (hint != null)
                    "${PluginVars.INFO}${I18nManager.get("cmd.hint", player)} /${hint.text}${PluginVars.RESET}"
                else
                    "${PluginVars.WARN}${I18nManager.get("cmd.unknown", player)}${PluginVars.RESET}"
            }
        }
    }

    private val assignTeam = NetServer.TeamAssigner { player: Player?, players: Iterable<Player>? ->
        if (player == null) return@TeamAssigner Vars.state.rules.defaultTeam
        val uuid = player.uuid()
        if (!PermissionManager.isNormal(uuid)) return@TeamAssigner Team.derelict

        if (Vars.state.rules.pvp) {
            val team = PlayerTeamManager.getTeam(uuid)
            if (team != null) {
                if (!team.data().hasCore() && team != Team.derelict) {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("team.lost", player)}${PluginVars.RESET}"
                    )
                }
                return@TeamAssigner team
            } else {
                showTeamMenu(player)
                return@TeamAssigner Team.derelict
            }
        }
        Vars.state.rules.defaultTeam
    }

    override fun init() {
        DataManager.init()
        EventManager.init()
        I18nManager.init()
        PluginTimer.init()
        WebUploader.init()
        Vars.indexer = MyBlockIndexer()
        Vars.netServer.assigner = assignTeam
        Vars.netServer.invalidHandler = calmInvalidHandler
        Vars.netServer.admins.addChatFilter(NetEvents::chat)
        Vars.net.handleServer(AdminRequestCallPacket::class.java, NetEvents::adminRequest)
        Vars.net.handleServer(Packets.Connect::class.java, NetEvents::connect)
        Vars.net.handleServer(Packets.ConnectPacket::class.java, NetEvents::connectPacket)
        Vars.netServer.admins.addActionFilter { action ->
            val player = action?.player ?: return@addActionFilter false
            if (player.unit() == null || player.unit().dead()) return@addActionFilter false
            if (!PermissionManager.isNormal(player.uuid())) return@addActionFilter false
            if (!player.team().data().hasCore() || player.team() == Team.derelict) return@addActionFilter false
            if (PermissionManager.isBanned(player.uuid())) return@addActionFilter false

            when (action.type) {
                ActionType.breakBlock -> {
                    action.tile?.let { RevertBuild.recordRemove(player, it) }
                }

                else -> {}
            }
            true
        }
    }

    override fun registerClientCommands(handler: CommandHandler) {
        handler.removeCommand("a")
        handler.removeCommand("t")
        handler.removeCommand("vote")
        handler.removeCommand("votekick")
        handler.removeCommand("sync")
        ClientCommands.register(handler)
    }

}
