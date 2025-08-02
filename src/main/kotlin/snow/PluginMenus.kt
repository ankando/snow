package plugin.snow

import arc.Events
import arc.math.Mathf
import arc.util.Strings
import arc.util.Time
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Weathers
import mindustry.core.NetClient
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Rules
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Iconc
import mindustry.gen.Player
import mindustry.io.SaveIO
import mindustry.net.Packets
import mindustry.net.WorldReloader
import mindustry.type.UnitType
import mindustry.type.Weather
import mindustry.ui.Menus
import mindustry.world.Block
import plugin.core.*
import plugin.core.MenusManage.createConfirmMenu
import plugin.core.PermissionManager.isBanned
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.RevertBuild.restorePlayerEditsWithinSeconds
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.pow

object PluginMenus {
    fun showGamesMenu(player: Player, page: Int = 1) {
        val games = listOf(
            "2048"                                                    to ::show2048game,
            I18nManager.get("game.lightsout",     player)            to ::showLightsOutGame,
            I18nManager.get("game.guessthenumber", player)           to ::showGuessGameMenu,
            I18nManager.get("game.gomoku",         player)           to ::showGomokuEntry,
            "中国象棋"                                                       to::showXiangqiEntry
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

    private enum class Piece(val id: Int) {
        SOLDIER(1), CANNON(2), HORSE(3), ELEPHANT(4), ADVISOR(5), ROOK(6), KING(7)
    }
    private data class Cell(val piece: Piece, val red: Boolean)
    private data class XCursor(var x: Int = 4, var y: Int = 10)
    private data class XInvite(val from: String, val time: Long = System.currentTimeMillis())

    private const val COLS = 9
    private const val ROWS = 11
    private fun river(y: Int) = y == 5
    private fun glyph(id: Int) = tileSprites[id] ?: tileSprites[0]!!

    private data class XqState(
        val red: String,
        val black: String,
        val board: Array<Array<Cell?>> = initBoard(),
        var redTurn: Boolean = true,
        var winner: Boolean? = null,
        val cursors: MutableMap<String, XCursor> = mutableMapOf(),
        var selected: Pair<Int, Int>? = null
    ) {
        private fun inBoard(x: Int, y: Int) = x in 0 until COLS && y in 0 until ROWS
        private fun cell(x: Int, y: Int) = if (inBoard(x, y)) board[y][x] else null
        private fun generalAlive(redSide: Boolean) = board.any { row -> row.any { it?.piece == Piece.KING && it.red == redSide } }

        fun selectOrMove(uid: String, cx: Int, cy: Int): Boolean {
            if (winner != null) return false
            val isRed = uid == red
            if (isRed != redTurn) return false
            val curCell = cell(cx, cy)
            val sel = selected
            return if (sel == null) {
                if (curCell == null || curCell.red != isRed) return false
                selected = cx to cy
                true
            } else {
                val (sx, sy) = sel
                val selCell = board[sy][sx] ?: return false
                val legal = legalMoves(sx, sy, selCell)
                when {
                    cx to cy == sel -> {
                        selected = null
                        true
                    }
                    cx to cy in legal -> {
                        board[cy][cx] = selCell
                        board[sy][sx] = null
                        selected = null
                        redTurn = !redTurn
                        if (!generalAlive(!isRed)) winner = isRed
                        true
                    }
                    curCell != null && curCell.red == isRed -> {
                        selected = cx to cy
                        true
                    }
                    else -> false
                }
            }
        }

        fun legalMoves(x: Int, y: Int, cell: Cell): List<Pair<Int, Int>> {
            if (!inBoard(x, y)) return emptyList()
            val redSide = cell.red
            val list = mutableListOf<Pair<Int, Int>>()

            fun canLand(nx: Int, ny: Int): Boolean {
                if (!inBoard(nx, ny)) return false
                if (river(ny)) return false
                val t = board[ny][nx]
                return t == null || t.red != redSide
            }
            fun add(nx: Int, ny: Int) { if (canLand(nx, ny)) list += nx to ny }

            when (cell.piece) {
                Piece.KING -> {
                    val palaceX = 3..5
                    val palaceY = if (redSide) 8..10 else 0..2
                    arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).forEach { (dx, dy) ->
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in palaceX && ny in palaceY) add(nx, ny)
                    }
                    var ny = y + if (redSide) -1 else 1
                    while (inBoard(x, ny)) {
                        val c = board[ny][x]
                        if (c != null) {
                            if (c.piece == Piece.KING && c.red != redSide) list += x to ny
                            break
                        }
                        ny += if (redSide) -1 else 1
                    }
                }
                Piece.ADVISOR -> {
                    val palaceX = 3..5
                    val palaceY = if (redSide) 8..10 else 0..2
                    arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1).forEach { (dx, dy) ->
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in palaceX && ny in palaceY) add(nx, ny)
                    }
                }
                Piece.ELEPHANT -> {
                    val dirs = arrayOf(2 to 2, 2 to -2, -2 to 2, -2 to -2)
                    for ((dx, dy) in dirs) {
                        val nx = x + dx
                        val ny = y + dy
                        val eyeX = x + dx / 2
                        val eyeY = y + dy / 2
                        if (!inBoard(nx, ny)) continue
                        if (river(ny)) continue
                        if (redSide && ny < 6) continue
                        if (!redSide && ny > 3) continue
                        if (board[eyeY][eyeX] == null) add(nx, ny)
                    }
                }
                Piece.HORSE -> {
                    val steps = arrayOf(
                        2 to 1, 1 to 2, -1 to 2, -2 to 1,
                        -2 to -1, -1 to -2, 1 to -2, 2 to -1
                    )
                    for ((dx, dy) in steps) {
                        val legX = x + if (dx.absoluteValue == 2) dx / 2 else 0
                        val legY = y + if (dy.absoluteValue == 2) dy / 2 else 0
                        val nx = x + dx
                        val ny = y + dy
                        if (!inBoard(legX, legY) || board[legY][legX] != null) continue
                        add(nx, ny)
                    }
                }

                Piece.ROOK -> {
                    for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        var nx = x + dx; var ny = y + dy
                        while (inBoard(nx, ny)) {
                            val t = board[ny][nx]
                            if (t == null) { if (!river(ny)) list += nx to ny }
                            else {
                                if (t.red != redSide && !river(ny)) list += nx to ny
                                break
                            }
                            nx += dx; ny += dy
                        }
                    }
                }

