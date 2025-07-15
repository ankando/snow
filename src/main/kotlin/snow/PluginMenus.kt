package plugin.snow

import arc.Events
import arc.math.Mathf
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.core.NetClient
import mindustry.game.EventType
import mindustry.game.Rules
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.type.UnitType
import mindustry.ui.Menus
import mindustry.world.Block
import plugin.core.*
import plugin.core.MenusManage.createConfirmMenu
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.PermissionManager.isNormal
import plugin.core.PermissionManager.verifyPermissionLevel
import plugin.core.RevertBuild.restorePlayerEditsWithinSeconds
import kotlin.math.max

object PluginMenus {
    fun showGamesMenu(player: Player, page: Int = 1) {
        val games = listOf(
            "2048" to ::show2048game
        )

        val menu = MenusManage.createMenu<Unit>(
            title = { p, _, _, _ ->
                "${PluginVars.GRAY}${I18nManager.get("games.title", p)}${PluginVars.RESET}"
            },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ ->
                games.map { (label, action) ->
                    MenuEntry("${PluginVars.WHITE}$label${PluginVars.RESET}") {
                        action(player)
                    }
                }
            }
        )

        menu(player, page)
    }


    private val game2048States = mutableMapOf<String, Array<IntArray>>()

    private const val WIN_TILE = 11
    private val tileSprites = mapOf(
        0 to "\uF8F7",  // empty
        1 to "\uF85B",  // 2
        2 to "\uF861",  // 4
        3 to "\uF85E",  // 8
        4 to "\uF85C",  // 16
        5 to "\uF85A",  // 32
        6 to "\uF857",  // 64
        7 to "\uF858",  // 128
        8 to "\uF856",  // 256
        9 to "\uF7BE",  // 512
        10 to "\uF683", // 1024
        11 to "\uF684"  // 2048
    )

    fun show2048game(player: Player) {
        val grid = game2048States.getOrPut(player.uuid()) { Array(4) { IntArray(4) } }

        if (grid.all { row -> row.all { it == 0 } }) {
            repeat(2) { addRandomTile(grid) }
        }

        val buttons = arrayOf(
            arrayOf("", fmtArrow("\uE804", canMoveDir(grid, 0, -1)), ""),
            arrayOf(fmtArrow("\uE802", canMoveDir(grid, -1, 0)), "", fmtArrow("\uE803", canMoveDir(grid, 1, 0))),
            arrayOf("", fmtArrow("\uE805", canMoveDir(grid, 0, 1)), ""),
            arrayOf("${PluginVars.SECONDARY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}")
        )

        if (!canMove(grid) && grid.all { row -> row.none { it == 0 } }) {
            Call.followUpMenu(player.con, menu2048Id, "2048", "\n${I18nManager.get("game.defeat", player)}\n", buttons)
            return
        }
        if (grid.any { row -> row.any { it >= WIN_TILE } }) {
            Call.followUpMenu(player.con, menu2048Id, "2048", "\n${I18nManager.get("game.victory", player)}\n", buttons)
            return
        }

        Call.followUpMenu(player.con, menu2048Id, "2048", "\n${renderGrid(grid)}", buttons)
    }

    private fun fmtArrow(sym: String, enabled: Boolean) = if (enabled) "${PluginVars.WHITE}$sym${PluginVars.RESET}" else "${PluginVars.SECONDARY}$sym${PluginVars.RESET}"

    private fun renderGrid(grid: Array<IntArray>): String = buildString {
        grid.forEach { row ->
            row.forEach { tile -> append(tileSprites[tile]) }
            append('\n')
        }
    }

    private fun moveGrid(grid: Array<IntArray>, dx: Int, dy: Int): Boolean {
        var moved = false
        val rangeX = if (dx > 0) 2 downTo 0 else 1..3
        val rangeY = if (dy > 0) 2 downTo 0 else 1..3

        if (dx != 0) {
            for (y in 0..3) for (x in rangeX) moved = moved or slide(grid, x, y, dx, 0)
        } else {
            for (y in rangeY) for (x in 0..3) moved = moved or slide(grid, x, y, 0, dy)
        }
        return moved
    }

    private fun slide(grid: Array<IntArray>, x: Int, y: Int, dx: Int, dy: Int): Boolean {
        val v = grid[y][x]
        if (v == 0) return false
        var cx = x
        var cy = y
        var moved = false
        while (true) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx !in 0..3 || ny !in 0..3) break
            val next = grid[ny][nx]
            if (next == 0) {
                grid[ny][nx] = v
                grid[cy][cx] = 0
                cx = nx; cy = ny; moved = true
            } else if (next == v) {
                grid[ny][nx] = v + 1
                grid[cy][cx] = 0
                moved = true; break
            } else break
        }
        return moved
    }

    private fun addRandomTile(grid: Array<IntArray>) {
        val empty = mutableListOf<Pair<Int, Int>>()
        grid.forEachIndexed { y, row -> row.forEachIndexed { x, v -> if (v == 0) empty += x to y } }
        if (empty.isNotEmpty()) {
            val (x, y) = empty.random()
            grid[y][x] = if (Mathf.chance(0.9)) 1 else 2
        }
    }

    private fun canMove(grid: Array<IntArray>): Boolean {
        for (y in 0..3) for (x in 0..3) {
            val v = grid[y][x]
            if (v == 0) return true
            if (x < 3 && grid[y][x + 1] == v) return true
            if (y < 3 && grid[y + 1][x] == v) return true
        }
        return false
    }

    private fun canMoveDir(grid: Array<IntArray>, dx: Int, dy: Int): Boolean {
        val test = Array(4) { grid[it].clone() }
        return moveGrid(test, dx, dy)
    }

    private const val IDX_UP = 1
    private const val IDX_LEFT = 3
    private const val IDX_RIGHT = 5
    private const val IDX_DOWN = 7
    private const val IDX_CLOSE = 9

    private val menu2048Id: Int = Menus.registerMenu { player, choice ->
        if (choice < 0) return@registerMenu
        if (choice == IDX_CLOSE) {
            Call.hideFollowUpMenu(player.con, menu2048Id)
            game2048States.remove(player.uuid())
            return@registerMenu
        }
        val grid = game2048States[player.uuid()] ?: return@registerMenu
        val moved = when (choice) {
            IDX_UP -> moveGrid(grid, 0, -1)
            IDX_LEFT -> moveGrid(grid, -1, 0)
            IDX_RIGHT -> moveGrid(grid, 1, 0)
            IDX_DOWN -> moveGrid(grid, 0, 1)
            else -> false
        }
        if (moved) addRandomTile(grid)
        show2048game(player)
    }

    private val editableBooleanRules = listOf(
        "fire",
        "onlyDepositCore",
        "polygonCoreProtection",
        "placeRangeCheck",
        "schematicsAllowed",
        "allowEditWorldProcessors",
        "coreCapture",
        "reactorExplosions",
        "possessionAllowed",
        "unitAmmo",
        "fog"
    )
    private val editableFloatRules = listOf(
        "buildSpeedMultiplier",
        "blockHealthMultiplier", "blockDamageMultiplier",
        "buildCostMultiplier", "deconstructRefundMultiplier",
        "unitBuildSpeedMultiplier", "unitDamageMultiplier",
        "unitHealthMultiplier", "unitCostMultiplier",
        "unitCrashDamageMultiplier", "solarMultiplier"
    )

    private fun getRuleValue(ruleName: String): Any? {
        return try {
            Vars.state.rules.javaClass.getDeclaredField(ruleName).apply {
                isAccessible = true
            }.get(Vars.state.rules)
        } catch (_: Exception) {
            null
        }
    }

    private fun Rules.setRule(name: String, value: Any) {
        try {
            val field = this.javaClass.getDeclaredField(name)
            field.isAccessible = true
            field.set(this, value)
        } catch (_: Exception) {
        }
    }

    private fun promptRuleValueInput(player: Player, rule: String) {
        MenusManage.createTextInput(
            title = rule,
            desc = "",
            placeholder = "",
            isNum = false,
            maxChars = 10
        ) { p, input ->
            val newValue = input.toFloatOrNull()
            if (newValue == null || newValue < 0f) {
                return@createTextInput
            }
            Vars.state.rules.setRule(rule, newValue)
            Call.setRules(Vars.state.rules)
            Call.announce(p.con, "${PluginVars.GRAY}${p.plainName()} set $rule to $newValue")
            showRulesMenu(p)
        }(player)
    }

    private fun sortedBlocks(): List<Block> {
        val banned = Vars.state.rules.bannedBlocks
        return Vars.content.blocks().toArray()
            .filter { it.canBeBuilt() && it != Blocks.air }
            .sortedWith(compareByDescending<Block> { banned.contains(it) })
    }

    private fun sortedUnits(): List<UnitType> {
        val banned = Vars.state.rules.bannedUnits
        return Vars.content.units().toArray()
            .filter { !it.internal }
            .sortedWith(compareByDescending<UnitType> { banned.contains(it) })
    }

    private val rulesMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu

        val allRules = editableBooleanRules + editableFloatRules
        val blocksIndex = allRules.size
        val unitsIndex = allRules.size + 1
        val closeIndex = allRules.size + 2

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, rulesMenuId)
            blocksIndex -> showBlocksMenu(player)
            unitsIndex -> showUnitsMenu(player)
            in 0 until allRules.size -> {
                verifyPermissionLevel(player, PermissionLevel.CORE_ADMIN) {
                    val rule = allRules[choice]
                    if (editableBooleanRules.contains(rule)) {
                        val cur = getRuleValue(rule) as? Boolean ?: return@verifyPermissionLevel
                        Vars.state.rules.setRule(rule, !cur)
                        Call.setRules(Vars.state.rules)
                        showRulesMenu(player)
                    } else if (editableFloatRules.contains(rule)) {
                        promptRuleValueInput(player, rule)
                    }
                }
            }
        }
    }

    fun showRulesMenu(player: Player) {
        verifyPermissionLevel(player, PermissionLevel.NORMAL) {
            val allRules = editableBooleanRules + editableFloatRules
            val rows = allRules.chunked(3).map { row ->
                row.map { rule ->
                    val value = getRuleValue(rule)
                    "\n${PluginVars.WHITE}$rule: ${PluginVars.SECONDARY}$value${PluginVars.RESET}\n"
                }.toTypedArray()
            }.toMutableList()

            val blocksLabel =
                "${PluginVars.WHITE}\uE871 ${I18nManager.get("rules.blocks", player)}${PluginVars.RESET}"
            val unitsLabel =
                "${PluginVars.WHITE}\uE82A ${I18nManager.get("rules.units", player)}${PluginVars.RESET}"
            val closeLabel = "${PluginVars.GRAY}\uE815${PluginVars.RESET}"
            rows += arrayOf(blocksLabel)
            rows += arrayOf(unitsLabel)
            rows += arrayOf(closeLabel)
            val buttons = rows.toTypedArray()
            Call.followUpMenu(
                player.con,
                rulesMenuId,
                "${PluginVars.GRAY}${I18nManager.get("rules.title", player)}${PluginVars.RESET}",
                "",
                buttons
            )
        }
    }

    private val blockMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu
        val bannedBlocks = Vars.state.rules.bannedBlocks
        val blocks = sortedBlocks()
        val closeIndex = blocks.size

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, blockMenuId)
            in 0 until blocks.size -> {
                verifyPermissionLevel(player, PermissionLevel.CORE_ADMIN) {
                    val block = blocks[choice]
                    if (bannedBlocks.contains(block)) bannedBlocks.remove(block) else bannedBlocks.add(block)
                    Call.setRules(Vars.state.rules)
                    showBlocksMenu(player)
                }
            }
        }
    }

    private val unitMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu
        val units = sortedUnits()
        val closeIndex = units.size

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, unitMenuId)
            in 0 until units.size -> {
                verifyPermissionLevel(player, PermissionLevel.CORE_ADMIN) {
                    val unit = units[choice]
                    val bannedUnits = Vars.state.rules.bannedUnits
                    if (bannedUnits.contains(unit)) bannedUnits.remove(unit) else bannedUnits.add(unit)
                    Call.setRules(Vars.state.rules)
                    showUnitsMenu(player)
                }
            }
        }
    }

    private fun showBlocksMenu(player: Player) {
        verifyPermissionLevel(player, PermissionLevel.NORMAL) {
            val banned = Vars.state.rules.bannedBlocks
            val rows = sortedBlocks()
                .chunked(4)
                .map { row ->
                    row.map { b ->
                        val col = if (banned.contains(b)) PluginVars.SECONDARY else PluginVars.WHITE
                        "$col${b.localizedName}${PluginVars.RESET}"
                    }.toTypedArray()
                }.toMutableList()

            rows += arrayOf("${PluginVars.GRAY}\uE815 ${I18nManager.get("close", player)}${PluginVars.RESET}")
            Call.followUpMenu(
                player.con,
                blockMenuId,
                "${PluginVars.GRAY}${I18nManager.get("rules.blocks", player)}${PluginVars.RESET}",
                "",
                rows.toTypedArray()
            )
        }
    }

    private fun showUnitsMenu(player: Player) {
        verifyPermissionLevel(player, PermissionLevel.NORMAL) {
            val banned = Vars.state.rules.bannedUnits
            val rows = sortedUnits()
                .chunked(4)
                .map { row ->
                    row.map { u ->
                        val col = if (banned.contains(u)) PluginVars.SECONDARY else PluginVars.WHITE
                        "$col${u.localizedName}${PluginVars.RESET}"
                    }.toTypedArray()
                }.toMutableList()

            rows += arrayOf("${PluginVars.GRAY}\uE815 ${I18nManager.get("close", player)}${PluginVars.RESET}")
            Call.followUpMenu(
                player.con,
                unitMenuId,
                "${PluginVars.GRAY}${I18nManager.get("rules.units", player)}${PluginVars.RESET}",
                "",
                rows.toTypedArray()
            )
        }
    }

    fun showHelpMenu(player: Player, page: Int = 1) {
        val show = MenusManage.createMenu<Unit>(
            title = { p, pageNum, total, _ ->
                "${PluginVars.GRAY}${I18nManager.get("help.title", p)} $pageNum/$total${PluginVars.RESET}"
            },
            desc = { p, _, _ -> ""
            },
            options = { p, _, _ ->
                Vars.netServer.clientCommands.commandList
                    .sortedBy { it.text }
                    .filter { it.text != "help" }
                    .filter { it.text != "t" }
                    .filter { it.text != "votekick" }
                    .map { cmd ->
                        val descKey = "helpCmd.${cmd.text}"
                        val desc = I18nManager.get(descKey, p)
                        MenuEntry("${PluginVars.WHITE}$desc${PluginVars.RESET}"
                        ) { player ->
                            NetClient.sendChatMessage(player, "/${cmd.text}")
                        }
                    }
            }
        )

        show(player, page)
    }

    fun showRankMenu(player: Player) {
        val rankMenu = MenusManage.createMenu<Unit>(
            title = { player, _, _, _ ->
                "${PluginVars.GRAY}${
                    I18nManager.get("rank.title", player)
                }${PluginVars.RESET}"
            },
            perPage = 50,
            desc = { player, _, _ ->
                val ranked = DataManager.players.values().filter { it.score > 0 }
                    .sortedByDescending { it.score }
                val myAcc = ranked.find { it.uuids.any { uuid -> uuid == player.uuid() } }
                val myScore = myAcc?.score ?: 0
                val myRank = if (myAcc != null) ranked.indexOfFirst { it.id == myAcc.id } + 1 else 0

                buildString {
                    if (myScore > 0) {
                        append(
                            "\n${PluginVars.GRAY}${
                                I18nManager.get(
                                    "rank.your_rank",
                                    player
                                )
                            } ${PluginVars.INFO}$myRank/${ranked.size} | $myScore${PluginVars.RESET}\n\n"
                        )
                    } else {
                        append("\n${PluginVars.INFO}${I18nManager.get("rank.nopoints", player)}${PluginVars.RESET}\n\n")
                    }
                    ranked.take(50).forEachIndexed { i, acc ->
                        val nick = acc.account.ifBlank { I18nManager.get("rank.unknown", player) }
                        val numColor   = if (i < 3) PluginVars.GOLD else PluginVars.INFO
                        val scoreColor = if (i < 3) PluginVars.GOLD else PluginVars.INFO

                        append(
                            "$numColor${i + 1}. $nick: $scoreColor${acc.score}${PluginVars.RESET}\n"
                        )
                    }

                }
            },
            paged = false,
            options = { _, _, _ -> emptyList() }
        )
        rankMenu(player, 1)
    }


    fun showPlayersMenu(player: Player, page: Int = 1) {
        val playersMenu = MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("players.title", player)}${PluginVars.RESET}" },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ ->
                Groups.player
                    .map { player ->
                        MenuEntry("${PluginVars.WHITE}${player.name()}${PluginVars.RESET}") { viewer ->
                            showPlayerInfoMenu(viewer, player)
                        }
                    }
            }
        )
        playersMenu(player, page)
    }


    fun showPlayerInfoMenu(viewer: Player, target: Player) {
        val acc = DataManager.getPlayerDataByUuid(target.uuid()) ?: return
        val canSet = !isCoreAdmin(target.uuid()) && target != viewer
        val strong = PluginVars.INFO
        val weak = PluginVars.SECONDARY
        val exclude = mutableListOf<Player>()
        exclude.add(target)

        val desc = buildString {
            append("\n${PluginVars.WHITE}${acc.account}${PluginVars.RESET}\n\n")
            append(
                "${PluginVars.SECONDARY}${I18nManager.get("playerInfo.score", viewer)}: ${acc.score}${PluginVars.RESET}\n"
            )
            append(
                "${PluginVars.SECONDARY}${I18nManager.get("playerInfo.wins", viewer)}: ${acc.wins}${PluginVars.RESET}\n"
            )
            append(
                "${PluginVars.SECONDARY}${I18nManager.get("playerInfo.lang", viewer)}: ${acc.lang}${PluginVars.RESET}\n"
            )
            append(
                if (isCoreAdmin(target.uuid())) {
                    "${PluginVars.SECONDARY}${I18nManager.get("playerInfo.role", viewer)}: ${I18nManager.get("role.admin", viewer)}${PluginVars.RESET}\n"
                } else {
                    "${PluginVars.SECONDARY}${I18nManager.get("playerInfo.role", viewer)}: ${I18nManager.get("role.normal", viewer)}${PluginVars.RESET}\n"
                }
            )
        }

        val btnPm = MenuEntry(
            "${PluginVars.WHITE}${I18nManager.get("playerInfo.pm", viewer)}${PluginVars.RESET}"
        ) {
            MenusManage.createTextInput(
                title = I18nManager.get("playerInfo.pm.title", viewer),
                desc = "",
                isNum = false,
                placeholder = ""
            ) { sender, input ->
                if (input.isBlank()) return@createTextInput
                target.sendMessage("${PluginVars.INFO}\uE836 ${sender.name()}:${PluginVars.RESET}${PluginVars.GRAY} $input")
                sender.sendMessage("${PluginVars.INFO}\uE835 ${target.name()}:${PluginVars.RESET}${PluginVars.GRAY} $input")
            }(viewer)
        }

        val btnVoteKick = MenuEntry(
            "${if (canSet) strong else weak}${I18nManager.get("playerInfo.votekick", viewer)}${PluginVars.RESET}"
        ) {
            if (!canSet) {
                Call.announce(
                    viewer.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", viewer)}${PluginVars.RESET}"
                )
                return@MenuEntry
            }
            showConfirmMenu(viewer) {
                beginVotekick(viewer, target)
            }
        }

        val btnBan = MenuEntry(
            "${if (canSet) strong else weak}${I18nManager.get("playerInfo.setban", viewer)}${PluginVars.RESET}"
        ) {
            if (!canSet) {
                Call.announce(
                    viewer.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", viewer)}${PluginVars.RESET}"
                )
                return@MenuEntry
            }
            MenusManage.createTextInput(
                title = I18nManager.get("playerInfo.setban.title", viewer),
                desc = "",
                isNum = false,
                placeholder = ""
            ) { _, input ->
                val seconds = input.toLongOrNull()
                if (seconds == null || seconds < 0) {
                    Call.announce(
                        viewer.con,
                        "${PluginVars.WARN}${
                            I18nManager.get(
                                "playerInfo.setban.invalid",
                                viewer
                            )
                        }${PluginVars.RESET}"
                    )
                    return@createTextInput
                }
                DataManager.updatePlayer(acc.id) {
                    it.banUntil = if (seconds == 0L) 0 else System.currentTimeMillis() + seconds * 1000
                }
                Call.announce(
                    viewer.con,
                    "${PluginVars.SUCCESS}${
                        I18nManager.get(
                            "playerInfo.setban.success",
                            viewer
                        )
                    }${PluginVars.RESET}"
                )
            }
        }

        val rows = listOf(btnPm, btnVoteKick, btnBan)
        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.GRAY}${
                    I18nManager.get(
                        "playerInfo.title",
                        viewer
                    )
                }${PluginVars.RESET}"
            },
            paged = false,
            desc = { _, _, _ -> desc },
            options = { _, _, _ -> rows }
        )(viewer, 1)

    }
    fun showMapMenu(player: Player, page: Int = 1) {
        val mapMenu = MenusManage.createMenu<Unit>(
            title = { _, page, total, _ ->
                "${PluginVars.GRAY}${
                    I18nManager.get(
                        "map.title",
                        player
                    )
                } $page/$total${PluginVars.RESET}"
            },
            desc = { _, _, _ -> "" },
            options = { _, _, _ ->
                Vars.maps.customMaps().toList().mapIndexed { i, map ->
                    MenuEntry(
                        "${PluginVars.WHITE}\uF029${i + 1} ${map.name()}${PluginVars.RESET}"
                    ) { player ->
                        showMapOptionMenu(player, map)
                    }
                }
            }
        )
        mapMenu(player, page)
    }


    private fun reloadWorld(map: mindustry.maps.Map) {
        if(Vars.state.isMenu){
            return
        }
        Vars.maps.setNextMapOverride(map)
        Events.fire(EventType.GameOverEvent(Team.derelict))
    }


    fun showMapOptionMenu(player: Player, map: mindustry.maps.Map) {
        val isAdmin = isCoreAdmin(player.uuid())
        val normalCount = Groups.player.count { isNormal(it.uuid()) }
        val isOk = isAdmin || normalCount < 2
        val strong = PluginVars.INFO
        val weak = PluginVars.SECONDARY
        val desc = buildString {
            append("\n$${PluginVars.SECONDARY}${map.author() ?: I18nManager.get("unknown", player)}${PluginVars.RESET}\n")
            append("\n${PluginVars.GRAY}${map.description()}${PluginVars.RESET}\n")
        }

        val btnVote = MenuEntry("${PluginVars.WHITE}${I18nManager.get("mapInfo.vote", player)}${PluginVars.RESET}") {
            if (normalCount <= 1) {
                reloadWorld(map)
                Call.announce(
                    player.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("rtv.changed_alone", player)}${PluginVars.RESET}"
                )
            } else {
                if (Vars.state.isGame && Vars.state.rules.pvp && Vars.state.tick > 5 * 60 * 60) {
                    Call.announce(player.con, "${PluginVars.WHITE}${I18nManager.get("inPvP", player)}")
                    return@MenuEntry
                }

                showConfirmMenu(player) {
                    VoteManager.createGlobalVote(
                        creator = player
                    ) { ok ->
                        if (ok && Vars.state.isGame) {
                            reloadWorld(map)
                        }
                    }

                    Groups.player.each { p ->
                        if (p != player && !p.dead() && isNormal(p.uuid())) {
                            val title = "${PluginVars.INFO}${I18nManager.get("rtv.title", p)}${PluginVars.RESET}"
                            val desc = "\uE827 ${PluginVars.GRAY}${player.name} ${I18nManager.get("rtv.desc", p)} ${map.name()}${PluginVars.RESET}"

                            val menu = createConfirmMenu(
                                title = title,
                                desc = desc,
                                onResult = { pl, choice ->
                                    if (choice == 0) {
                                        VoteManager.addVote(pl.uuid())
                                    }
                                }
                            )

                            menu(p)
                        }



                    }
                }
            }
        }


        val btnChange = MenuEntry(
            "${if (isOk) strong else weak}${
                I18nManager.get(
                    "mapInfo.change",
                    player
                )
            }${PluginVars.RESET}"
        ) {
            if (isOk) {
                showConfirmMenu(player) {
                    reloadWorld(map)
                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("mapInfo.changed", player)}${PluginVars.RESET}"
                    )
                }
            } else {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}"
                )
            }
        }

        val btnNext = MenuEntry(
            "${if (isOk) strong else weak}${
                I18nManager.get(
                    "mapInfo.next",
                    player
                )
            }${PluginVars.RESET}"
        ) {
            if (isOk) {
                showConfirmMenu(player) {
                    Vars.maps.setNextMapOverride(map)
                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("mapInfo.nextset", player)}${PluginVars.RESET}"
                    )
                }
            } else {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}"
                )
            }
        }

        val rows = listOf(btnVote, btnChange, btnNext)

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${map.name()}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> desc },
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    fun showLanguageMenu(player: Player) {
        val acc = DataManager.getPlayerDataByUuid(player.uuid()) ?: return
        val langs = listOf(
            "zh_CN" to "${PluginVars.WHITE}中文${PluginVars.RESET}",
            "en" to "${PluginVars.WHITE}English${PluginVars.RESET}",
            "ru" to "${PluginVars.WHITE}Русский${PluginVars.RESET}",
            "ja" to "${PluginVars.WHITE}日本語${PluginVars.RESET}",
            "ko" to "${PluginVars.WHITE}한국인${PluginVars.RESET}"
        )

        val rows = langs.map { (code, display) ->
            val selected = if (acc.lang == code) PluginVars.SUCCESS else ""
            MenuEntry("$selected$display${PluginVars.RESET}") { _ ->
                if (acc.lang != code) {
                    DataManager.updatePlayer(acc.id) { it.lang = code }
                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("lang.changed", player)}${PluginVars.RESET}"
                    )
                }
            }
        }.toMutableList()

        rows.add(
            0,
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("lang.auto", player)}${PluginVars.RESET}") {
                val locale = player.locale()
                DataManager.updatePlayer(acc.id) { it.lang = locale }
                Call.announce(
                    player.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("lang.changed", player)}${PluginVars.RESET}"
                )
            }
        )

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("lang.title", player)}${PluginVars.RESET}" },
            desc = { _, _, _ -> ""},
            paged = false,
            options = { _, _, _ -> rows }
        )(player, 1)
    }


    fun showSetProfileMenu(player: Player) {
        val acc = DataManager.getPlayerDataByUuid(player.uuid()) ?: return
        val isCoreAdmin = isCoreAdmin(player.uuid())
        val canToggleAdmin = isCoreAdmin
        val strong = PluginVars.WHITE
        val weak = PluginVars.WARN

        val i18nTrue = I18nManager.get("common.true", player)
        val i18nFalse = I18nManager.get("common.false", player)

        val btnUsername = MenuEntry("${strong}${I18nManager.get("profile.username", player)}${PluginVars.RESET}") {
            MenusManage.createTextInput(
                title = I18nManager.get("profile.username.title", player),
                desc = I18nManager.get("profile.username.desc", player),
                isNum = false,
                placeholder = acc.account
            ) { _, input ->
                val trimName = input.trim()
                if (trimName.isBlank()) {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("profile.username.invalid", player)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }
                val exists = DataManager.players.values()
                    .any { it.account.equals(trimName, ignoreCase = true) && it.id != acc.id }
                if (exists) {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("profile.username.duplicate", player)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }
                DataManager.updatePlayer(acc.id) { it.account = trimName }
                Call.announce(
                    player.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("profile.username.success", player)}${PluginVars.RESET}"
                )
            }(player)
        }

        val btnPassword = MenuEntry("${strong}${I18nManager.get("profile.password", player)}${PluginVars.RESET}") {
            MenusManage.createTextInput(
                title = I18nManager.get("profile.password.title", player),
                desc = I18nManager.get("profile.password.desc", player),
                isNum = true,
                placeholder = ""
            ) { _, input ->
                val trimPwd = input.trim()

                if (trimPwd.length < 4) {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("profile.password.invalid", player)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }

                val allowedSymbols = setOf('.', ',', '-', '_', '@', '#', '!', '?')
                val isValid = trimPwd.all { it.isLetterOrDigit() || it.isWhitespace() || it in allowedSymbols }

                if (!isValid) {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("profile.password.invalid", player)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }

                DataManager.updatePlayer(acc.id) { it.password = trimPwd }
                Call.announce(
                    player.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("profile.password.success", player)}${PluginVars.RESET}"
                )
            }(player)
        }


        val btnLang = MenuEntry("${strong}${I18nManager.get("profile.language", player)}: ${weak}${DataManager.getPlayerDataByUuid(player.uuid())?.lang}${PluginVars.RESET}") {
            showLanguageMenu(player)
        }

        val btnLock = MenuEntry(
            "${strong}${I18nManager.get("profile.lock", player)}: ${
                if (acc.isLock) i18nTrue else i18nFalse
            }${PluginVars.RESET}"
        ) {
            if (acc.password.isEmpty()) {
                Call.announce(player.con, I18nManager.get("needPass", player))
                return@MenuEntry
            }
            DataManager.updatePlayer(acc.id) { it.isLock = !acc.isLock }
            Call.announce(
                player.con,
                "${PluginVars.SUCCESS}${I18nManager.get("profile.lock.success", player)}${PluginVars.RESET}"
            )
        }

        val btnAdmin = MenuEntry(
            "${if (canToggleAdmin) strong else weak}${I18nManager.get("profile.admin", player)}: ${
                if (acc.isAdmin) i18nTrue else i18nFalse
            }${PluginVars.RESET}"
        ) {
            if (!canToggleAdmin) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}"
                )
                return@MenuEntry
            }
            DataManager.updatePlayer(acc.id) { it.isAdmin = !acc.isAdmin }
            if (player.admin() != acc.isAdmin) player.admin = acc.isAdmin
            Call.announce(
                player.con,
                "${PluginVars.SUCCESS}${I18nManager.get("player.admin.success", player)}${PluginVars.RESET}"
            )
        }

        val rows = listOf(btnUsername, btnPassword, btnLang, btnLock, btnAdmin)

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.GRAY}${I18nManager.get("profile.title", player)}${PluginVars.RESET}"
            },
            paged = false,
            desc = { _, _, _ -> "" },
            options = { _, _, _ -> rows }
        )(player, 1)
    }


    fun showMapInfoMenu(player: Player, map: mindustry.maps.Map) {
        val desc = buildString {
            append(
                "\n${PluginVars.WHITE}${(Vars.state.tick / 60f / 60f).toInt()} ${
                    I18nManager.get(
                        "minute",
                        player
                    )
                }${PluginVars.RESET}\n"
            )
            append("\n")
            append("${PluginVars.WHITE}${map.name()}${PluginVars.RESET}\n")
            append("\n")
            if (!map.author().isNullOrBlank()) {
                append("${PluginVars.SECONDARY}${map.author()}${PluginVars.RESET}\n")
            }
            append("\n")
            append("${PluginVars.GRAY}${map.description()}${PluginVars.RESET}\n")
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.GRAY}${
                    I18nManager.get(
                        "mapInfo.title",
                        player
                    )
                }${PluginVars.RESET}"
            },
            paged = false,
            desc = { _, _, _ -> desc },
            options = { _, _, _ -> listOf() }
        )(player, 1)

    }

    private val tempTeamChoices = mutableMapOf<String, MutableList<Team>>()
    fun showTeamMenu(player: Player) {
        val teamsWithCore = Team.all.filter { t -> t.data().hasCore() && t != Team.derelict }.toMutableList()

        if (teamsWithCore.isEmpty()) {
            Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("team.no_team", player)}${PluginVars.RESET}")
            return
        }

        val all = Groups.player.copy().select { p ->
            val t = p.team()
            t != Team.derelict && t.data().hasCore()
        }

        val totalPlayers = all.size + 1
        val maxPerTeam = max(1, Mathf.ceil(totalPlayers / teamsWithCore.size.toFloat()))
        val desc = ""

        val columns = mutableListOf<MutableList<String?>>()

        for (t in teamsWithCore) {
            val col = mutableListOf<String?>()
            val count = all.count { p -> p.team() === t }

            col.add("${PluginVars.SECONDARY}${t.coloredName()}${PluginVars.RESET}")

            all.each { p ->
                if (p.team() === t) {
                    col.add("${PluginVars.WHITE}${p.name()}${PluginVars.RESET}")
                }
            }

            if (count < maxPerTeam) {
                col.add("${PluginVars.INFO}\uE813${PluginVars.RESET}")
            }

            columns.add(col)
        }

        val maxRows = columns.maxOfOrNull { it.size } ?: 0

        for (col in columns) {
            while (col.size < maxRows) {
                col.add("${PluginVars.SECONDARY}\uE868${PluginVars.RESET}")
            }
        }

        val rows = mutableListOf<Array<String?>>()
        for (rowIndex in 0 until maxRows) {
            val thisRow = Array(columns.size) { colIndex -> columns[colIndex][rowIndex] }
            rows.add(thisRow)
        }

        tempTeamChoices[player.uuid()] = teamsWithCore

        Call.followUpMenu(
            player.con,
            teamMenuId,
            "${PluginVars.GRAY}${I18nManager.get("team.choose", player)}${PluginVars.RESET}",
            desc,
            rows.toTypedArray()
        )
    }

    private fun onMenuChoose(player: Player, choice: Int) {
        if (choice < 0) return

        val teams = tempTeamChoices[player.uuid()]?.toList()?.toMutableList()
        if (teams.isNullOrEmpty()) return

        val totalCols = teams.size
        val adjustedChoice = choice

        val colIndex = adjustedChoice % totalCols
        val rowIndex = adjustedChoice / totalCols

        val target = teams[colIndex]
        if (target === Team.derelict || !target.data().hasCore()) return

        val all = Groups.player.copy().select { p ->
            p?.team()?.let { it != Team.derelict && it.data().hasCore() } ?: false
        }
        val totalPlayers = all.size + 1
        val maxPerTeam = max(1, Mathf.ceil(totalPlayers / teams.size.toFloat()))
        val count = all.count { it?.team() === target }

        val col = all.filter { it?.team() === target }.mapNotNull { it?.name }
        val isPlusButton = (rowIndex == col.size + 1) && (count < maxPerTeam)

        if (!isPlusButton) {
            showTeamMenu(player)
            return
        }

        Call.hideFollowUpMenu(player.con, teamMenuId)
        player.team(target)
        PlayerTeamManager.setTeam(player, target)
        Call.announce(
            player.con,
            "${PluginVars.INFO}${I18nManager.get("team.joined", player)} ${target.coloredName()}${PluginVars.RESET}"
        )
    }

    private val teamMenuId = Menus.registerMenu { player, choice ->
        if (player != null) {
            onMenuChoose(player, choice)
        }
    }
    fun regNameInput(player: Player) {
        val regName = MenusManage.createTextInput(
            title = I18nManager.get("reg.title", player),
            desc = I18nManager.get("reg.desc", player),
            isNum = false,
            placeholder = ""
        ) { player, input ->
            val name = input.trim()
            val exists = DataManager.players.values().any { it.account == name }
            if (exists) {
                Call.announce(
                    player.con,
                    "${PluginVars.ERROR}${I18nManager.get("reg.name.duplicate", player)} ${PluginVars.RESET}"
                )
                return@createTextInput
            }
            Call.announce(
                player.con,
                "${PluginVars.SUCCESS}${I18nManager.get("reg.success", player)} $name${PluginVars.RESET}"
            )
            DataManager.registerPlayer(
                account = name,
                password = "",
                uuid = player.uuid(),
                lang = player.locale()
            )
            if (Vars.state.rules.pvp) {
                showTeamMenu(player)
            }
        }
        regName(player)
    }
    fun logNameInput(player: Player) {
        val logName = MenusManage.createTextInput(
            title = I18nManager.get("login.title", null),
            desc = I18nManager.get("login.name.desc", null),
            isNum = false,
            placeholder = ""
        ) { player, input ->
            val name = input.trim()
            val account = DataManager.players.values().find { it.account == name }
            if (account == null) {
                Call.announce(
                    player.con,
                    "${PluginVars.ERROR}${I18nManager.get("login.notFound", player)}${PluginVars.RESET}"
                )
                return@createTextInput
            }
            logPasswordInput(player, account)
        }
        logName(player)
    }

    fun logPasswordInput(player: Player, account: PlayerData) {
        MenusManage.createTextInput(
            title = I18nManager.get("login.password.title", player),
            desc = I18nManager.get("login.password.desc", player),
            isNum = true,
            placeholder = ""
        ) { p, pwd ->
            if (account.isLock) {
                Call.announce(p.con, "${PluginVars.ERROR}${I18nManager.get("isLock", p)}${PluginVars.RESET}")
                return@createTextInput
            }
            if (account.uuids.any { it == p.uuid() }) {
                Call.announce(p.con, "${PluginVars.ERROR}${I18nManager.get("login.already", p)}${PluginVars.RESET}")
                return@createTextInput
            }
            if (account.password != pwd) {
                Call.announce(p.con, "${PluginVars.ERROR}${I18nManager.get("login.pwdError", p)}${PluginVars.RESET}")
                return@createTextInput
            }
            account.uuids.add(p.uuid())
            DataManager.requestSave()
            Call.announce(p.con, "${PluginVars.SUCCESS}${I18nManager.get("login.success", p)}${PluginVars.RESET}")
        }(player)
    }

    fun showAuthMenu(player: Player) {
        val title = "${PluginVars.GRAY}${I18nManager.get("auth.title", player)}${PluginVars.RESET}"
        val desc = ""
        val buttons = arrayOf(
            arrayOf("${PluginVars.WHITE}${I18nManager.get("auth.register", player)}${PluginVars.RESET}"),
            arrayOf("${PluginVars.WHITE}${I18nManager.get("auth.login", player)}${PluginVars.RESET}")
        )


        val menuId = Menus.registerMenu { p, choice ->
            if (p == null) return@registerMenu
            when (choice) {
                0 -> regNameInput(p)
                1 -> logNameInput(p)
                else -> {}
            }
        }

        Call.menu(player.con, menuId, title, desc, buttons)
    }


    fun showRevertMenu(player: Player) {
        val revertPlayers = RevertBuild.getAllPlayersWithEdits()
        val btns = mutableListOf<MenuEntry>()

        btns.add(MenuEntry("${PluginVars.WHITE}${I18nManager.get("revert.all_players", player)}${PluginVars.RESET}") {
            MenusManage.createTextInput(
                title = I18nManager.get("revert.input_seconds.title", player),
                desc = I18nManager.get("revert.input_seconds.desc", player),
                isNum = true,
                placeholder = "180"
            ) { _, input ->
                val seconds = input.toIntOrNull()
                if (seconds == null || seconds < 1) {
                    Call.announce(
                        player.con,
                        "${PluginVars.WARN}${I18nManager.get("revert.invalid_input", player)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }

                RevertBuild.restoreAllPlayersEditsWithinSeconds(seconds)
                Call.announce(
                    player.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("revert.all_success", player)}${PluginVars.RESET}"
                )
            }(player)
        })

        revertPlayers.forEach { uuid ->
            val acc = DataManager.getPlayerDataByUuid(uuid)
            val name = acc?.account ?: uuid.take(8)

            btns.add(MenuEntry("${PluginVars.WHITE}$name${PluginVars.RESET}") {
                MenusManage.createTextInput(
                    title = I18nManager.get("revert.input_seconds.title", player),
                    desc = I18nManager.get("revert.input_seconds.desc", player),
                    isNum = true,
                    placeholder = "180"
                ) { _, input ->
                    val seconds = input.toIntOrNull()
                    if (seconds == null || seconds < 1) {
                        Call.announce(
                            player.con,
                            "${PluginVars.WARN}${I18nManager.get("revert.invalid_input", player)}${PluginVars.RESET}"
                        )
                        return@createTextInput
                    }

                    restorePlayerEditsWithinSeconds(uuid, seconds)

                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("revert.player_success", player)} $name${PluginVars.RESET}"
                    )
                }(player)
            })
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("revert.title", player)}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> "" },
            options = { _, _, _ -> btns }
        )(player, 1)
    }


    val uploadMapMenuId: Int = Menus.registerMenu { p, choice ->
        if (p == null) return@registerMenu
        when (choice) {
            0 -> Call.hideFollowUpMenu(p.con, uploadMapMenuId)
            1 -> {
                val url = "http://${DataManager.webUrl}:${DataManager.webPort}/?token=${TokensManager.create(p.uuid())}"
                Call.openURI(p.con, url)
            }
        }
    }

    fun showUploadMapMenu(player: Player) {
        val closeLabel = "${PluginVars.GRAY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"
        val openLabel = "${PluginVars.INFO}${I18nManager.get("open", player)}${PluginVars.RESET}"
        val buttons = arrayOf(arrayOf(closeLabel), arrayOf(openLabel))
        Call.menu(
            player.con,
            uploadMapMenuId,
            "${PluginVars.GRAY}${I18nManager.get("uploadMap", player)}${PluginVars.RESET}",
            "\n${PluginVars.WARN}${I18nManager.get("uploadDesc", player)}${PluginVars.RESET}\n",
            buttons
        )
    }

    fun showVoteKickPlayerMenu(player: Player) {
        val kickablePlayers = Groups.player.filter {
            it != player && !isCoreAdmin(it.uuid()) && it.con != null
        }
        if (kickablePlayers.isEmpty()) {
            Call.announce(
                player.con,
                "${PluginVars.WARN}${I18nManager.get("votekick.no_targets", player)}${PluginVars.RESET}"
            )
            return
        }
        val rows = kickablePlayers.map { target ->
            MenuEntry("${PluginVars.WHITE}${target.name()}${PluginVars.RESET}") {
                showConfirmMenu(player) {
                    beginVotekick(player, target)
                }
            }
        }
        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.WARN}${
                    I18nManager.get(
                        "votekick.menu.title",
                        player
                    )
                }${PluginVars.RESET}"
            },
            paged = false,
            desc = { _, _, _ -> I18nManager.get("votekick.menu.desc", player) },
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    fun beginVotekick(viewer: Player, target: Player) {
        val exclude = listOf(viewer, target)

        VoteManager.createGlobalVote(
            creator = viewer,
            excludePlayers = exclude
        ) { ok ->
            if (ok) {
                target.kick("")
                restorePlayerEditsWithinSeconds(target.uuid(), 200)
            }
        }

        Groups.player.each { p ->
            if (p != viewer && p != target && !p.dead() && isNormal(p.uuid())) {
                val title = "${PluginVars.WARN}${I18nManager.get("playerInfo.votekick.title", p)}${PluginVars.RESET}"
                val desc = "\uE817 ${PluginVars.GRAY}${viewer.name} ${
                    I18nManager.get("playerInfo.votekick.desc", p)
                } ${target.name()}${PluginVars.RESET}"

                val voteMenu = createConfirmMenu(
                    title = title,
                    desc = desc,
                    onResult = { player, choice ->
                        if (choice == 0) {
                            VoteManager.addVote(player.uuid())
                        }
                    }
                )

                voteMenu(p)
            }
        }
    }


    fun showConfirmMenu(player: Player, onConfirm: (Player) -> Unit) {
        val title = I18nManager.get("confirm.title", player)
        val desc = I18nManager.get("confirm.desc", player)

        createConfirmMenu(
            title = title,
            desc = desc,
            onResult = { p, choice ->
                if (choice == 0) onConfirm(p)
            }
        )(player)
    }

}
