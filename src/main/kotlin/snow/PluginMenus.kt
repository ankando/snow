package plugin.snow

import arc.Events
import arc.math.Mathf
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.core.NetClient
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Rules
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.io.SaveIO
import mindustry.net.Packets
import mindustry.net.WorldReloader
import mindustry.type.UnitType
import mindustry.ui.Menus
import mindustry.world.Block
import plugin.core.*
import plugin.core.MenusManage.createConfirmMenu
import plugin.core.PermissionManager.isBanned
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.PermissionManager.isNormal
import plugin.core.RevertBuild.restorePlayerEditsWithinSeconds
import kotlin.math.max
import kotlin.math.pow

object PluginMenus {
    fun showGamesMenu(player: Player, page: Int = 1) {
        val games = listOf(
            "2048"                                                    to ::show2048game,
            I18nManager.get("game.lightsout",     player)            to ::showLightsOutGame,
            I18nManager.get("game.guessthenumber", player)           to ::showGuessGameMenu,
            I18nManager.get("game.gomoku",         player)           to ::showGomokuEntry
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



    private enum class Stone(val glyph: String){ EMPTY("\uF8F7"), BLACK("\uF6F3"), WHITE("\uF7C9") }
    private data class Cursor(var x:Int=10,var y:Int=10)
    private data class Invite(val from:String,val time:Long=System.currentTimeMillis())
    private data class GomokuState(
        val white:String,val black:String,
        val board:Array<Array<Stone>> = Array(21){ Array(21){ Stone.EMPTY } },
        var turnBlack:Boolean = true,
        var winner:Stone? = null,
        val cursors:MutableMap<String,Cursor> = mutableMapOf()
    ){
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

        private fun checkFive(x:Int,y:Int,s:Stone):Boolean{
            fun count(dx:Int,dy:Int):Int{
                var c=0; var nx=x+dx; var ny=y+dy
                while(nx in 0 until 21 && ny in 0 until 21 && board[ny][nx]==s){ c++; nx+=dx; ny+=dy }
                return c
            }
            val dirs = arrayOf(1 to 0,0 to 1,1 to 1,1 to -1)
            return dirs.any{ (dx,dy)-> 1+count(dx,dy)+count(-dx,-dy)>=5 }
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

    private fun key(u1:String,u2:String)=if(u1<u2) u1 to u2 else u2 to u1
    private const val BOARD=21
    private val invites=mutableMapOf<String,MutableList<Invite>>()
    private val games  =mutableMapOf<Pair<String,String>,GomokuState>()
    private const val INV_PREFIX="${PluginVars.SECONDARY}\uE861${PluginVars.RESET}"

    private val gomokuMenuId:Int=Menus.registerMenu{ p, choice->
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

        when (choice) {
            1,3,5,7 -> if (myTurn) {
                when (choice) {
                    1 -> cur.y = (cur.y - 1 + BOARD) % BOARD
                    3 -> cur.x = (cur.x - 1 + BOARD) % BOARD
                    5 -> cur.x = (cur.x + 1) % BOARD
                    7 -> cur.y = (cur.y + 1) % BOARD
                }
            }
            9 -> if (myTurn) {
                if(state.place(cur.x, cur.y, myStone, me.uuid())) state.turnBlack = !state.turnBlack
            }
            10 -> {
                showConfirmMenu(me) {
                    games.remove(stateKey); Call.hideFollowUpMenu(me.con, gomokuMenuId); return@showConfirmMenu
                }
            }
            11 -> {
                showConfirmMenu(me) {
                    Call.hideFollowUpMenu(me.con, gomokuMenuId); return@showConfirmMenu
                }
            }
        }

        val oppUuid = if (me.uuid() == stateKey.first) stateKey.second else stateKey.first
        showGomokuBoard(me, state)
        Groups.player.find { it.uuid() == oppUuid }?.let { showGomokuBoard(it, state) }
    }

    fun showGomokuEntry(player:Player){
        val uid=player.uuid()
        games.keys.find{ it.first==uid||it.second==uid }?.let{
            showGomokuBoard(player,games[it]!!); return
        }
        val now=System.currentTimeMillis()
        invites.values.forEach{ it.removeIf{ inv-> now-inv.time>300_000 } }

        val alreadySent=invites.values.any{ lst->lst.any{ it.from==uid } }
        val rows=mutableListOf<MenuEntry>()

        invites[uid].orEmpty().forEach{ inv->
            Groups.player.find{ it.uuid()==inv.from }?.let{ sender->
                rows+=MenuEntry("$INV_PREFIX${PluginVars.SECONDARY} ${sender.name()}${PluginVars.RESET}"){
                    if(games.keys.any{ it.first==uid||it.second==uid }) return@MenuEntry
                    showConfirmMenu(player) {
                        if (invites.isNotEmpty()) {
                            startGame(sender.uuid(), uid)
                        }
                        invites[uid]?.clear()
                    }
                }
            }
        }

        Groups.player.filter{ it.uuid()!=uid }.forEach{ p->
            rows+=MenuEntry("${PluginVars.WHITE}${p.name()}${PluginVars.RESET}"){
                if(alreadySent){
                    Call.announce(player.con,"${PluginVars.WARN}${I18nManager.get("gomoku.inv.already",player)}${PluginVars.RESET}")
                }else{
                    showConfirmMenu(player){
                        if(games.keys.any{ it.first==uid||it.second==uid }) return@showConfirmMenu
                        val lst=invites.getOrPut(p.uuid()){ mutableListOf() }
                        if(lst.none{ it.from==uid }) lst+=Invite(uid)
                        Call.announce(player.con,"${PluginVars.INFO}${I18nManager.get("gomoku.inv.sent",player)}${PluginVars.RESET}")
                    }
                }
            }
        }

        MenusManage.createMenu<Unit>(
            title={_,_,_,_->"${PluginVars.GRAY}${I18nManager.get("gomoku.list",player)}${PluginVars.RESET}"},
            desc={_,_,_->""},
            paged=true,
            options={_,_,_->rows}
        )(player,1)
    }

    private fun startGame(u1:String,u2:String){
        val k=key(u1,u2)
        games[k]=GomokuState(white=k.first,black=k.second)
        invites.remove(u1); invites.remove(u2)
        invites.values.forEach{ it.removeIf{ inv-> inv.from==u1||inv.from==u2 } }
        val s=games[k]!!
        Groups.player.find{ it.uuid()==k.first }?.let{ showGomokuBoard(it,s) }
        Groups.player.find{ it.uuid()==k.second }?.let{ showGomokuBoard(it,s) }
    }

    private fun showGomokuBoard(p:Player,state:GomokuState){
        val cur=state.cursors.getOrPut(p.uuid()){ Cursor() }
        val meBlack=p.uuid()==state.black
        val myStone=if(meBlack) Stone.BLACK else Stone.WHITE
        val myTurn =(state.turnBlack&&meBlack)||(!state.turnBlack&&!meBlack) && state.winner==null

        val boardTxt=buildString{
            repeat(BOARD){ y->
                repeat(BOARD){ x->
                    append(
                        when{
                            state.board[y][x]!=Stone.EMPTY -> state.board[y][x].glyph
                            x==cur.x&&y==cur.y            -> ""
                            else                          -> Stone.EMPTY.glyph
                        }
                    )
                }
                append('\n')
            }
        }

        fun b(t:String,e:Boolean)=if(e) "${PluginVars.WHITE}$t${PluginVars.RESET}"
        else   "${PluginVars.SECONDARY}$t${PluginVars.RESET}"

        val buttons=arrayOf(
            arrayOf("", b("\uE804",myTurn), ""),
            arrayOf(b("\uE802",myTurn),"",b("\uE803",myTurn)),
            arrayOf("", b("\uE805",myTurn), ""),
            arrayOf(b(I18nManager.get("gomoku.select",p),myTurn)),
            arrayOf(b(I18nManager.get("gomoku.end",p),true)),
            arrayOf(b(I18nManager.get("gomoku.exit",p),true))
        )

        val info = when{
            state.winner==myStone -> I18nManager.get("gomoku.win",p)
            state.winner!=null    -> I18nManager.get("gomoku.lose",p)
            myTurn                -> I18nManager.get("gomoku.yourturn",p)
            else                  -> I18nManager.get("gomoku.wait",p)
        }

        Call.followUpMenu(
            p.con,gomokuMenuId,
            "${PluginVars.GRAY}${I18nManager.get("gomoku.title",p)}${PluginVars.RESET}",
            "\n${PluginVars.INFO}$info${PluginVars.RESET}\n\n$boardTxt",
            buttons
        )
    }

    private enum class Hint { LOW, HIGH, NONE }

    private data class GuessState(
        val answer: Int = Mathf.random(1, 100),
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
            desc = desc,
            placeholder = "1",
            isNum = true,
            maxChars = 3
        ) { _, input ->
            val number = input.toIntOrNull()

            if (number == null || number !in 1..100) {
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
                if (VoteManager.globalVoteSession != null) {
                    Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                    return@MenuEntry
                }

                showConfirmMenu(player) {
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
                                onResult = { pl, choice -> if (choice == 0) VoteManager.addVote(pl.uuid()) }
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
            title = { _, _, _, _ -> "${PluginVars.GRAY}${I18nManager.get("snapshot.title", player)}${PluginVars.RESET}" },
            desc = { _, _, _ -> "" },
            paged = true,
            options = { _, _, _ -> rows }
        )(player, page)
    }

    fun showSnapshotOptionsMenu(player: Player, file: arc.files.Fi, mapName: String, index: Int) {
        val isAdmin = isCoreAdmin(player.uuid())
        val normalCount = Groups.player.count { isNormal(it.uuid()) }
        val strong = PluginVars.INFO
        val weak = PluginVars.SECONDARY

        val title = "${PluginVars.GRAY}$mapName#$index${PluginVars.RESET}"
        val desc = ""

        val btnVote = MenuEntry("${PluginVars.WHITE}${I18nManager.get("snapshot.vote", player)}${PluginVars.RESET}") {
            if (normalCount <= 1) {
                loadSnapshot(file)
                Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("rtv.changed_alone", player)}${PluginVars.RESET}")
            } else {
                if ((DataManager.getPlayerDataByUuid(player.uuid())?.score ?: 0) < 100) {
                    Call.announce(player.con, "${PluginVars.SUCCESS}${I18nManager.get("noPoints", player)}${PluginVars.RESET}")
                    return@MenuEntry
                }
                if (VoteManager.globalVoteSession != null) {
                    Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                    return@MenuEntry
                }

                showConfirmMenu(player) {
                    VoteManager.createGlobalVote(creator = player) { ok ->
                        if (ok && Vars.state.isGame) loadSnapshot(file)
                    }
                    Groups.player.each { p ->
                        if (p != player && !isBanned(p.uuid())) {
                            val t = "${PluginVars.INFO}${I18nManager.get("rtv.title", p)}${PluginVars.RESET}"
                            val d = "\uE827 ${PluginVars.GRAY}${player.name} ${I18nManager.get("snapshot.vote.desc", p)} $mapName#$index${PluginVars.RESET}"
                            val menu = createConfirmMenu(
                                title = { t },
                                desc = { d },
                                onResult = { pl, choice ->
                                    if (choice == 0) VoteManager.addVote(pl.uuid())
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
        val closeIndex = allRules.size + 2

        when (choice) {
            closeIndex -> Call.hideFollowUpMenu(player.con, rulesMenuId)
            blocksIndex -> showBlocksMenu(player)
            unitsIndex -> showUnitsMenu(player)
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
                    val col = if (banned.contains(b)) PluginVars.SECONDARY else PluginVars.WHITE
                    "$col${b.localizedName}${PluginVars.RESET}"
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
                    val col = if (banned.contains(u)) PluginVars.SECONDARY else PluginVars.WHITE
                    "$col${u.localizedName}${PluginVars.RESET}"
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
            desc  = { _, _, _ -> "" },
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
                    .filter { it.text != "votekick" }
                    .filter { it.text != "over" || player.admin }
                    .filter { it.text != "rules" || player.admin }
                    .filter { it.text != "revert" || player.admin }
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
        val team = PlayerTeamManager.getTeam(uuid)

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
            append("\n${PluginVars.WHITE}${acc.account}${PluginVars.RESET}\n\n")
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
            if (VoteManager.globalVoteSession != null) {
                Call.announce(viewer.con, "${PluginVars.WARN}${I18nManager.get("vote.running", viewer)}${PluginVars.RESET}")
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
                        "${PluginVars.WARN}${I18nManager.get("playerInfo.setban.invalid", viewer)}${PluginVars.RESET}"
                    )
                    return@createTextInput
                }
                DataManager.updatePlayer(acc.id) {
                    it.banUntil = if (seconds == 0L) 0 else System.currentTimeMillis() + seconds * 1000
                }
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
        }

        MenusManage.createMenu<Unit>(
            title = { _, p, t, _ -> "${PluginVars.GRAY}${I18nManager.get("map.mode.choose", player)} $p/$t${PluginVars.RESET}" },
            desc  = { _, _, _ -> "" },
            paged = false,
            options = { _, _, _ -> rows }
        )(player, page)
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
                isCurrent -> "\uE829"
                isNext    -> "\uE809"
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
        val normalCount = Groups.player.count { isNormal(it.uuid()) }
        val mapMode = TagUtil.getMode(map.description())
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
                if (VoteManager.globalVoteSession != null) {
                    Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                    return@MenuEntry
                }

                showConfirmMenu(player) {
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
        if (player.team() != Team.derelict || isBanned(player.uuid())) return
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
        val currentTeam = player.team()
        if (currentTeam != Team.derelict && currentTeam.data().hasCore()) return

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
                if (VoteManager.globalVoteSession != null) {
                    Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("vote.running", player)}${PluginVars.RESET}")
                    return@MenuEntry
                }
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
            if (p != viewer && p != target && !isBanned(p.uuid())) {
                val title = "${PluginVars.WARN}${I18nManager.get("playerInfo.votekick.title", p)}${PluginVars.RESET}"
                val desc = "\uE817 ${PluginVars.GRAY}${viewer.name} ${
                    I18nManager.get("playerInfo.votekick.desc", p)
                } ${target.name()}${PluginVars.RESET}"

                val voteMenu = createConfirmMenu(
                    title = { title },
                    desc = { desc },
                    onResult = { pl, choice ->
                        if (choice == 0) {
                            VoteManager.addVote(pl.uuid())
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

}