                Piece.CANNON -> {
                    for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                        var nx = x + dx; var ny = y + dy; var jumped = false
                        while (inBoard(nx, ny)) {
                            val t = board[ny][nx]
                            if (!jumped) {
                                if (t == null) { if (!river(ny)) list += nx to ny }
                                else jumped = true
                            } else {
                                if (t != null) {
                                    if (t.red != redSide && !river(ny)) list += nx to ny
                                    break
                                }
                            }
                            nx += dx; ny += dy
                        }
                    }
                }

                Piece.SOLDIER -> {
                    val forward = if (redSide) -1 else 1
                    val ny1 = y + forward
                    if (river(ny1)) {
                        val ny2 = ny1 + forward
                        add(x, ny2)
                    } else add(x, ny1)
                    if ((redSide && y <= 4) || (!redSide && y >= 6)) {
                        add(x + 1, y); add(x - 1, y)
                    }
                }
            }
            return list
        }

        companion object {
            private fun initBoard(): Array<Array<Cell?>> {
                fun put(b: Array<Array<Cell?>>, x: Int, y: Int, p: Piece, red: Boolean) { b[y][x] = Cell(p, red) }
                val b = Array(ROWS) { arrayOfNulls<Cell?>(COLS) }
                put(b, 0, 0, Piece.ROOK, false); put(b, 8, 0, Piece.ROOK, false)
                put(b, 1, 0, Piece.HORSE, false); put(b, 7, 0, Piece.HORSE, false)
                put(b, 2, 0, Piece.ELEPHANT, false); put(b, 6, 0, Piece.ELEPHANT, false)
                put(b, 3, 0, Piece.ADVISOR, false); put(b, 5, 0, Piece.ADVISOR, false)
                put(b, 4, 0, Piece.KING, false)
                put(b, 1, 2, Piece.CANNON, false); put(b, 7, 2, Piece.CANNON, false)
                for (x in 0 until COLS step 2) put(b, x, 3, Piece.SOLDIER, false)

                put(b, 0, 10, Piece.ROOK, true); put(b, 8, 10, Piece.ROOK, true)
                put(b, 1, 10, Piece.HORSE, true); put(b, 7, 10, Piece.HORSE, true)
                put(b, 2, 10, Piece.ELEPHANT, true); put(b, 6, 10, Piece.ELEPHANT, true)
                put(b, 3, 10, Piece.ADVISOR, true); put(b, 5, 10, Piece.ADVISOR, true)
                put(b, 4, 10, Piece.KING, true)
                put(b, 1, 8, Piece.CANNON, true); put(b, 7, 8, Piece.CANNON, true)
                for (x in 0 until COLS step 2) put(b, x, 7, Piece.SOLDIER, true)
                return b
            }
        }

        override fun equals(other: Any?) = other is XqState && redTurn == other.redTurn &&
                winner == other.winner && red == other.red && black == other.black &&
                board.contentDeepEquals(other.board) && cursors == other.cursors && selected == other.selected

        override fun hashCode() = arrayOf(redTurn, winner, red, black, board.contentDeepHashCode(), cursors, selected).contentHashCode()
    }

    private fun xKey(a: String, b: String) = if (a < b) a to b else b to a
    private val xInvites = mutableMapOf<String, MutableList<XInvite>>()
    private val xGames = mutableMapOf<Pair<String, String>, XqState>()
    private val xLastInvite = mutableMapOf<String, Long>()
    private const val X_COOLDOWN = 60_000L

    private val xMenuId: Int = Menus.registerMenu { pl, choice ->
        val p = pl ?: return@registerMenu
        val key = xGames.keys.find { it.first == p.uuid() || it.second == p.uuid() } ?: run {
            Call.hideFollowUpMenu(p.con, xMenuId); return@registerMenu
        }
        val st = xGames[key]!!
        val cur = st.cursors.getOrPut(p.uuid()) { if (p.uuid() == st.red) XCursor(4, 10) else XCursor(4, 0) }
        val isRed = p.uuid() == st.red
        val myTurn = (isRed && st.redTurn) || (!isRed && !st.redTurn)

        when (choice) {
            1, 3, 5, 7 -> if (myTurn) {
                when (choice) {
                    1 -> cur.y = (cur.y - 1 + ROWS) % ROWS
                    3 -> cur.x = (cur.x - 1 + COLS) % COLS
                    5 -> cur.x = (cur.x + 1) % COLS
                    7 -> cur.y = (cur.y + 1) % ROWS
                }
                showBoardX(p, st)
            }

            9 -> if (myTurn && st.selectOrMove(p.uuid(), cur.x, cur.y)) {
                val opp = if (p.uuid() == key.first) key.second else key.first
                showBoardX(p, st)
                Groups.player.find { it.uuid() == opp }?.let { showBoardX(it, st) }
                st.winner?.let { winRed ->
                    val oppPl = Groups.player.find { it.uuid() == opp }
                    oppPl?.let { Call.announce(it.con, if ((isRed && winRed) || (!isRed && !winRed)) "你输了！" else "你赢了！") }
                    xGames.remove(key)
                }
            }

            10 -> {
                showConfirmMenu(p) {
                    st.winner = !isRed
                    val opp = if (p.uuid() == key.first) key.second else key.first
                    Groups.player.find { it.uuid() == opp }?.let { showBoardX(it, st) }
                    xGames.remove(key)
                    Call.hideFollowUpMenu(p.con, xMenuId)
                }
                return@registerMenu
            }

            11 -> {
                Call.hideFollowUpMenu(p.con, xMenuId)
                return@registerMenu
            }

        }

        showBoardX(p, st)
    }


    fun showXiangqiEntry(pl: Player) {
        val uid = pl.uuid()
        xGames.keys.find { it.first == uid || it.second == uid }?.let {
            showBoardX(pl, xGames[it]!!)
            return
        }
        val now = System.currentTimeMillis()
        xInvites.values.forEach { it.removeIf { i -> now - i.time > 300_000 } }
        val sent = xInvites.values.any { it.any { i -> i.from == uid } }
        val rows = mutableListOf<MenuEntry>()
        xInvites[uid].orEmpty().forEach { inv ->
            val sender = Groups.player.find { it.uuid() == inv.from } ?: return@forEach
            if (xGames.none { it.key.first == sender.uuid() || it.key.second == sender.uuid() }) {
                rows += MenuEntry("${PluginVars.SECONDARY}${sender.name()}${PluginVars.RESET}") {
                    showConfirmMenu(pl) {
                        if (xGames.none { it.key.first == uid || it.key.second == uid || it.key.first == sender.uuid() || it.key.second == sender.uuid() })
                            startXiangqi(sender.uuid(), uid)
                        xInvites[uid]?.clear()
                    }
                }
            }
        }
        Groups.player.filter { it.uuid() != uid && xGames.none { g -> g.key.first == it.uuid() || g.key.second == it.uuid() } }
            .forEach { target ->
                rows += MenuEntry("${PluginVars.WHITE}${target.name()}${PluginVars.RESET}") {
                    val last = xLastInvite[uid] ?: 0
                    val delta = now - last
                    when {
                        sent -> Call.announce(pl.con, I18nManager.get("gomoku.inv.already", pl))
                        delta < X_COOLDOWN -> Call.announce(pl.con, "(${((X_COOLDOWN - delta) / 1000).toInt()}s)")
                        else -> {
                            showConfirmMenu(pl) {
                                if (xGames.none { it.key.first == uid || it.key.second == uid || it.key.first == target.uuid() || it.key.second == target.uuid() }) {
                                    val lst = xInvites.getOrPut(target.uuid()) { mutableListOf() }
                                    if (lst.none { it.from == uid }) lst += XInvite(uid)
                                    xLastInvite[uid] = now
                                    Call.announce(pl.con, I18nManager.get("gomoku.inv.sent", pl))
                                    createConfirmMenu(
                                        title = { "中国象棋邀请" },
                                        desc = { "${pl.name()} 邀请你下中国象棋" },
                                        onResult = { tgt, ch ->
                                            if (ch == 0 && xGames.none { it.key.first == uid || it.key.second == uid || it.key.first == tgt.uuid() || it.key.second == tgt.uuid() })
                                                startXiangqi(uid, tgt.uuid())
                                        }
                                    )(target)
                                }
                            }
                        }
                    }
                }
            }
        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "中国象棋 - 选择对手" },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> rows }
        )(pl, 1)
    }

    private fun startXiangqi(a: String, b: String) {
        if (xGames.any { it.key.first == a || it.key.second == a || it.key.first == b || it.key.second == b }) return
        val k = xKey(a, b)
        xGames[k] = XqState(red = b, black = a)
        xInvites.remove(a); xInvites.remove(b)
        xInvites.values.forEach { it.removeIf { i -> i.from == a || i.from == b } }
        val st = xGames[k]!!
        Groups.player.find { it.uuid() == a }?.let { showBoardX(it, st) }
        Groups.player.find { it.uuid() == b }?.let { showBoardX(it, st) }
    }

    private fun showBoardX(pl: Player, st: XqState) {
        val cur = st.cursors.getOrPut(pl.uuid()) { XCursor() }
        val isRed = pl.uuid() == st.red
        val myTurn = (isRed && st.redTurn) || (!isRed && !st.redTurn)
        val sel = st.selected
        val legal = if (sel != null) {
            val (sx, sy) = sel
            if (sx in 0 until COLS && sy in 0 until ROWS) {
                val c = st.board[sy][sx]
                if (c != null && c.red == isRed) st.legalMoves(sx, sy, c) else emptyList()
            } else emptyList()
        } else emptyList()
        val sb = StringBuilder()
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                val pos = x to y
                val cell = st.board[y][x]
                val base = when {
                    cell != null -> glyph(cell.piece.id)
                    river(y) -> ""
                    else -> glyph(0)
                }
                val txt = when {
                    pos == cur.x to cur.y && pos == sel -> "${PluginVars.SECONDARY}$base${PluginVars.RESET}"
                    pos == cur.x to cur.y && pos in legal -> "${PluginVars.SECONDARY}$base${PluginVars.RESET}"
                    pos == cur.x to cur.y -> "${PluginVars.INFO}$base${PluginVars.RESET}"
                    pos == sel -> "${PluginVars.WARN}$base${PluginVars.RESET}"
                    pos in legal -> "${PluginVars.GOLD}$base${PluginVars.RESET}"
                    cell != null && cell.red -> "${PluginVars.RED}$base${PluginVars.RESET}"
                    cell != null -> "${PluginVars.BLUE}$base${PluginVars.RESET}"
                    river(y) -> "${PluginVars.GRAY}$base${PluginVars.RESET}"
                    else -> base
                }
                sb.append(txt)
            }
            sb.append('\n')
        }
        fun b(t: String, en: Boolean) = if (en) "${PluginVars.WHITE}$t${PluginVars.RESET}" else "${PluginVars.SECONDARY}$t${PluginVars.RESET}"
        val btns = arrayOf(
            arrayOf("", b("\uE804", myTurn), ""),
            arrayOf(b("\uE802", myTurn), "", b("\uE803", myTurn)),
            arrayOf("", b("\uE805", myTurn), ""),
            arrayOf(b("选择", myTurn)),
            arrayOf(b("投降", true)),
            arrayOf(b("退出", true))
        )
        val info = when {
            st.winner == null && myTurn -> "轮到你"
            st.winner == null -> "等待对手"
            st.winner == isRed -> "你赢了！"
            else -> "你输了！"
        }
        Call.followUpMenu(pl.con, xMenuId, "中国象棋", "\n$info\n\n$sb", btns)
    }

    private enum class Stone(val glyph: String){ EMPTY("\uF8F7"), BLACK("\uF7C9"), WHITE("\uF8A6") }
    private data class Cursor(var x: Int = 10, var y: Int = 10)
    private data class Invite(val from: String, val time: Long = System.currentTimeMillis())
    private data class GomokuState(
        val white: String, val black: String,
        val board: Array<Array<Stone>> = Array(21) { Array(21) { Stone.EMPTY } },
        var turnBlack: Boolean = true,
        var winner: Stone? = null,
        val cursors: MutableMap<String, Cursor> = mutableMapOf()
    ) {
        fun place(x: Int, y: Int, s: Stone, self: String): Boolean {
            if (board[y][x] != Stone.EMPTY || winner != null) return false
            board[y][x] = s
            if (checkFive(x, y, s)) {
                winner = s
                val gameKey = key(self, if (self == white) black else white)
                games.remove(gameKey)
            }
            return true
        }

        private fun checkFive(x: Int, y: Int, s: Stone): Boolean {
            fun count(dx: Int, dy: Int): Int {
                var c = 0; var nx = x + dx; var ny = y + dy
                while (nx in 0 until 21 && ny in 0 until 21 && board[ny][nx] == s) {
                    c++; nx += dx; ny += dy
                }
                return c
            }
            val dirs = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
            return dirs.any { (dx, dy) -> 1 + count(dx, dy) + count(-dx, -dy) >= 5 }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as GomokuState

            if (turnBlack != other.turnBlack) return false
            if (white != other.white) return false
            if (black != other.black) return false
            if (!board.contentDeepEquals(other.board)) return false
            if (winner != other.winner) return false
            if (cursors != other.cursors) return false

            return true
        }

        override fun hashCode(): Int {
            var result = turnBlack.hashCode()
            result = 31 * result + white.hashCode()
            result = 31 * result + black.hashCode()
            result = 31 * result + board.contentDeepHashCode()
            result = 31 * result + (winner?.hashCode() ?: 0)
            result = 31 * result + cursors.hashCode()
            return result
        }
    }

    private fun key(u1: String, u2: String) = if (u1 < u2) u1 to u2 else u2 to u1
    private const val BOARD = 21
    private val invites = mutableMapOf<String, MutableList<Invite>>()
    private val games = mutableMapOf<Pair<String, String>, GomokuState>()
    private const val INV_PREFIX = "${PluginVars.SECONDARY}\uE861${PluginVars.RESET}"
    private val lastInviteTimes = mutableMapOf<String, Long>()
    private const val INVITE_COOLDOWN = 60_000L
    private val gomokuMenuId: Int = Menus.registerMenu { p, choice ->
        val me = p ?: return@registerMenu
        val stateKey = games.keys.find { it.first == me.uuid() || it.second == me.uuid() } ?: run {
            Call.hideFollowUpMenu(me.con, gomokuMenuId)
            return@registerMenu
        }

        val state = games[stateKey]!!

        if (state.winner != null) {
            Call.hideFollowUpMenu(me.con, gomokuMenuId)
            return@registerMenu
        }

        val cur = state.cursors.getOrPut(me.uuid()) { Cursor() }
        val meBlack = me.uuid() == state.black
        val myStone = if (meBlack) Stone.BLACK else Stone.WHITE
        val myTurn = (state.turnBlack && meBlack) || (!state.turnBlack && !meBlack)

        var needBroadcast = false

        when (choice) {
            1, 3, 5, 7 -> if (myTurn) {
                when (choice) {
                    1 -> cur.y = (cur.y - 1 + BOARD) % BOARD
                    3 -> cur.x = (cur.x - 1 + BOARD) % BOARD
                    5 -> cur.x = (cur.x + 1) % BOARD
                    7 -> cur.y = (cur.y + 1) % BOARD
                }
            }
            9 -> if (myTurn) {
                if (state.place(cur.x, cur.y, myStone, me.uuid())) {
                    state.turnBlack = !state.turnBlack
                    needBroadcast = true
                }
            }
            10 -> {
                showConfirmMenu(me) {
                    games.remove(stateKey)
                    Call.hideFollowUpMenu(me.con, gomokuMenuId)
                }
                return@registerMenu
            }
            11 -> {
                Call.hideFollowUpMenu(me.con, gomokuMenuId)
                return@registerMenu
            }
        }

        if (needBroadcast) {
            val oppUuid = if (me.uuid() == stateKey.first) stateKey.second else stateKey.first
            showGomokuBoard(me, state)
            Groups.player.find { it.uuid() == oppUuid }?.let { showGomokuBoard(it, state) }
        } else {
            showGomokuBoard(me, state)
        }
    }


    fun showGomokuEntry(player: Player) {
        val uid = player.uuid()

        games.keys.find { it.first == uid || it.second == uid }?.let {
            showGomokuBoard(player, games[it]!!)
            return
        }

        val now = System.currentTimeMillis()
        invites.values.forEach { it.removeIf { inv -> now - inv.time > 300_000 } }

        val alreadySent = invites.values.any { lst -> lst.any { it.from == uid } }
        val rows = mutableListOf<MenuEntry>()

        invites[uid].orEmpty().forEach { inv ->
            val sender = Groups.player.find { it.uuid() == inv.from }

            if (sender != null && games.keys.none { it.first == sender.uuid() || it.second == sender.uuid() }) {
                rows += MenuEntry("$INV_PREFIX${PluginVars.SECONDARY} ${sender.name()}${PluginVars.RESET}") {
                    if (games.keys.any { it.first == uid || it.second == uid }) return@MenuEntry
                    showConfirmMenu(player) {
                        if (invites.isNotEmpty()) {
                            startGame(sender.uuid(), uid)
                        }
                        invites[uid]?.clear()
                    }
                }
            }
        }

        Groups.player
            .filter { it.uuid() != uid && games.keys.none { g -> g.first == it.uuid() || g.second == it.uuid() } }
            .forEach { p ->
                rows += MenuEntry("${PluginVars.WHITE}${p.name()}${PluginVars.RESET}") {
                    val last = lastInviteTimes[uid] ?: 0
                    val delta = now - last
                    if (alreadySent) {
                        Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("gomoku.inv.already", player)}${PluginVars.RESET}")
                    } else if (delta < INVITE_COOLDOWN) {
                        val wait = ((INVITE_COOLDOWN - delta) / 1000).toInt()
                        Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("gomoku.inv.cooldown", player)} ($wait s)${PluginVars.RESET}")
                    } else {
                        showConfirmMenu(player) {
                            if (games.keys.any { it.first == uid || it.second == uid }) return@showConfirmMenu
                            val lst = invites.getOrPut(p.uuid()) { mutableListOf() }
                            if (lst.none { it.from == uid }) lst += Invite(uid)
                            lastInviteTimes[uid] = now
                            Call.announce(player.con, "${PluginVars.INFO}${I18nManager.get("gomoku.inv.sent", player)}${PluginVars.RESET}")

                            val title = "${PluginVars.GRAY}${I18nManager.get("gomoku.inv.title", p)}${PluginVars.RESET}"
                            val desc = "${PluginVars.SECONDARY}${player.name()} ${I18nManager.get("gomoku.inv.desc", p)}${PluginVars.RESET}"

                            createConfirmMenu(
                                title = { title },
                                desc = { desc },
                                onResult = { target, choice ->
                                    if (choice == 0 && games.none { it.key.first == target.uuid() || it.key.second == target.uuid() }) {
                                        startGame(player.uuid(), target.uuid())
                                    }
                                }
                            )(p)
                        }
                    }
                }
            }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("gomoku.list", player)}${PluginVars.RESET}" },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, 1)
    }


    private fun startGame(inviter: String, invitee: String) {
        val k = key(inviter, invitee)
        games[k] = GomokuState(white = invitee, black = inviter)
        invites.remove(inviter)
        invites.remove(invitee)
        invites.values.forEach { it.removeIf { inv -> inv.from == inviter || inv.from == invitee } }
        val state = games[k]!!
        Groups.player.find { it.uuid() == inviter }?.let { showGomokuBoard(it, state) }
        Groups.player.find { it.uuid() == invitee }?.let { showGomokuBoard(it, state) }
    }


    private fun showGomokuBoard(p: Player, state: GomokuState) {
        val cur = state.cursors.getOrPut(p.uuid()) { Cursor() }
        val meBlack = p.uuid() == state.black
        val myStone = if (meBlack) Stone.BLACK else Stone.WHITE
        val myTurn = (state.turnBlack && meBlack) || (!state.turnBlack && !meBlack) && state.winner == null
        val boardTxt = buildString {
            repeat(BOARD) { y ->
                repeat(BOARD) { x ->
                    val stone = state.board[y][x]
                    val isCursor = (x == cur.x && y == cur.y)

                    append(
                        when {
                            isCursor && stone != Stone.EMPTY -> "${PluginVars.SECONDARY}${stone.glyph}${PluginVars.RESET}" // 光标在已有棋子上，改变颜色
                            stone != Stone.EMPTY -> stone.glyph
                            isCursor -> ""
                            else -> Stone.EMPTY.glyph
                        }
                    )
                }
                append('\n')
            }
        }


        fun b(t: String, e: Boolean) = if (e) "${PluginVars.WHITE}$t${PluginVars.RESET}"
        else "${PluginVars.SECONDARY}$t${PluginVars.RESET}"

        val buttons = arrayOf(
            arrayOf("", b("\uE804", myTurn), ""),
            arrayOf(b("\uE802", myTurn), "", b("\uE803", myTurn)),
            arrayOf("", b("\uE805", myTurn), ""),
            arrayOf(b(I18nManager.get("gomoku.select", p), myTurn)),
            arrayOf(b(I18nManager.get("gomoku.end", p), true)),
            arrayOf(b(I18nManager.get("gomoku.exit", p), true))
        )

        val info = when {
            state.winner == myStone -> I18nManager.get("gomoku.win", p)
            state.winner != null -> I18nManager.get("gomoku.lose", p)
            myTurn -> I18nManager.get("gomoku.yourturn", p)
            else -> I18nManager.get("gomoku.wait", p)
        }

        Call.followUpMenu(
            p.con, gomokuMenuId,
            "${PluginVars.GRAY}${I18nManager.get("gomoku.title", p)}${PluginVars.RESET}",
            "\n${PluginVars.INFO}$info${PluginVars.RESET}\n\n$boardTxt",
            buttons
        )
    }


    private enum class Hint { LOW, HIGH, NONE }

    private data class GuessState(
        val answer: Int = Mathf.random(1, 999),
        var attempts: Int = 0,
        var hint: Hint = Hint.NONE
    )

    private val guessStates = mutableMapOf<String, GuessState>()

    fun showGuessGameMenu(player: Player) {
        val uuid  = player.uuid()
        val state = guessStates.getOrPut(uuid) { GuessState() }

        val desc = buildString {
            append(I18nManager.get("guess.desc", player))
            when (state.hint) {
                Hint.LOW  -> append("\n\n${I18nManager.get("guess.low",  player)}")
                Hint.HIGH -> append("\n\n${I18nManager.get("guess.high", player)}")
                Hint.NONE -> {}
            }
            append("\n\n${I18nManager.get("guess.attempts", player)}: ${state.attempts}")
        }

        MenusManage.createTextInput(
            title = I18nManager.get("guess.title", player),
            desc = "$desc\n\n(1-1000)",
            placeholder = "",
            isNum = true,
            maxChars = 3
        ) { _, input ->
            val number = input.toIntOrNull()

            if (number == null || number !in 1..1000) {
                guessStates.remove(uuid)
                return@createTextInput
            }

            val stateNow = guessStates[uuid] ?: return@createTextInput
            stateNow.attempts++

            stateNow.hint = when {
                number < stateNow.answer -> Hint.LOW
                number > stateNow.answer -> Hint.HIGH
                else -> {
                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("guess.win", player)} ${stateNow.attempts}${PluginVars.RESET}"
                    )
                    guessStates.remove(uuid)
                    return@createTextInput
                }
            }

            showGuessGameMenu(player)
        }(player)
    }

    private data class LightsOutGameStateData(
        val lightGrid: Array<BooleanArray>,
        var stepCounter: Int = 0,
        var pendingHintCellIndex: Int = -1
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as LightsOutGameStateData

            if (stepCounter != other.stepCounter) return false
            if (pendingHintCellIndex != other.pendingHintCellIndex) return false
            if (!lightGrid.contentDeepEquals(other.lightGrid)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = stepCounter
            result = 31 * result + pendingHintCellIndex
            result = 31 * result + lightGrid.contentDeepHashCode()
            return result
        }
    }

    private val lightsOutGameStatesMap = mutableMapOf<String, LightsOutGameStateData>()


    private const val GRID_SIDE_LENGTH = 5

    private const val BTN_INDEX_REFRESH = GRID_SIDE_LENGTH * GRID_SIDE_LENGTH      // 25
    private const val BTN_INDEX_CLOSE   = GRID_SIDE_LENGTH * GRID_SIDE_LENGTH + 1  // 26
    private const val BTN_INDEX_HINT    = GRID_SIDE_LENGTH * GRID_SIDE_LENGTH + 2  // 27

    private const val CHAR_LIGHT_ON  = "\uE853"
    private const val CHAR_LIGHT_OFF = "\uE86C"

    fun showLightsOutGame(player: Player) {
        val playerUuid = player.uuid()
        val playerState = lightsOutGameStatesMap.getOrPut(playerUuid) {
            LightsOutGameStateData(generateRandomSolvableGrid())
        }

        val buttonRows = Array(GRID_SIDE_LENGTH + 1) { arrayOfNulls<String>(GRID_SIDE_LENGTH) }

        for (row in 0 until GRID_SIDE_LENGTH) {
            for (col in 0 until GRID_SIDE_LENGTH) {
                val cellIdx = row * GRID_SIDE_LENGTH + col
                val charBase = if (playerState.lightGrid[row][col]) CHAR_LIGHT_ON else CHAR_LIGHT_OFF

                buttonRows[row][col] =
                    when {
                        cellIdx == playerState.pendingHintCellIndex ->
                            "${PluginVars.GOLD}$charBase[]"
                        playerState.lightGrid[row][col] ->
                            "${PluginVars.WHITE}$charBase${PluginVars.RESET}"
                        else ->
                            "${PluginVars.SECONDARY}$charBase${PluginVars.RESET}"
                    }
            }
        }

        buttonRows[GRID_SIDE_LENGTH][0] = "${PluginVars.WHITE}\uE86F${PluginVars.RESET}"
        buttonRows[GRID_SIDE_LENGTH][1] = "${PluginVars.SECONDARY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"
        buttonRows[GRID_SIDE_LENGTH][2] = "${PluginVars.WHITE}\uE88E${PluginVars.RESET}"

        val description = buildString {
            append("\n${PluginVars.WHITE}${I18nManager.get("steps", player)}: ${playerState.stepCounter}${PluginVars.RESET}\n")
            if (playerState.lightGrid.all { it.all(Boolean::not) })
                append("\n\n${PluginVars.WHITE}${I18nManager.get("game.victory", player)}${PluginVars.RESET}\n")
        }

        Call.followUpMenu(
            player.con,
            lightsOutMenuId,
            "${PluginVars.WHITE}${I18nManager.get("game.lightsout", player)}${PluginVars.RESET}",
            description,
            buttonRows
        )
    }

    private fun generateRandomSolvableGrid(): Array<BooleanArray> {
        while (true) {
            val grid = Array(GRID_SIDE_LENGTH) { BooleanArray(GRID_SIDE_LENGTH) }
            repeat(10 + Mathf.random(14)) {
                toggleCellAndNeighbors(
                    grid,
                    Mathf.random(GRID_SIDE_LENGTH - 1),
                    Mathf.random(GRID_SIDE_LENGTH - 1)
                )
            }
            if (grid.any { it.any(Boolean::not) }) return grid
        }
    }

    private fun toggleCellAndNeighbors(grid: Array<BooleanArray>, x: Int, y: Int) {
        val dirs = arrayOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for ((dx, dy) in dirs) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until GRID_SIDE_LENGTH && ny in 0 until GRID_SIDE_LENGTH)
                grid[ny][nx] = !grid[ny][nx]
        }
    }

    private fun calcNextHintIndex(grid: Array<BooleanArray>): Int {
        val n = GRID_SIDE_LENGTH * GRID_SIDE_LENGTH
        val aug = Array(n) { BooleanArray(n + 1) }

        for (y in 0 until GRID_SIDE_LENGTH)
            for (x in 0 until GRID_SIDE_LENGTH) {
                val r = y * GRID_SIDE_LENGTH + x
                for ((dx, dy) in arrayOf(0 to 0, 1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = x + dx; val ny = y + dy
                    if (nx in 0 until GRID_SIDE_LENGTH && ny in 0 until GRID_SIDE_LENGTH)
                        aug[r][ny * GRID_SIDE_LENGTH + nx] = true
                }
                aug[r][n] = grid[y][x]
            }

        var row = 0
        for (col in 0 until n) {
            var pivot = row
            while (pivot < n && !aug[pivot][col]) pivot++
            if (pivot == n) continue
            val tmp = aug[pivot]; aug[pivot] = aug[row]; aug[row] = tmp
            for (r in 0 until n) if (r != row && aug[r][col])
                for (c in col..n) aug[r][c] = aug[r][c] xor aug[row][c]
            if (++row == n) break
        }

        val xVec = BooleanArray(n)
        for (r in n - 1 downTo 0) {
            val lead = aug[r].indexOfFirst { it }
            if (lead == -1 || lead == n) continue
            var rhs = aug[r][n]
            for (k in lead + 1 until n) if (aug[r][k] && xVec[k]) rhs = rhs xor true
            xVec[lead] = rhs
        }
        return xVec.indexOfFirst { it }
    }

    private val lightsOutMenuId:Int = Menus.registerMenu { player, choice ->
        if (choice < 0) return@registerMenu
        val state = lightsOutGameStatesMap[player.uuid()] ?: return@registerMenu

        when (choice) {
            BTN_INDEX_REFRESH ->
                lightsOutGameStatesMap[player.uuid()] = LightsOutGameStateData(generateRandomSolvableGrid())

            BTN_INDEX_CLOSE -> {
                Call.hideFollowUpMenu(player.con, lightsOutMenuId)
                return@registerMenu
            }

            BTN_INDEX_HINT ->
                state.pendingHintCellIndex = calcNextHintIndex(state.lightGrid)

            else -> {
                state.pendingHintCellIndex = -1
                toggleCellAndNeighbors(state.lightGrid, choice % GRID_SIDE_LENGTH, choice / GRID_SIDE_LENGTH)
                state.stepCounter++
            }
        }
        showLightsOutGame(player)
    }
    private val game2048States = mutableMapOf<String, Array<IntArray>>()

    private const val WIN_TILE = 11
    private val tileSprites = mapOf(
        0 to "\uF8F7",  // empty
        1 to "\uF861",  // 2
        2 to "\uF85B",  // 4
        3 to "\uF85E",  // 8
        4 to "\uF85C",  // 16
        5 to "\uF85A",  // 32
        6 to "\uF857",  // 64
        7 to "\uF858",  // 128
        8 to "\uF856",  // 256
        9 to "\uF7BE",  // 512
        10 to "\uF688", // 1024
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
            arrayOf(
                "${PluginVars.SECONDARY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"
            ),
            arrayOf("${PluginVars.SECONDARY}${PluginVars.RESET}")
        )


        val maxTile = grid.maxOf { row -> row.maxOrNull() ?: 0 }
        val score = 2.0.pow(maxTile).toInt()

        if (!canMove(grid) && grid.all { row -> row.none { it == 0 } }) {
            Call.followUpMenu(
                player.con, menu2048Id, "2048",
                "\n${PluginVars.WHITE}${I18nManager.get("score", player)}: $score\n\n${I18nManager.get("game.defeat", player)}\n\n${renderGrid(grid)}${PluginVars.RESET}",
                buttons
            )
            return
        }

        if (grid.any { row -> row.any { it >= WIN_TILE } }) {
            Call.followUpMenu(
                player.con, menu2048Id, "2048",
                "\n${PluginVars.WHITE}${I18nManager.get("score", player)}: $score\n\n${I18nManager.get("game.victory", player)}\n\n${renderGrid(grid)}${PluginVars.RESET}",
                buttons
            )
            return
        }

        Call.followUpMenu(
            player.con,
            menu2048Id,
            "2048",
            "\n${PluginVars.WHITE}${I18nManager.get("score", player)}: $score\n\n${renderGrid(grid)}${PluginVars.RESET}",
            buttons
        )

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
    private const val IDX_REFRESH = 10

    private val menu2048Id: Int = Menus.registerMenu { player, choice ->
        if (choice < 0) return@registerMenu
        if (choice == IDX_CLOSE) {
            Call.hideFollowUpMenu(player.con, menu2048Id)
            return@registerMenu
        }
        if (choice == IDX_REFRESH) {
            showConfirmMenu(player) {
                game2048States.remove(player.uuid())
                show2048game(player)
            }
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

    private val snapshotFolder: arc.files.Fi = Vars.saveDirectory.child("snapshots")

    fun showSnapshotMenu(player: Player, page: Int = 1) {
        snapshotFolder.mkdirs()

        val rows = mutableListOf<MenuEntry>()

        rows += MenuEntry("${PluginVars.WHITE}${I18nManager.get("snapshot.add", player)}${PluginVars.RESET}") {
            val ts = System.currentTimeMillis()
            val file = snapshotFolder.child("$ts.msav")
            if (!isCoreAdmin(player.uuid())) {
                showConfirmMenu(player) {
                    if (VoteManager.globalVoteSession != null) {
                        Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                        return@showConfirmMenu
                    }
                    VoteManager.createGlobalVote(creator = player) { ok ->
                        if (ok && Vars.state.isGame) {
                            SaveIO.save(file)
                            Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("snapshot.saved", player)}${PluginVars.RESET}")
                        }
                    }
                    Groups.player.forEach { p ->
                        if (p != player && !isBanned(p.uuid())) {
                            val t = "${PluginVars.INFO}${I18nManager.get("snapshot.vote.title", p)}${PluginVars.RESET}"
                            val d = "${PluginVars.GRAY}${player.name} ${I18nManager.get("snapshot.vote.desc", p)}${PluginVars.RESET}"
                            createConfirmMenu(
                                title = { t },
                                desc = { d },
                                canStop = isCoreAdmin(p.uuid()),
                                onResult = { pl, choice -> if (choice == 0) VoteManager.addVote(pl.uuid()); if (choice == 2) {
                                    VoteManager.clearVote()
                                    Call.announce("${PluginVars.GRAY}${p.name} ${I18nManager.get("vote.cancel", p)}${PluginVars.RESET}")
                                } }
                            )(p)
                        }
                    }
                }
            } else {
                SaveIO.save(file)
                Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("snapshot.saved", player)}${PluginVars.RESET}")
                showSnapshotMenu(player)
            }
        }

        val mapEntries = snapshotFolder.list().filter { it.extension() == "msav" }
            .mapNotNull { file ->
                val ts = file.nameWithoutExtension().toLongOrNull() ?: return@mapNotNull null
                val mapName = runCatching { SaveIO.getMeta(file).map.name() }.getOrElse { "Unknown" }
                Triple(mapName, ts, file)
            }
            .groupBy { it.first }
            .mapValues { (_, list) -> list.sortedByDescending { it.second } }
            .toList()
            .sortedByDescending { it.second.firstOrNull()?.second ?: 0L }

        mapEntries.forEach { (mapName, group) ->
            group.forEachIndexed { i, (_, _, file) ->
                val label = "${PluginVars.WHITE}$mapName#${i + 1}${PluginVars.RESET}"
                rows += MenuEntry(label) { p -> showSnapshotOptionsMenu(p, file, mapName, i + 1) }
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, pageNum, totalPages, _ ->
                "${PluginVars.GRAY}${I18nManager.get("snapshot.title", player)} ${pageNum}/${totalPages}${PluginVars.RESET}"
            },
            desc = { _, _, _ -> "" },
            paged = true,
            options = { _, _, _ -> rows }
        )(player, page)

    }

    fun showSnapshotOptionsMenu(player: Player, file: arc.files.Fi, mapName: String, index: Int) {
        val isAdmin = isCoreAdmin(player.uuid())
        val normalCount = Groups.player.count { !isBanned(it.uuid()) }
        val strong = PluginVars.INFO
        val weak = PluginVars.SECONDARY

        val title = "${PluginVars.GRAY}$mapName#$index${PluginVars.RESET}"
        val desc = ""

        val btnVote = MenuEntry("${PluginVars.WHITE}${I18nManager.get("snapshot.vote", player)}${PluginVars.RESET}") {
            if (normalCount <= 1) {
                loadSnapshot(file)
                Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("rtv.changed_alone", player)}${PluginVars.RESET}")
            } else {
                if ((DataManager.getPlayerDataByUuid(player.uuid())?.score ?: 0) < 20 && !isAdmin) {
                    Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("noPoints", player)}${PluginVars.RESET}")
                    return@MenuEntry
                }


                showConfirmMenu(player) {
                    if (VoteManager.globalVoteSession != null) {
                        Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                        return@showConfirmMenu
                    }
                    VoteManager.createGlobalVote(creator = player) { ok ->
                        if (ok && Vars.state.isGame) loadSnapshot(file)
                    }
                    Groups.player.each { p ->
                        if (p != player && !isBanned(p.uuid())) {
                            val t = "${PluginVars.INFO}${I18nManager.get("rtv.title", p)}${PluginVars.RESET}"
                            val d = "\uE827 ${PluginVars.GRAY}${player.name} ${I18nManager.get("snapshot.rtv.desc", p)} $mapName#$index${PluginVars.RESET}"
                            val menu = createConfirmMenu(
                                title = { t },
                                desc = { d },
                                canStop = isCoreAdmin(p.uuid()),
                                onResult = { pl, choice ->
                                    if (choice == 0) VoteManager.addVote(pl.uuid()); if (choice == 2) {
                                    VoteManager.clearVote()
                                    Call.announce("${PluginVars.GRAY}${p.name} ${I18nManager.get("vote.cancel", p)}${PluginVars.RESET}")
                                }
                                }
                            )
                            menu(p)
                        }
                    }
                }
            }
        }

        val btnChange = MenuEntry("${if (isAdmin) strong else weak}${I18nManager.get("snapshot.change", player)}${PluginVars.RESET}") {
            if (!isAdmin) {
                Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}")
                return@MenuEntry
            }
            showConfirmMenu(player) {
                loadSnapshot(file)
            }
        }

        val btnDelete = MenuEntry("${if (isAdmin) strong else weak}${I18nManager.get("snapshot.delete", player)}${PluginVars.RESET}") {
            if (!isAdmin) {
                Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}")
                return@MenuEntry
            }
            showConfirmMenu(player) {
                if (file.exists() && file.delete()) {
                    Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("snapshot.deleted", player)}${PluginVars.RESET}")
                } else {
                    Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("snapshot.delete_failed", player)}${PluginVars.RESET}")
                }
                showSnapshotMenu(player)
            }
        }

        val rows = mutableListOf(btnVote)
        if (isAdmin) {
            rows += btnChange
            rows += btnDelete
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> title },
            desc = { _, _, _ -> desc },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    private fun loadSnapshot(file: arc.files.Fi) {
        if (!file.exists()) return
        try {
            if (Vars.state.isGame) {
                Groups.player.each { it.kick(Packets.KickReason.serverRestarting) }
                Vars.state.set(mindustry.core.GameState.State.menu)
                Vars.net.closeServer()
            }
            RevertBuild.clearAll()
            VoteManager.clearVote()
            val reloader = WorldReloader()
            reloader.begin()
            SaveIO.load(file)
            Vars.state.set(mindustry.core.GameState.State.playing)
            Vars.netServer.openServer()
            reloader.end()
        } catch (_: Exception) { }
    }

    private data class WeatherEditKey(val uuid: String, val weather: Weather)

    private val weatherEditStates = ConcurrentHashMap<WeatherEditKey, Weather.WeatherEntry>()
    private val weatherEditContext = ConcurrentHashMap<Int, Weather>()

    private val weatherConfigMenuId: Int = Menus.registerMenu { pl, ch ->
        val player = pl ?: return@registerMenu
        val weather = weatherEditContext[player.id] ?: return@registerMenu
        val key = WeatherEditKey(player.uuid(), weather)
        val entry = weatherEditStates[key] ?: return@registerMenu
        val refresh = { showWeatherConfigMenu(player, weather) }

        when (ch) {
            0 -> Call.hideFollowUpMenu(player.con, weatherConfigMenuId)
            1 -> if (!entry.always) promptNum(player) { entry.minDuration = it; refresh() }
            2 -> if (!entry.always) promptNum(player) { entry.maxDuration = it; refresh() }
            3 -> if (!entry.always) promptNum(player) { entry.minFrequency = it; refresh() }
            4 -> if (!entry.always) promptNum(player) { entry.maxFrequency = it; refresh() }
            5 -> { entry.always = !entry.always; refresh() }
            6 -> {
                val rules = Vars.state.rules
                for (i in rules.weather.size - 1 downTo 0) {
                    if (rules.weather[i].weather == weather) {
                        rules.weather.remove(i)
                    }
                }
                val newEntry = Weather.WeatherEntry().apply {
                    this.weather = entry.weather
                    this.intensity = entry.intensity
                    this.always = entry.always
                    this.minDuration = entry.minDuration
                    this.maxDuration = entry.maxDuration
                    this.minFrequency = entry.minFrequency
                    this.maxFrequency = entry.maxFrequency
                }
                rules.weather.add(newEntry)
                Call.setRules(rules)
                Call.hideFollowUpMenu(player.con, weatherConfigMenuId)
            }
        }
    }

    fun showWeatherMenu(player: Player) {
        val rows = listOf(
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("weather.all", player)}${PluginVars.RESET}") { showAllWeatherMenu(player) },
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("weather.snow", player)}${PluginVars.RESET}") { showWeatherConfigMenu(player, Weathers.snow) },
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("weather.rain", player)}${PluginVars.RESET}") { showWeatherConfigMenu(player, Weathers.rain) },
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("weather.sandstorm", player)}${PluginVars.RESET}") { showWeatherConfigMenu(player, Weathers.sandstorm) },
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("weather.sporestorm", player)}${PluginVars.RESET}") { showWeatherConfigMenu(player, Weathers.sporestorm) },
            MenuEntry("${PluginVars.WHITE}${I18nManager.get("weather.fog", player)}${PluginVars.RESET}") { showWeatherConfigMenu(player, Weathers.fog) }
        )

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("weather.title", player)}${PluginVars.RESET}" },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    private fun showAllWeatherMenu(player: Player) {
        val rules = Vars.state.rules
        val list = mutableListOf<MenuEntry>()

        list += MenuEntry("${PluginVars.WARN}${I18nManager.get("weather.removeAll", player)}${PluginVars.RESET}") {
            rules.weather.clear()
            Groups.weather.clear()
            Call.setRules(rules)
        }

        rules.weather.forEach { w ->
            val txt = "${PluginVars.WHITE}${w.weather.localizedName}${PluginVars.RESET}"
            list += MenuEntry(txt) {
                createConfirmMenu(
                    title = { I18nManager.get("weather.confirm.title", it) },
                    desc = { "" },
                    onResult = { _, r ->
                        if (r == 0) {
                            rules.weather.remove(w)
                            Groups.weather.removeAll { active -> active.weather === w.weather }
                            Call.setRules(rules)
                        }
                    }
                )(player)
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> I18nManager.get("weather.all", player) },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> list }
        )(player, 1)
    }

    private fun showWeatherConfigMenu(player: Player, weather: Weather) {
        val key = WeatherEditKey(player.uuid(), weather)
        weatherEditContext[player.id] = weather

        val entry = weatherEditStates.getOrPut(key) {
            Vars.state.rules.weather.find { it.weather == weather }?.let {
                Weather.WeatherEntry().also { copy ->
                    copy.weather = it.weather
                    copy.intensity = it.intensity
                    copy.always = it.always
                    copy.minDuration = it.minDuration
                    copy.maxDuration = it.maxDuration
                    copy.minFrequency = it.minFrequency
                    copy.maxFrequency = it.maxFrequency
                }
            } ?: Weather.WeatherEntry().apply {
                this.weather = weather
                intensity = 0.5f
                minDuration = 12 * Time.toMinutes
                maxDuration = 12 * Time.toMinutes
                minFrequency = 12 * Time.toMinutes
                maxFrequency = 12 * Time.toMinutes
            }
        }

        val always = entry.always
        val makeLabel: (String, Float) -> String = { keyStr, v ->
            val color = if (always) PluginVars.SECONDARY else PluginVars.WHITE
            "${color}${I18nManager.get(keyStr, player)}: ${PluginVars.SECONDARY}${v.toInt()} mins${PluginVars.RESET}"
        }

        val buttons = arrayOf(
            arrayOf("${PluginVars.GRAY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"),
            arrayOf(
                makeLabel("weather.minDuration", entry.minDuration / Time.toMinutes),
                makeLabel("weather.maxDuration", entry.maxDuration / Time.toMinutes)
            ),
            arrayOf(
                makeLabel("weather.minFrequency", entry.minFrequency / Time.toMinutes),
                makeLabel("weather.maxFrequency", entry.maxFrequency / Time.toMinutes)
            ),
            arrayOf("${PluginVars.WHITE}${I18nManager.get("weather.always", player)}: ${PluginVars.SECONDARY}${always}${PluginVars.RESET}"),
            arrayOf("${PluginVars.INFO}${I18nManager.get("weather.save", player)}${PluginVars.RESET}")
        )

        Call.followUpMenu(player.con, weatherConfigMenuId, "${PluginVars.GRAY}${weather.localizedName}${PluginVars.RESET}", "", buttons)
    }

    private fun promptNum(player: Player, cb: (Float) -> Unit) {
        MenusManage.createTextInput(
            title = "",
            desc = "",
            placeholder = "",
            isNum = true,
            maxChars = 5
        ) { _, inp -> inp.toFloatOrNull()?.let { cb(it * Time.toMinutes) } }(player)
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
            Call.announce("${PluginVars.GRAY}${p.plainName()} \uE87C $rule: $newValue")
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
        if (!isCoreAdmin(player.uuid())) return@registerMenu
        val allRules = editableBooleanRules + editableFloatRules
        val blocksIndex = allRules.size
        val unitsIndex = allRules.size + 1
        val weatherIndex = allRules.size + 2
        val closeIndex = allRules.size + 3

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, rulesMenuId)
            blocksIndex -> showBlocksMenu(player)
            unitsIndex -> showUnitsMenu(player)
            weatherIndex -> showWeatherMenu(player)
            in 0 until allRules.size -> {
                val rule = allRules[choice]
                if (editableBooleanRules.contains(rule)) {
                    val cur = getRuleValue(rule) as? Boolean ?: return@registerMenu
                    Vars.state.rules.setRule(rule, !cur)
                    Call.announce("${PluginVars.GRAY}${p.plainName()} \uE87C $rule: ${!cur}")
                    Call.setRules(Vars.state.rules)
                    showRulesMenu(player)
                } else if (editableFloatRules.contains(rule)) {
                    promptRuleValueInput(player, rule)
                }
            }
        }
    }

    fun showRulesMenu(player: Player) {
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
        val weatherLabel = "${PluginVars.GRAY}\uE87C ${I18nManager.get("weather.title", player)}${PluginVars.RESET}"
        rows += arrayOf(blocksLabel)
        rows += arrayOf(unitsLabel)
        rows += arrayOf(weatherLabel)
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

    private val blockMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu
        if (!isCoreAdmin(player.uuid())) return@registerMenu
        val bannedBlocks = Vars.state.rules.bannedBlocks
        val blocks = sortedBlocks()
        val closeIndex = blocks.size

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, blockMenuId)
            in 0 until blocks.size -> {
                val block = blocks[choice]
                if (bannedBlocks.contains(block)) bannedBlocks.remove(block) else bannedBlocks.add(block)
                Call.setRules(Vars.state.rules)
                showBlocksMenu(player)
            }
        }
    }

    private val unitMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu
        if (!isCoreAdmin(player.uuid())) return@registerMenu
        val units = sortedUnits()
        val closeIndex = units.size

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, unitMenuId)
            in 0 until units.size -> {
                val unit = units[choice]
                val bannedUnits = Vars.state.rules.bannedUnits
                if (bannedUnits.contains(unit)) bannedUnits.remove(unit) else bannedUnits.add(unit)
                Call.setRules(Vars.state.rules)
                showUnitsMenu(player)
            }
        }
    }

    private fun showBlocksMenu(player: Player) {
        val banned = Vars.state.rules.bannedBlocks
        val rows = sortedBlocks()
            .chunked(4)
            .map { row ->
                row.map { b ->
                    val isBanned = banned.contains(b)
                    val col = if (isBanned) PluginVars.SECONDARY else PluginVars.WHITE
                    val icon = GetIcon.getBuildingIcon(b)
                    val prefix = if (isBanned) "" else ""
                    "$col$prefix$icon${PluginVars.RESET}"
                }.toTypedArray()
            }.toMutableList()

        rows += arrayOf("${PluginVars.GRAY}\uE815${PluginVars.RESET}")
        Call.followUpMenu(
            player.con,
            blockMenuId,
            "${PluginVars.GRAY}${I18nManager.get("rules.blocks", player)}${PluginVars.RESET}",
            "",
            rows.toTypedArray()
        )
    }

    private fun showUnitsMenu(player: Player) {
        val banned = Vars.state.rules.bannedUnits
        val rows = sortedUnits()
            .chunked(4)
            .map { row ->
                row.map { u ->
                    val isBanned = banned.contains(u)
                    val col = if (isBanned) PluginVars.SECONDARY else PluginVars.WHITE
                    val icon = GetIcon.getUnitIcon(u)
                    val prefix = if (isBanned) "" else ""
                    "$col$prefix$icon${PluginVars.RESET}"
                }.toTypedArray()
            }.toMutableList()

        rows += arrayOf("${PluginVars.GRAY}\uE815${PluginVars.RESET}")
        Call.followUpMenu(
            player.con,
            unitMenuId,
            "${PluginVars.GRAY}${I18nManager.get("rules.units", player)}${PluginVars.RESET}",
            "",
            rows.toTypedArray()
        )
    }



    fun showGameOverMenu(player: Player) {
        if (!isCoreAdmin(player.uuid())) {
            Call.announce(player.con,
                "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}")
            return
        }

        val teams = Team.all.filter { it == Team.derelict || it.data().hasCore() }

        val rows = teams.map { team ->
            MenuEntry("${PluginVars.WHITE}${team.coloredName()}${PluginVars.RESET}") {
                showConfirmMenu(player) {
                    Events.fire(EventType.GameOverEvent(team))
                }
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.GRAY}${I18nManager.get("gameover.title", player)}${PluginVars.RESET}"
            },
            desc  = { _, _, _ -> "\n${PluginVars.GRAY}${I18nManager.get("gameover.desc", player)}${PluginVars.RESET}\n" },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    fun showHelpMenu(player: Player, page: Int = 1) {
        val show = MenusManage.createMenu<Unit>(
            title = { p, pageNum, total, _ ->
                "${PluginVars.GRAY}${I18nManager.get("help.title", p)} $pageNum/$total${PluginVars.RESET}"
            },
            desc = { _, _, _ -> "" },
            options = { p, _, _ ->
                Vars.netServer.clientCommands.commandList
                    .sortedBy { it.text }
                    .filter { it.text != "help" }
                    .filter { it.text != "t" }
                    .filter { it.text != "a" }
                    .filter { it.text != "ban" }
                    .filter { it.text != "print" }
                    .filter { it.text != "wave" }
                    .filter { it.text != "votekick" }
                    .filter { it.text != "over" || isCoreAdmin(player.uuid()) }
                    .filter { it.text != "rules" || isCoreAdmin(player.uuid()) }
                    .filter { it.text != "revert" || isCoreAdmin(player.uuid()) }
                    .map { cmd ->
                        val descKey = "helpCmd.${cmd.text}"
                        val desc = I18nManager.get(descKey, p)
                        MenuEntry("${PluginVars.INFO}$desc${PluginVars.RESET}") { player ->
                            NetClient.sendChatMessage(player, "/${cmd.text}")
                        }
                    }
            }
        )

        show(player, page)
    }

    fun showMessageMenu(player: Player, page: Int = 1) {
        val allMessages = RecordMessage.getAll().lines().asReversed()

        val menu = MenusManage.createMenu<Unit>(
            title = { p, currentPage, totalPages, _ ->
                "${PluginVars.GRAY}${I18nManager.get("messages.title", p)} $currentPage/$totalPages${PluginVars.RESET}"
            },
            perPage = 50,
            paged = true,
            desc = { _, _, _ -> "" },
            options = { p, _, _ ->
                if (allMessages.isEmpty()) {
                    listOf(
                        MenuEntry("${PluginVars.INFO}${I18nManager.get("messages.empty", p)}${PluginVars.RESET}") {}
                    )
                } else {
                    allMessages.map { msg ->
                        MenuEntry("${PluginVars.INFO}$msg${PluginVars.RESET}") { viewer ->
                            MenusManage.createTextInput(
                                title = I18nManager.get("messages.title", viewer),
                                desc = "",
                                isNum = false,
                                placeholder = msg
                            ) { _, _ ->
                                return@createTextInput
                            }(viewer)
                        }
                    }
                }
            }
        )

        menu(player, page)
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

    fun showLogoutMenu(player: Player) {
        val uuid = player.uuid()
        val acc = DataManager.getPlayerDataByUuid(uuid)
        val team = PlayerTeam.getTeam(uuid)

        if (acc == null) {
            Call.announce(
                player.con,
                "${PluginVars.WARN}${I18nManager.get("logout.not_logged_in", player)}${PluginVars.RESET}"
            )
            return
        }

        if (acc.isLock) {
            Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("isLock", player)}${PluginVars.RESET}")
            return
        }

        if (Vars.state.rules.pvp && team != null) {
            Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("inPvP", player)}${PluginVars.RESET}")
            return
        }

        val btnLogout = MenuEntry("${PluginVars.WHITE}${I18nManager.get("logout.button.current", player)}${PluginVars.RESET}") {
            showConfirmMenu(player) {
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

        val btnLogoutAll = MenuEntry("${PluginVars.WHITE}${I18nManager.get("logout.button.all", player)}${PluginVars.RESET}") {
            showConfirmMenu(player) {
                acc.uuids.clear()
                player.clearUnit()
                DataManager.requestSave()
                Call.announce(
                    player.con,
                    "${PluginVars.INFO}${I18nManager.get("logout.all", player)}${PluginVars.RESET}"
                )
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("logout.title", player)}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> "" },
            options = { _, _, _ -> listOf(btnLogout, btnLogoutAll) }
        )(player, 1)
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

        val desc = buildString {
            append("\n${PluginVars.WHITE}${acc.account}${PluginVars.RESET}\n${PluginVars.SECONDARY}\uF029${acc.id}${PluginVars.RESET}\n\n")
            append("${PluginVars.SECONDARY}${I18nManager.get("playerInfo.score", viewer)}: ${acc.score}${PluginVars.RESET}\n")
            append("${PluginVars.SECONDARY}${I18nManager.get("playerInfo.wins", viewer)}: ${acc.wins}${PluginVars.RESET}\n")
            append("${PluginVars.SECONDARY}${I18nManager.get("playerInfo.lang", viewer)}: ${acc.lang}${PluginVars.RESET}\n")
            append(
                "${PluginVars.SECONDARY}${I18nManager.get("playerInfo.role", viewer)}: ${
                    if (isCoreAdmin(target.uuid())) "Admin" else "Player"
                }${PluginVars.RESET}\n"
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
                if (VoteManager.globalVoteSession != null) {
                    Call.announce(viewer.con, "${PluginVars.WARN}${I18nManager.get("vote.running", viewer)}${PluginVars.RESET}")
                    return@showConfirmMenu
                }
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
                        "${PluginVars.WARN}${I18nManager.get("playerInfo.setban.invalid", viewer)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }
                DataManager.updatePlayer(acc.id) {
                    it.banUntil = if (seconds == 0L) 0 else System.currentTimeMillis() + seconds * 1000
                }
                target.kick("")
                restorePlayerEditsWithinSeconds(target.uuid(), 200)
                UnitEffects.clear(target.uuid())
                Call.announce(
                    viewer.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("playerInfo.setban.success", viewer)}${PluginVars.RESET}"
                )
            }(viewer)
        }

        val rows = mutableListOf(btnPm, btnVoteKick)

        if (canSet) {
            rows += btnBan
        }
        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("playerInfo.title", viewer)}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> desc },
            options = { _, _, _ -> rows }
        )(viewer, 1)
    }

    private fun reloadWorld(map: mindustry.maps.Map) {
        if (Vars.state.isMenu || !map.file.exists()) return

        try {
            val reloader = WorldReloader()
            reloader.begin()
            Vars.state.map = map
            Vars.world.loadMap(map)
            Vars.logic.play()
            reloader.end()
        } catch (_: Exception) {}
    }


    fun showMapMenu(player: Player, page: Int = 1) {
        val modeItems: List<Pair<Gamemode?, String>> = listOf(
            Gamemode.pvp      to I18nManager.get("mode.pvp", player),
            Gamemode.survival to I18nManager.get("mode.survival", player),
            Gamemode.attack   to I18nManager.get("mode.attack", player),
            Gamemode.sandbox  to I18nManager.get("mode.sandbox", player),
            null              to I18nManager.get("map.all", player)
        )

        val rows = modeItems.map { (mode, text) ->
            MenuEntry("${PluginVars.WHITE}$text${PluginVars.RESET}") {
                showMapListMenu(player, mode, 1)
            }
        }.toMutableList()

        rows += MenuEntry("${PluginVars.WHITE}${I18nManager.get("map.search", player)}${PluginVars.RESET}") {
            showMapSearchMenu(player)
        }

        MenusManage.createMenu<Unit>(
            title = { _, p, t, _ -> "${PluginVars.GRAY}${I18nManager.get("map.mode.choose", player)} $p/$t${PluginVars.RESET}" },
            desc  = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, page)
    }

    private fun showMapSearchMenu(player: Player) {
        MenusManage.createTextInput(
            title = I18nManager.get("map.search", player),
            desc = I18nManager.get("map.search.desc", player),
            placeholder = "",
            isNum = false,
            maxChars = 50
        ) { _, input ->
            val searchTerm = input.trim()
            if (searchTerm.isNotEmpty()) {
                showFilteredMapList(player, searchTerm)
            }
        }(player)
    }

    private fun showFilteredMapList(player: Player, searchTerm: String) {
        val allMaps = Vars.maps.customMaps().toList()

        val filtered = allMaps.filter { map ->
            map.name().contains(searchTerm, ignoreCase = true)
        }

        if (filtered.isEmpty()) {
            Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("map.none", player)}${PluginVars.RESET}")
            return
        }

        val rows = filtered.mapIndexed { index, map ->
            val icon = "\uF029"
            val sizeStr = "${map.width}x${map.height}"

            val label = buildString {
                append("${PluginVars.WHITE}$icon${index + 1} ${map.name()}${PluginVars.RESET}")
                append("\n${PluginVars.SECONDARY}$sizeStr${PluginVars.RESET}")
            }

            MenuEntry(label) {
                showMapOptionMenu(player, map)
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("search", player)}: $searchTerm${PluginVars.RESET}" },
            desc = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, 1)
    }


    private fun i18nMode(mode: Gamemode, player: Player): String = when (mode) {
        Gamemode.pvp      -> I18nManager.get("mode.pvp", player)
        Gamemode.survival -> I18nManager.get("mode.survival", player)
        Gamemode.attack   -> I18nManager.get("mode.attack", player)
        Gamemode.sandbox  -> I18nManager.get("mode.sandbox", player)
        else              -> I18nManager.get("mode.unknown", player)
    }

    fun showMapListMenu(player: Player, mode: Gamemode?, page: Int = 1) {
        val allMaps = Vars.maps.customMaps().toList()
        val filtered = if (mode == null) allMaps else allMaps.filter {
            val fileName = it.file.name()
            val mapDataMode = DataManager.maps[fileName]?.modeName?.lowercase()
            val tagMode = TagUtil.getMode(it.description())?.name?.lowercase()
            (mapDataMode ?: tagMode) == mode.name.lowercase()
        }

        if (filtered.isEmpty()) {
            Call.announce(player.con,
                "${PluginVars.WARN}${I18nManager.get("map.none", player)}${PluginVars.RESET}")
            return
        }

        val current = Vars.state.map
        val nextMap = NextMap.get()
        var index   = 1

        val rows = filtered.map { map ->
            val isCurrent = map == current
            val isNext    = map == nextMap
            val icon = when {
                isCurrent -> "${PluginVars.GREEN}\uE829"
                isNext    -> "${PluginVars.GOLD}\uE809"
                else      -> "\uF029"
            }
            val fileName = map.file.name()
            val mapMode = DataManager.maps[fileName]?.modeName
                ?.lowercase()
                ?.let { mode -> Gamemode.entries.find { it.name.equals(mode, ignoreCase = true) } }
                ?: TagUtil.getMode(map.description())

            val used    = UsedMaps.isUsed(map)
            val color   = if (used) PluginVars.SECONDARY else PluginVars.WHITE
            val sizeStr = "${map.width}x${map.height}"

            val label = buildString {
                append("$color$icon$index ${map.name()}${PluginVars.RESET}")
                append("\n")
                if (mode == null) {
                    if (mapMode != null) {
                        append("${PluginVars.SECONDARY}${i18nMode(mapMode, player)} $sizeStr${PluginVars.RESET}")
                    } else {
                        append("${PluginVars.SECONDARY}$sizeStr${PluginVars.RESET}")
                    }
                } else {
                    append("${PluginVars.SECONDARY}$sizeStr${PluginVars.RESET}")
                }
            }

            index++
            MenuEntry(label) { showMapOptionMenu(player, map) }
        }

        val title = if (mode == null)
            I18nManager.get("map.all", player)
        else
            i18nMode(mode, player)

        MenusManage.createMenu<Unit>(
            title   = { _, p, t, _ -> "${PluginVars.GRAY}$title $p/$t${PluginVars.RESET}" },
            desc    = { _, _, _ -> "" },
            paged   = true,
            options = { _, _, _ -> rows }
        )(player, page)
    }


    fun showMapOptionMenu(player: Player, map: mindustry.maps.Map) {
        val isAdmin = isCoreAdmin(player.uuid())
        val normalCount = Groups.player.count { !isBanned(it.uuid()) }
        val fileName = map.file.name()
        val mapMode = DataManager.maps[fileName]?.modeName
            ?.lowercase()
            ?.let { mode -> Gamemode.entries.find { it.name.equals(mode, ignoreCase = true) } }
            ?: TagUtil.getMode(map.description())

        val isOk = isAdmin || normalCount < 2
        val strong = PluginVars.INFO
        val weak = PluginVars.SECONDARY

        val mapFileName = map.file.name()
        val uploaderId = DataManager.maps[mapFileName]?.uploaderId
        val uploaderName = uploaderId?.let { id ->
            val uploaderData = DataManager.players.values().find { it.id == id }
            uploaderData?.account
        } ?: I18nManager.get("unknown", player)

        val modeRaw = DataManager.maps[mapFileName]?.modeName ?: ""
        val modeName = when (modeRaw.lowercase()) {
            "pvp" -> I18nManager.get("mode.pvp", player)
            "survival" -> I18nManager.get("mode.survival", player)
            "attack" -> I18nManager.get("mode.attack", player)
            "sandbox" -> I18nManager.get("mode.sandbox", player)
            else -> I18nManager.get("mode.unknown", player)
        }

        val desc = buildString {
            append("\n${PluginVars.SECONDARY}${I18nManager.get("mapInfo.uploader", player)}: $uploaderName${PluginVars.RESET}\n")
            append("\n${PluginVars.GRAY}$modeName${PluginVars.RESET}\n")
            append("\n${PluginVars.SECONDARY}${map.author() ?: I18nManager.get("unknown", player)}${PluginVars.RESET}\n")
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
                val points = DataManager.getPlayerDataByUuid(player.uuid())?.score ?: 0
                if (Vars.state.isGame && Vars.state.rules.pvp && Vars.state.tick > 5 * 60 * 60 && points < 150) {
                    Call.announce(player.con, "${PluginVars.WHITE}${I18nManager.get("inPvP", player)}")
                    return@MenuEntry
                }

                showConfirmMenu(player) {
                    if (VoteManager.globalVoteSession != null) {
                        Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                        return@showConfirmMenu
                    }
                    VoteManager.createGlobalVote(creator = player) { ok ->
                        if (ok && Vars.state.isGame) {
                            reloadWorld(map)
                        }
                    }

                    Groups.player.each { p ->
                        if (p != player && !isBanned(p.uuid())) {
                            val title = "${PluginVars.INFO}${I18nManager.get("rtv.title", p)}${PluginVars.RESET}"
                            val desc = "\uE827 ${PluginVars.GRAY}${player.name} ${I18nManager.get("rtv.desc", p)} ${map.name()}${PluginVars.RESET}"

                            val menu = createConfirmMenu(
                                title = { title },
                                desc = { desc },
                                canStop = isCoreAdmin(p.uuid()),
                                onResult = { pl, choice ->
                                    if (choice == 0) {
                                        VoteManager.addVote(pl.uuid())
                                    }
                                    if (choice == 2) {
                                        VoteManager.clearVote()
                                        Call.announce("${PluginVars.GRAY}${p.name} ${I18nManager.get("vote.cancel", p)}${PluginVars.RESET}")
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
            "${if (isOk) strong else weak}${I18nManager.get("mapInfo.change", player)}${PluginVars.RESET}"
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
            "${if (isOk) strong else weak}${I18nManager.get("mapInfo.next", player)}${PluginVars.RESET}"
        ) {
            if (isOk) {
                showConfirmMenu(player) {
                    Vars.maps.setNextMapOverride(map)
                    NextMap.set(map)
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

        val canDelete = isAdmin || uploaderId == DataManager.getIdByUuid(player.uuid())

        val btnDelete = MenuEntry(
            "${if (canDelete) strong else weak}${I18nManager.get("mapInfo.delete", player)}${PluginVars.RESET}"
        ) {
            if (!canDelete) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}"
                )
                return@MenuEntry
            }

            showConfirmMenu(player) {
                val deleted = map.file.delete()
                if (deleted) {
                    DataManager.maps.remove(mapFileName)
                    DataManager.requestSave()
                    Vars.maps.reload()
                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("mapInfo.deleted", player)}${PluginVars.RESET}"
                    )
                } else {
                    Call.announce(
                        player.con,
                        "${PluginVars.ERROR}${I18nManager.get("mapInfo.delete_failed", player)}${PluginVars.RESET}"
                    )
                }
            }
        }

        val btnSelectMode = MenuEntry("${PluginVars.WHITE}${I18nManager.get("mapInfo.selectmode", player)}${PluginVars.RESET}") {
            if (isAdmin || uploaderId == DataManager.getIdByUuid(player.uuid())) {
                showSelectModeMenu(player, map)
            } else {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}"
                )
            }
        }

        val rows = mutableListOf<MenuEntry>()

        if (isAdmin || mapMode != null) {
            rows += btnVote
        }

        if (isAdmin) {
            rows += btnChange
            rows += btnNext
        }

        if (isAdmin || uploaderId == DataManager.getIdByUuid(player.uuid())) {
            rows += btnDelete
            rows += btnSelectMode
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${map.name()}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> desc },
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    fun showSelectModeMenu(player: Player, map: mindustry.maps.Map) {
        val currentMode = DataManager.maps[map.file.name()]?.modeName ?: "unknown"

        val modeDesc = when (currentMode) {
            "pvp" -> I18nManager.get("mode.pvp", player)
            "survival" -> I18nManager.get("mode.survival", player)
            "attack" -> I18nManager.get("mode.attack", player)
            "sandbox" -> I18nManager.get("mode.sandbox", player)
            else -> I18nManager.get("mode.unknown", player)
        }

        val modeButtons = listOf(
            Gamemode.pvp to I18nManager.get("mode.pvp", player),
            Gamemode.survival to I18nManager.get("mode.survival", player),
            Gamemode.attack to I18nManager.get("mode.attack", player),
            Gamemode.sandbox to I18nManager.get("mode.sandbox", player)
        )

        val rows = modeButtons.map { (mode, label) ->
            MenuEntry(label) {
                updateMapMode(map, mode)
                Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("mapInfo.modechanged", player)} ${label}${PluginVars.RESET}")
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${map.name()}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> "${PluginVars.SECONDARY}${I18nManager.get("mapInfo.currentmode", player)}: $modeDesc${PluginVars.RESET}" },  // 动态描述内容
            options = { _, _, _ -> rows }
        )(player, 1)
    }


    fun updateMapMode(map: mindustry.maps.Map, newMode: Gamemode) {
        val mapFileName = map.file.name()
        DataManager.maps[mapFileName]?.modeName = newMode.name
        DataManager.requestSave()
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
        val strong = PluginVars.WHITE
        val weak = PluginVars.WARN

        val i18nTrue = I18nManager.get("common.true", player)
        val i18nFalse = I18nManager.get("common.false", player)
        val btnLogout = MenuEntry("${strong}${I18nManager.get("logout.title", player)}${PluginVars.RESET}") {
            showLogoutMenu(player)
        }

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


        val rows = listOf(btnLogout, btnUsername, btnPassword, btnLang, btnLock)

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.GRAY}${I18nManager.get("profile.title", player)}${PluginVars.RESET}"
            },
            paged = false,
            desc = { _, _, _ -> "" },
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    private val tempTeamChoices = mutableMapOf<String, MutableList<Team>>()
    fun showTeamMenu(player: Player) {
        val desc = Vars.state.map.description()
        val tagMap = TagUtil.getTagValues(desc)
        val bannedTeams = tagMap["banteam"]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        val teamSizeLimit = tagMap["teamsize"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE

        val teamsWithCore = Team.all.filter { t ->
            t != Team.derelict &&
                    !bannedTeams.contains(t.id) &&
                    t.data().hasCore()
        }.toMutableList()

        if (teamsWithCore.isEmpty()) {
            Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("team.no_team", player)}${PluginVars.RESET}")
            return
        }

        val all = Groups.player.copy().select { p ->
            val t = p.team()
            t != Team.derelict && t.data().hasCore()
        }

        val totalPlayers = all.size + 1
        val maxPerTeam = max(1, minOf(teamSizeLimit, Mathf.ceil(totalPlayers / teamsWithCore.size.toFloat())))
        val descText = ""

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
            descText,
            rows.toTypedArray()
        )
    }

    private fun onMenuChoose(player: Player, choice: Int) {
        if (choice < 0) return
        if (player.team().data().hasCore()) return

        val teams = tempTeamChoices[player.uuid()]?.toList()?.toMutableList() ?: return
        val totalCols = teams.size
        val colIndex = choice % totalCols
        val rowIndex = choice / totalCols

        val target = teams.getOrNull(colIndex) ?: return
        if (target === Team.derelict || !target.data().hasCore()) return

        val desc = Vars.state.map.description()
        val tagMap = TagUtil.getTagValues(desc)
        val teamSizeLimit = tagMap["teamsize"]?.firstOrNull()?.toIntOrNull()?.coerceAtLeast(1) ?: Int.MAX_VALUE

        val all = Groups.player.copy().select { it?.team()?.let { t -> t != Team.derelict && t.data().hasCore() } ?: false }
        val totalPlayers = all.size + 1
        val maxPerTeam = max(1, minOf(teamSizeLimit, Mathf.ceil(totalPlayers / teams.size.toFloat())))
        val count = all.count { it?.team() === target }
        val col = all.filter { it?.team() === target }.mapNotNull { it?.name }

        val isPlusButton = (rowIndex == col.size + 1) && (count < maxPerTeam)

        if (!isPlusButton) {
            showTeamMenu(player)
            return
        }

        Call.hideFollowUpMenu(player.con, teamMenuId)
        player.team(target)
        PlayerTeam.setTeam(player, target)

        val name = Strings.stripColors(player.name)
        val pData = DataManager.getPlayerDataByUuid(player.uuid())
        if (pData != null) {
            Groups.player.each { p ->
                if (!RecordMessage.isDisabled(p.uuid())) {
                    val text = "${PluginVars.INFO}$name ${
                        I18nManager.get(
                            "joined",
                            p
                        )
                    } ${target.coloredName()}${PluginVars.RESET}"
                    Call.infoToast(p.con, text, 3f)
                }
            }
        }
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
            } else {
                player.team(Vars.state.rules.defaultTeam)
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
            if (Vars.state.rules.pvp) {
                showTeamMenu(p)
            } else {
                p.team(Vars.state.rules.defaultTeam)
            }
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
        if (!isCoreAdmin(player.uuid())) return
        val revertPlayers = RevertBuild.getAllPlayersWithEdits()
        val btns = mutableListOf<MenuEntry>()
        btns.add(
            MenuEntry(
                "${PluginVars.WHITE}${
                    I18nManager.get(
                        "revert.all_players",
                        player
                    )
                }${PluginVars.RESET}"
            ) {
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
                            "${PluginVars.WARN}${
                                I18nManager.get(
                                    "revert.invalid_input",
                                    player
                                )
                            }${PluginVars.RESET}"
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
            val name = Vars.netServer.admins.getInfo(uuid).lastName

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
                            "${PluginVars.WARN}${
                                I18nManager.get(
                                    "revert.invalid_input",
                                    player
                                )
                            }${PluginVars.RESET}"
                        )
                        return@createTextInput
                    }

                    restorePlayerEditsWithinSeconds(uuid, seconds)

                    Call.announce(
                        player.con,
                        "${PluginVars.SUCCESS}${
                            I18nManager.get(
                                "revert.player_success",
                                player
                            )
                        } $name${PluginVars.RESET}"
                    )
                }(player)
            })
        }

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ ->
                "${PluginVars.GRAY}${
                    I18nManager.get(
                        "revert.title",
                        player
                    )
                }${PluginVars.RESET}"
            },
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
        val point = DataManager.getPlayerDataByUuid(player.uuid())?.score
        if (!isCoreAdmin(player.uuid()) && (point == null || point < 50)) {
            Call.announce(player.con(), I18nManager.get("noPoints", player))
            return
        }
        Call.menu(
            player.con,
            uploadMapMenuId,
            "${PluginVars.GRAY}${I18nManager.get("uploadMap", player)}${PluginVars.RESET}",
            "\n${PluginVars.WARN}${I18nManager.get("uploadDesc", player)}${PluginVars.RESET}\n",
            buttons
        )
    }

    val aboutMenuId: Int = Menus.registerMenu { p, choice ->
        if (p == null) return@registerMenu
        when (choice) {
            0 -> Call.hideFollowUpMenu(p.con, uploadMapMenuId)
            1 -> {
                val url = "https://github.com/ankando/snow"
                Call.openURI(p.con, url)
            }
        }
    }

    fun showAboutMenu(player: Player) {
        val closeLabel = "${PluginVars.GRAY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"
        val openLabel = "${PluginVars.INFO}${I18nManager.get("open", player)}${PluginVars.RESET}"
        val buttons = arrayOf(arrayOf(closeLabel), arrayOf(openLabel))

        Call.menu(
            player.con,
            aboutMenuId,
            "${PluginVars.GRAY}${I18nManager.get("about", player)}${PluginVars.RESET}",
            "\n${PluginVars.WARN}${I18nManager.get("aboutDesc", player)}${PluginVars.RESET}\n",
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
                    if (VoteManager.globalVoteSession != null) {
                        Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                        return@showConfirmMenu
                    }
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
            if (p != viewer && p != target && !isBanned(p.uuid())) {
                val title = "${PluginVars.WARN}${I18nManager.get("playerInfo.votekick.title", p)}${PluginVars.RESET}"
                val desc = "\uE817 ${PluginVars.GRAY}${viewer.name} ${
                    I18nManager.get("playerInfo.votekick.desc", p)
                } ${target.name()}${PluginVars.RESET}"

                val voteMenu = createConfirmMenu(
                    title = { title },
                    desc = { desc },
                    canStop = isCoreAdmin(p.uuid()),
                    onResult = { pl, choice ->
                        if (choice == 0) {
                            VoteManager.addVote(pl.uuid())
                        }
                        if (choice == 2) {
                            VoteManager.clearVote()
                            Call.announce("${PluginVars.GRAY}${p.name} ${I18nManager.get("vote.cancel", p)}${PluginVars.RESET}")
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
            title = { title },
            desc = { desc },
            onResult = { p, choice ->
                if (choice == 0) onConfirm(p)
            }
        )(player)
    }

    fun showOthersMenu(player: Player) {
        val strong = PluginVars.WHITE
        val weak = PluginVars.WARN
        val uuid = player.uuid()

        val current = RevertBuild.RevertState.getHistoryMode(uuid)

        val i18nShowHistory = I18nManager.get("others.showHistory", player)
        val i18nShowIcons = I18nManager.get("icons.title", player)
        val i18nEffect = I18nManager.get("others.selectEffect", player)
        val i18nHud = I18nManager.get("showHud.title", player)

        val historyModeState = if (current) "true" else "false"
        val currentMsgState = !RecordMessage.isDisabled(uuid)
        val msgToggleLabel = I18nManager.get("others.showMessage", player)
        val msgToggleState = if (currentMsgState) "true" else "false"

        val toggleMessageBtn = MenuEntry("${strong}$msgToggleLabel: ${weak}$msgToggleState${PluginVars.RESET}") {
            val newState = !currentMsgState
            RecordMessage.setDisabled(uuid, !newState)
            Call.announce(player.con, I18nManager.get("ok", player))
        }
        val toggleHistoryBtn = MenuEntry("${strong}$i18nShowHistory: ${weak}$historyModeState${PluginVars.RESET}") {
            val newState = !current
            Call.announce(player.con, I18nManager.get("ok", player))
            RevertBuild.RevertState.setHistoryMode(uuid, newState)
        }
        val showIconsBtn = MenuEntry("${strong}${i18nShowIcons}${PluginVars.RESET}") {
            showIconMenu(player)
        }

        val effectMenuBtn = MenuEntry("${strong}$i18nEffect${PluginVars.RESET}") {
            showEffectMenu(player)
        }

        val hudMenuBtn = MenuEntry("${strong}$i18nHud${PluginVars.RESET}") {
            showHudTextMenu(player)
        }

        val rows = listOf(
            toggleMessageBtn,
            toggleHistoryBtn,
            showIconsBtn,
            effectMenuBtn,
            hudMenuBtn
        )
        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("others.title", player)}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> "" },
            options = { _, _, _ -> rows }
        )(player, 1)
    }

    private val iconMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu
        if (choice == 0) {
            Call.hideFollowUpMenu(player.con, iconMenuId)
            return@registerMenu
        }

        val allIcons = Iconc::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Char::class.javaPrimitiveType }
            .mapNotNull {
                try { (it.get(null) as Char).toString() }
                catch (_: Exception) { null }
            }
        val buildingIcons = Vars.content.blocks().map { GetIcon.getBuildingIcon(it) }
        val unitIcons = Vars.content.units().map { GetIcon.getUnitIcon(it) }

        val uniqueIcons = allIcons.toSet() - buildingIcons.toSet() - unitIcons.toSet()
        val icons = (uniqueIcons + buildingIcons + unitIcons).toList()

        val idx = choice - 1
        if (idx in icons.indices) {
            val icon = icons[idx]
            val iconInput = MenusManage.createTextInput(
                title       = I18nManager.get("icons.title", player),
                desc        = "",
                placeholder = icon,
                isNum       = false,
                maxChars    = PluginVars.MENU_TEXT_INPUT_MAX_CHARS
            ) { _, _ ->
            }
            iconInput(player)
        }
    }


    fun showIconMenu(player: Player) {
        val allIcons = Iconc::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Char::class.javaPrimitiveType }
            .mapNotNull {
                try { (it.get(null) as Char).toString() }
                catch (_: Exception) { null }
            }

        val buildingIcons = Vars.content.blocks().map { GetIcon.getBuildingIcon(it) }
        val unitIcons = Vars.content.units().map { GetIcon.getUnitIcon(it) }

        val uniqueIcons = allIcons.toSet() - buildingIcons.toSet() - unitIcons.toSet()
        val filteredIcons =  uniqueIcons + buildingIcons + unitIcons
        val rows = mutableListOf<Array<String>>()
        rows += arrayOf("${PluginVars.GRAY}\u2716${PluginVars.RESET}")
        filteredIcons.chunked(5).forEach { chunk ->
            rows += chunk.map { "${PluginVars.WHITE}$it${PluginVars.RESET}" }.toTypedArray()
        }

        Call.followUpMenu(
            player.con,
            iconMenuId,
            "${PluginVars.GRAY}${I18nManager.get("icons.title", player)}${PluginVars.RESET}",
            "",
            rows.toTypedArray()
        )
    }

    fun showHudTextMenu(player: Player) {
        val strong = PluginVars.WHITE

        val clearBtn = MenuEntry("${strong}\uE87C ${I18nManager.get("showHud.hide", player)}${PluginVars.RESET}") {
            HudTextController.setMode(player, null)
            Call.announce(player.con, I18nManager.get("ok", player))
        }

        val modeButtons = HudTextController.availableModes().map { mode ->
            MenuEntry("${strong}${mode.displayName}${PluginVars.RESET}") {
                HudTextController.setMode(player, mode)
                Call.announce(player.con, "${PluginVars.SUCCESS}\uE87C ${mode.displayName}")
            }
        }

        val rows = listOf(clearBtn) + modeButtons

        MenusManage.createMenu<Unit>(
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("showHud.title", player)}${PluginVars.RESET}" },
            paged = false,
            desc = { _, _, _ -> "" },
            options = { _, _, _ -> rows }
        )(player, 1)
    }
    private val effectMenuId: Int = Menus.registerMenu { p, choice ->
        val player = p ?: return@registerMenu
        val effects = UnitEffects.allEffects()

        when (choice) {
            0 -> {
                Call.hideFollowUpMenu(player.con, effectMenuId)
            }
            1 -> {
                UnitEffects.clear(player.uuid())
                Call.announce(player.con, "${PluginVars.INFO}\uE87C ${I18nManager.get("common.false", player)}${PluginVars.RESET}")
            }
            in 2 until (2 + effects.size) -> {
                val selected = effects[choice - 2].second
                UnitEffects.setEffect(player, selected)
                Call.announce(player.con, "${PluginVars.WHITE}\uE809 ${effects[choice - 2].first}${PluginVars.RESET}")
            }
        }
    }

    fun showEffectMenu(player: Player) {
        val point = DataManager.getPlayerDataByUuid(player.uuid())?.score
        if (!isCoreAdmin(player.uuid()) && (point == null || point < 100)) {
            Call.announce(player.con(), I18nManager.get("noPoints", player))
            return
        }

        val effects = UnitEffects.allEffects()
        val rows = mutableListOf<Array<String>>()

        rows += arrayOf("${PluginVars.GRAY}\u2716${PluginVars.RESET}")

        rows += arrayOf("${PluginVars.WHITE}\uE87C ${I18nManager.get("showHud.hide", player)}${PluginVars.RESET}")

        effects.map { it.first }
            .chunked(5)
            .forEach { chunk ->
                rows += chunk.map { "${PluginVars.WHITE}$it${PluginVars.RESET}" }.toTypedArray()
            }

        Call.followUpMenu(
            player.con,
            effectMenuId,
            "${PluginVars.GRAY}${I18nManager.get("others.selectEffect", player)}${PluginVars.RESET}",
            "",
            rows.toTypedArray()
        )
    }
    fun showEmojisMenu(player: Player, page: Int = 1) {
        val isAdmin = isCoreAdmin(player.uuid())
        val emojiDir = Vars.saveDirectory.child("emojis")
        emojiDir.mkdirs()

        val files = emojiDir.list()
            .filter { it.exists() && !it.isDirectory }
            .sortedBy { it.name() }

        val rows = mutableListOf<MenuEntry>()

        if (isAdmin) {
            rows += MenuEntry("${PluginVars.WHITE}${I18nManager.get("emoji.delete", player)}${PluginVars.RESET}") {
                val deleteRows = files.map { file ->
                    MenuEntry("${PluginVars.WHITE}${file.name()}${PluginVars.RESET}") {
                        val success = file.delete()
                        if (success) {
                            Call.announce(
                                player.con,
                                "${PluginVars.SUCCESS}${I18nManager.get("emoji.deleted", player)}: ${file.name()}${PluginVars.RESET}"
                            )
                        } else {
                            Call.announce(
                                player.con,
                                "${PluginVars.ERROR}${I18nManager.get("emoji.delete_failed", player)}${PluginVars.RESET}"
                            )
                        }
                        showEmojisMenu(player, page)
                    }
                }

                MenusManage.createMenu<Unit>(
                    title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("emoji.delete", player)}${PluginVars.RESET}" },
                    desc = { _, _, _ -> "" },
                    paged = true,
                    options = { _, _, _ -> deleteRows }
                )(player, 1)
            }
        }

        files.forEach { file ->
            rows += MenuEntry("${PluginVars.WHITE}${file.name()}${PluginVars.RESET}") {
                Emoji.print(player, file.name())
            }
        }

        MenusManage.createMenu<Unit>(
            title = { _, pageNum, totalPages, _ ->
                "${PluginVars.GRAY}${I18nManager.get("emoji.title", player)} $pageNum/$totalPages${PluginVars.RESET}"
            },
            desc = { _, _, _ -> "" },
            paged = true,
            options = { _, _, _ -> rows }
        )(player, page)
    }
}
