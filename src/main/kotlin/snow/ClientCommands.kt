package plugin.snow

import arc.util.CommandHandler
import arc.util.Strings
import arc.util.Time
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.core.*
import plugin.core.PermissionManager.isBanned
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.RevertBuild.restorePlayerEditsWithinSeconds
import plugin.core.Translator.translate
import plugin.snow.PluginMenus.beginVotekick
import plugin.snow.PluginMenus.showConfirmMenu
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
            desc: String,
            exec: (Array<String>, Player) -> Unit
        ) {
            handler.register<Player>(
                name,
                args,
                desc
            ) { args, player ->
                if ( isBanned(player.uuid())) return@register
                exec(args, player)
            }
        }
        register("help", "[page]", "helpCmd.help") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showHelpMenu(player, page)
        }
        register("maps", "[page]", "helpCmd.maps") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showMapMenu(player, page)
        }
        register("rank", "[page]", "helpCmd.rank") { args, player ->
            parsePageArg(args)
            PluginMenus.showRankMenu(player)
        }
        register("players", "", "helpCmd.players") { args, player ->
            PluginMenus.showPlayersMenu(player, 1)
        }
        register("games", "", "helpCmd.games") { args, player ->
            PluginMenus.showGamesMenu(player)
        }
        register("join", "", "helpCmd.join") { _, player ->
            if (!Vars.state.rules.pvp || !PlayerTeam.wasAutoAssigned(player.uuid())) {
                Call.announce(player.con, I18nManager.get("join.joined", player))
                return@register
            }

            PluginMenus.showTeamMenu(player)
        }
        register("rules", "", "helpCmd.rules") { _, player ->
            PluginMenus.showRulesMenu(player)
        }
        register("ban", "[id] [seconds]", "Ban a player by id") { args, viewer ->
            if (!isCoreAdmin(viewer.uuid())) {
                return@register
            }
            if (args.size != 2) {
                Call.announce(viewer.con, "${PluginVars.WARN}[id [seconds]${PluginVars.RESET}")
                return@register
            }
            val id = args[0].toIntOrNull()
            val seconds = args[1].toLongOrNull()

            if (id == null || seconds == null || seconds <= 0) {
                Call.announce(viewer.con, "${PluginVars.WARN}Invalid arguments.${PluginVars.RESET}")
                return@register
            }

            val playerData = DataManager.players[id]
            if (playerData == null) {
                Call.announce(viewer.con, "${PluginVars.WARN}${I18nManager.get("login.notFound", viewer)}${PluginVars.RESET}")
                return@register
            }

            if (isCoreAdmin(id)) {
                Call.announce(viewer.con, "${PluginVars.WARN}${I18nManager.get("votekick.noadmin", viewer)}${PluginVars.RESET}")
                return@register
            }

            val banUntil = System.currentTimeMillis() + seconds * 1000
            playerData.banUntil = banUntil

            Groups.player.each { target ->
                if (target != null && playerData.uuids.contains(target.uuid())) {
                    target.kick("")
                    restorePlayerEditsWithinSeconds(target.uuid(), 200)
                    UnitEffects.clear(target.uuid())
                }
            }

            Call.announce(
                viewer.con,
                "${PluginVars.SUCCESS}${I18nManager.get("playerInfo.setban.success", viewer)}${PluginVars.RESET}"
            )

            DataManager.requestSave()
        }

        register("upload", "", "helpCmd.upload") { _, player ->
            PluginMenus.showUploadMapMenu(player)
        }
        register("message", "", "helpCmd.message") { _, player ->
            PluginMenus.showMessageMenu(player)
        }
        register("about", "", "helpCmd.about") { _, player ->
            PluginMenus.showAboutMenu(player)
        }
        register("snapshot", "", "helpCmd.snapshot") { args, player ->
            PluginMenus.showSnapshotMenu(player)
        }
        register("revert", "", "helpCmd.revert") { _, player ->
            PluginMenus.showRevertMenu(player)
        }
        register("profile", "", "helpCmd.profile") { _, player ->
            PluginMenus.showSetProfileMenu(player)
        }
        register("over", "", "helpCmd.over") { _, player ->
            PluginMenus.showGameOverMenu(player)
        }
        register("misc", "", "helpCmd.misc") { _, player ->
            PluginMenus.showOthersMenu(player)
        }
        register("sync", "", "helpCmd.sync") { _, player ->
            if (!player.isLocal) {
                if (Time.timeSinceMillis(player.info.lastSyncTime) < 1000 * 5) {
                    return@register

                }
                player.info.lastSyncTime = Time.millis()
                Call.worldDataBegin(player.con)
                netServer.sendWorldData(player)
            }
        }
        register("votekick", "[player] [reason]", "helpCmd.votekick") { args, player ->
            if (args.isEmpty()) {
                showVoteKickPlayerMenu(player)
                return@register
            }
            if (Groups.player.size() < 3) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.too_few", player)}${PluginVars.RESET}"
                )
                return@register
            }
            val targetName = args[0]

            val target = if (
                targetName.startsWith("#") &&
                targetName.length > 1 &&
                Strings.canParseInt(targetName.substring(1))
            ) {
                val id = Strings.parseInt(targetName.substring(1))
                Groups.player.find { it?.id() == id }
            } else {
                Groups.player.find { it?.name?.contains(targetName, ignoreCase = true) == true }
            }

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

        register("a", "<...>", "Send Messages to admins") { args, player ->
            if (args.isEmpty()) return@register

            val message = args.joinToString(" ")
            val playerName = player.name() ?: I18nManager.get("unknown", player)

            val selfPrefix = "${PluginVars.INFO}<\uE82C>${PluginVars.RESET} " +
                    "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
            val selfMessage = "$selfPrefix$message${PluginVars.RESET}"
            player.sendMessage(selfMessage)

            Groups.player.each { receiver ->
                if (receiver === player || !receiver.admin) return@each
                val rPrefix = "${PluginVars.INFO}<\uE82C>${PluginVars.RESET} " +
                        "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
                val acc = DataManager.getPlayerDataByUuid(receiver.uuid())
                val lang = acc?.lang ?: receiver.locale()

                translate(message, "auto", lang, { translated ->
                    val msg = if (translated != message)
                        "$rPrefix$message ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}"
                    else
                        selfMessage
                    receiver.sendMessage(msg)
                }, {
                    receiver.sendMessage(selfMessage)
                })
            }
        }

        register("t", "<...>", "helpCmd.t") { args, player ->
            if (args.isEmpty()) return@register

            val message = args.joinToString(" ")
            val playerName = player.name() ?: I18nManager.get("unknown", player)
            val prefix = "${PluginVars.INFO}<${I18nManager.get("team.tag", player)}>${PluginVars.RESET} " +
                    "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
            val formatted = "$prefix$message${PluginVars.RESET}"

            player.sendMessage(formatted)

            Groups.player.each { receiver ->
                if (receiver === player || receiver.team() != player.team()) return@each
                if (RecordMessage.isDisabled(receiver.uuid())) return@each
                val rPrefix = "${PluginVars.INFO}<${I18nManager.get("team.tag", receiver)}>${PluginVars.RESET} " +
                        "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
                val acc = DataManager.getPlayerDataByUuid(receiver.uuid())
                val lang = acc?.lang ?: receiver.locale()
                translate(message, "auto", lang, { translated ->
                    val msg = if (translated != message)
                        "$rPrefix$message ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}"
                    else
                        formatted
                    receiver.sendMessage(msg)
                }, {
                    receiver.sendMessage(formatted)
                })
            }
        }


        register("surrender", "", "helpCmd.surrender") { _, player ->
            val team = player.team()
            val teamPlayerCount = Groups.player.count { it.team() == team && !it.dead() }

            if (team == Team.derelict || !team.data().hasCore() || teamPlayerCount < 3) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("surrender.no_team", player)}${PluginVars.RESET}"
                )
                return@register
            }

            showConfirmMenu(player) {
                VoteManager.createTeamVote(player) { ok ->
                    if (ok) {
                        team.cores().forEach { core ->
                            mindustry.entities.Damage.damage(
                                Team.derelict, core.x, core.y,
                                1.2f, 9999999f, false, false
                            )
                        }
                        Groups.build.each { if (it.team == team) it.kill() }
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
                Groups.player.each { p ->
                    if (p.team() == team && p != player && !p.dead() && !isBanned(p.uuid())) {
                        val title = "${PluginVars.WARN}${I18nManager.get("surrender.vote.title", p)}${PluginVars.RESET}"
                        val desc = "${PluginVars.GRAY}${I18nManager.get("surrender.vote.desc", p)}${PluginVars.RESET}"

                        val menu = MenusManage.createConfirmMenu(
                            title = {title},
                            desc = {desc},
                            onResult = { pl, choice ->
                                if (choice == 0) {
                                    VoteManager.addVote(pl.uuid(), team)
                                }
                            }
                        )

                        menu(p)
                    }
                }
            }
        }


    }
}