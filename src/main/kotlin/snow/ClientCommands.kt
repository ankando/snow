package plugin.snow

import arc.util.CommandHandler
import arc.util.Time
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.core.DataManager
import plugin.core.I18nManager
import plugin.core.PermissionLevel
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.PermissionManager.verifyPermissionLevel
import plugin.core.Translator.translate
import plugin.core.VoteManager
import plugin.snow.PluginMenus.beginVotekick
import plugin.snow.PluginMenus.showVoteKickPlayerMenu

object ClientCommands {
    fun register(handler: CommandHandler) {
        fun parsePageArg(args: Array<String>): Int {
            if (args.isNotEmpty()) {
                val n = args[0].toIntOrNull()
                if (n != null && n >= 1) return n
            }
            return 1
        }

        fun register(
            name: String,
            args: String,
            required: PermissionLevel = PermissionLevel.NORMAL,
            exec: (Array<String>, Player) -> Unit
        ) {
            handler.register<Player>(
                name,
                args,
                I18nManager.get("cmd.$name.desc", null)
            ) { args, player ->
                verifyPermissionLevel(player, required) {
                    exec(args, player)
                }
            }
        }

        register("help", "[page]") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showHelpMenu(player, page)
        }
        register("maps", "[page]") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showMapMenu(player, page)
        }
        register("rank", "[page]") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showRankMenu(player, page)
        }
        register("players", "") { args, player ->
            PluginMenus.showPlayersMenu(player, 1)
        }
        register("join", "") { _, player ->
            if (player.team() != Team.derelict) {
                Call.announce(player.con, I18nManager.get("join.joined", player)); return@register
            }
            PluginMenus.showTeamMenu(player)
        }
        register("rules", "", PermissionLevel.MEMBER) { _, player ->
            PluginMenus.showRulesMenu(player)
        }
        register("INFO", "") { _, player ->
            PluginMenus.showMapInfoMenu(player, Vars.state.map)
        }
        register("upload", "", PermissionLevel.MEMBER) { _, player ->
            PluginMenus.showUploadMapMenu(player)
        }
        register("revert", "", PermissionLevel.CORE_ADMIN) { _, player ->
            PluginMenus.showRevertMenu(player)
        }
        register("profile", "") { _, player ->
            PluginMenus.showSetProfileMenu(player)
        }
        register("sync", "") { _, player ->
            if (!player.isLocal) {
                if (Time.timeSinceMillis(player.info.lastSyncTime) < 1000 * 5) {
                    return@register
                }
                player.info.lastSyncTime = Time.millis()
                Call.worldDataBegin(player.con)
                netServer.sendWorldData(player)
            }
        }
        register("votekick", "[player] [reason]", PermissionLevel.MEMBER) { args, player ->
            if (Groups.player.size() < 3) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.too_few", player)}${PluginVars.RESET}"
                )
                return@register
            }
            if (args.isEmpty()) {
                showVoteKickPlayerMenu(player)
                return@register
            }
            val targetName = args[0]
            val target =
                Groups.player.find { it.name().equals(targetName, true) || it.name().contains(targetName, true) }
            if (target == null || target == player) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.notfound", player)}${PluginVars.RESET}"
                )
                return@register
            }
            if (target == player) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.noself", player)}${PluginVars.RESET}"
                )
                return@register
            }
            if (isCoreAdmin(target.uuid())) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.noadmin", player)}${PluginVars.RESET}"
                )
                return@register
            }
            if (args.size > 1) args.copyOfRange(1, args.size).joinToString(" ") else ""
            beginVotekick(player, target)
        }

        register("t", "<...>") { args, player ->
            if (args.isEmpty()) return@register

            val message = args.joinToString(" ")
            val playerName = player.name() ?: I18nManager.get("unknown", player)
            val prefix = "${PluginVars.INFO}${I18nManager.get("team.tag", player)}${PluginVars.RESET} " +
                    "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
            val formatted = "$prefix$message${PluginVars.RESET}"

            player.sendMessage(formatted)

            Groups.player.each { receiver ->
                if (receiver === player) return@each
                if (receiver.team() != player.team()) return@each

                val acc = DataManager.getPlayerDataByUuid(receiver.uuid())
                val lang = acc?.lang ?: receiver.locale()
                val needsTranslation = !lang.isNullOrEmpty() && lang != "off"

                if (needsTranslation) {
                    translate(message, "auto", lang, { translated ->
                        val msg = if (translated != message)
                            "$prefix$message ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}"
                        else
                            formatted
                        receiver.sendMessage(msg)
                    }, {
                        receiver.sendMessage(formatted)
                    })
                } else {
                    receiver.sendMessage(formatted)
                }
            }
        }

        register("logout", "[all]") { args, player ->
            val uuid = player.uuid()
            val acc = DataManager.getPlayerDataByUuid(uuid)

            if (acc == null) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("logout.not_logged_in", player)}${PluginVars.RESET}"
                )
                return@register
            }

            if (acc.isLock) {
                Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("isLock", player)}${PluginVars.RESET}")
                return@register
            }
            if (args.isNotEmpty() && args[0].equals("all", ignoreCase = true)) {
                acc.uuids.clear()
                player.clearUnit()
                DataManager.requestSave()
                Call.announce(
                    player.con,
                    "${PluginVars.INFO}${I18nManager.get("logout.all", player)}${PluginVars.RESET}"
                )
            } else {
                acc.uuids.remove(uuid)
                player.clearUnit()
                DataManager.requestSave()
                Call.announce(
                    player.con,
                    "${PluginVars.INFO}${I18nManager.get("logout.success", player)}${PluginVars.RESET}"
                )
            }
        }
        register("surrender", "", PermissionLevel.MEMBER) { _, player ->

            val team = player.team()
            val teamPlayerCount = Groups.player.count { it.team() == team && !it.dead() }
            if (team == Team.derelict || !team.data().hasCore() || teamPlayerCount < 3) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("surrender.no_team", player)}${PluginVars.RESET}"
                )
                return@register
            }
            VoteManager.createVote(
                team = true,
                p = player,
                title = "${PluginVars.WARN}${I18nManager.get("surrender.vote.title", player)}${PluginVars.RESET}",
                desc = "${PluginVars.GRAY}${I18nManager.get("surrender.vote.desc", player)}${PluginVars.RESET}",
            ) { ok ->
                if (ok) {
                    team.cores().forEach { core ->
                        val x = core.x
                        val y = core.y
                        mindustry.entities.Damage.damage(
                            Team.derelict,
                            x, y,
                            1.2f,
                            9999999f,
                            false,
                            false
                        )
                    }
                    Groups.build.each { build ->
                        if (build.team == team) {
                            build.kill()
                        }
                    }
                    Groups.player.each { p ->
                        if (p.team() == team) {
                            Call.announce(
                                p.con,
                                "${PluginVars.INFO}${I18nManager.get("surrender.success", p)}${PluginVars.RESET}"
                            )
                        }
                    }
                } else {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("surrender.fail", player)}${PluginVars.RESET}"
                    )
                }
            }
        }
    }
}
