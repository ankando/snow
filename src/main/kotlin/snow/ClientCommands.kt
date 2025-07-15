package plugin.snow

import arc.util.CommandHandler
import arc.util.Time
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.core.GameState
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import plugin.core.*
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.PermissionManager.isNormal
import plugin.core.PermissionManager.verifyPermissionLevel
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
            required: PermissionLevel = PermissionLevel.NORMAL,
            exec: (Array<String>, Player) -> Unit
        ) {
            handler.register<Player>(
                name,
                args,
                desc
            ) { args, player ->
                verifyPermissionLevel(player, required) {
                    exec(args, player)
                }
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
            if (player.team() != Team.derelict) {
                Call.announce(player.con, I18nManager.get("join.joined", player))
                return@register
            }
            PluginMenus.showTeamMenu(player)
        }
        register("rules", "", "helpCmd.rules") { _, player ->
            PluginMenus.showRulesMenu(player)
        }
        register("info", "", "helpCmd.info") { _, player ->
            PluginMenus.showMapInfoMenu(player, Vars.state.map)
        }
        register("upload", "", "helpCmd.upload") { _, player ->
            PluginMenus.showUploadMapMenu(player)
        }
        register("revert", "", "helpCmd.revert") { _, player ->
            PluginMenus.showRevertMenu(player)
        }
        register("profile", "", "helpCmd.profile") { _, player ->
            PluginMenus.showSetProfileMenu(player)
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
        register("logout", "[all]", "helpCmd.logout") { args, player ->
            val uuid = player.uuid()
            val acc = DataManager.getPlayerDataByUuid(uuid)
            val team = PlayerTeamManager.getTeam(uuid)
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
            if (Vars.state.rules.pvp && team != null) {
                Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("inPvP", player)}${PluginVars.RESET}")
                return@register
            }
            showConfirmMenu(player) {
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
                    player.kick("", 0)
                }
            }
        }
        register("metch", "", "helpCmd.metch") { _, player ->
            if (!Vars.state.rules.pvp) {
                player.sendMessage("${PluginVars.WARN}${I18nManager.get("metch.only_pvp", player)}${PluginVars.RESET}")
                return@register
            }

            if (!isCoreAdmin(player.uuid())) {
                player.sendMessage("${PluginVars.WARN}${I18nManager.get("metch.admin_only", player)}${PluginVars.RESET}")
                return@register
            }

            fun showMapSelectMenu(player: Player) {
                val selected = CompetitionManager.getAllMaps()
                val title = "${PluginVars.GRAY}Maps ${selected.size}/3${PluginVars.RESET}"

                val entries = Vars.maps.customMaps().map { map ->
                    val alreadySelected = selected.contains(map)
                    val label = if (alreadySelected)
                        "${PluginVars.SUCCESS}✔ ${map.name()}"
                    else
                        map.name()

                    MenuEntry("${PluginVars.WHITE}$label${PluginVars.RESET}") {
                        if (alreadySelected) {
                            CompetitionManager.removeMap(map)
                        } else {
                            if (selected.size < 3) {
                                CompetitionManager.addMap(map)
                            }
                        }

                        // 判断是否选够 3 张
                        if (CompetitionManager.getAllMaps().size >= 3) {
                            Vars.state.set(GameState.State.paused)
                            CompetitionManager.setCompetitionState(1)
                            PluginMenus.showTeamMenu(player)
                        } else {
                            showMapSelectMenu(player)
                        }
                    }
                }

                MenusManage.createMenu<Unit>(
                    title = { _, _, _, _ -> title },
                    desc = { _, _, _ -> "" },
                    paged = true,
                    options = { _, _, _ -> entries.toMutableList() }
                )(player, 1)
            }

            val entries = listOf(
                MenuEntry("${PluginVars.WHITE}${I18nManager.get("metch.prepare_match", player)}${PluginVars.RESET}") {
                    if (CompetitionManager.getCompetitionState() != 0) {
                        player.sendMessage("${PluginVars.WARN}${I18nManager.get("metch.already_started", player)}${PluginVars.RESET}")
                        return@MenuEntry
                    }
                    CompetitionManager.resetCompetition()
                    showMapSelectMenu(player)
                },
                MenuEntry("${PluginVars.WHITE}${I18nManager.get("metch.start_match", player)}${PluginVars.RESET}") {
                    if (CompetitionManager.getCompetitionState() != 1) {
                        player.sendMessage("${PluginVars.WARN}${I18nManager.get("metch.not_voting", player)}${PluginVars.RESET}")
                        return@MenuEntry
                    }
                    CompetitionManager.initializeCompetitionMode()
                    Call.announce("${PluginVars.SUCCESS}${I18nManager.get("metch.match_started", player)}${PluginVars.RESET}")
                },
                MenuEntry("${PluginVars.SECONDARY}${I18nManager.get("metch.cancel_match", player)}${PluginVars.RESET}") {
                    CompetitionManager.resetCompetition()
                    Vars.state.set(GameState.State.playing)
                    Call.announce("${PluginVars.INFO}${I18nManager.get("metch.match_reset", player)}${PluginVars.RESET}")
                }
            )


            MenusManage.createMenu<Unit>(
                title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("metch.title", player)}${PluginVars.RESET}" },
                desc = { _, _, _ -> "" },
                paged = false,
                options = { _, _, _ -> entries }
            )(player, 1)
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
                    if (p.team() == team && p != player && !p.dead() && isNormal(p.uuid())) {
                        val title = "${PluginVars.WARN}${I18nManager.get("surrender.vote.title", p)}${PluginVars.RESET}"
                        val desc = "${PluginVars.GRAY}${I18nManager.get("surrender.vote.desc", p)}${PluginVars.RESET}"

                        val menu = MenusManage.createConfirmMenu(
                            title = title,
                            desc = desc,
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