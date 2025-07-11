package plugin.core

import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.ui.Menus
import plugin.core.PermissionManager.verifyPermissionLevel
import plugin.snow.PluginVars
import kotlin.math.max

object MenusManage {
    fun <T> createMenu(
        title: (Player, Int, Int, T?) -> String,
        desc: ((Player, Int, T?) -> String)? = null,
        perPage: Int = PluginVars.MENU_BUTTONS_PER_PAGE,
        paged: Boolean = true,
        options: (Player, T?, Int) -> List<MenuEntry>,
        extraData: ((Player) -> T?)? = null
    ): (Player, Int) -> Unit {
        val menuPageMap = mutableMapOf<String, Int>()
        val dataCache = mutableMapOf<String, T?>()

        val menuHolder = object {
            lateinit var showMenu: (Player, Int) -> Unit
            var menuId: Int = -1
        }

        menuHolder.menuId = Menus.registerMenu { p, choice ->
            if (p == null) return@registerMenu
            verifyPermissionLevel(p, PermissionLevel.NORMAL) {
                val curPage = menuPageMap[p.uuid()] ?: 1
                val myData = dataCache[p.uuid()]
                val allData = options(p, myData, curPage)

                if (!paged) {
                    when (choice) {
                        0 -> Call.hideFollowUpMenu(p.con, menuHolder.menuId)
                        in 1..allData.size -> allData[choice - 1].onClick?.invoke(p)
                    }
                } else {
                    val totalPages = max(1, (allData.size + perPage - 1) / perPage)
                    val currentPage = curPage.coerceIn(1, totalPages)
                    val start = (currentPage - 1) * perPage
                    val end = minOf(start + perPage, allData.size)

                    when (choice) {
                        0 -> {
                            val prev = if (totalPages == 1) 1 else if (currentPage == 1) totalPages else currentPage - 1
                            menuHolder.showMenu(p, prev)
                        }

                        1 -> Call.hideFollowUpMenu(p.con, menuHolder.menuId)
                        2 -> {
                            val next = if (totalPages == 1) 1 else if (currentPage == totalPages) 1 else currentPage + 1
                            menuHolder.showMenu(p, next)
                        }

                        in 3 until (end - start + 3) -> {
                            val idx = start + (choice - 3)
                            if (idx in allData.indices) allData[idx].onClick?.invoke(p)
                        }
                    }
                }
            }
        }

        menuHolder.showMenu = { player, page ->
            verifyPermissionLevel(player, PermissionLevel.NORMAL) {
                val myData = extraData?.invoke(player)
                dataCache[player.uuid()] = myData

                val allData = options(player, myData, page)

                if (!paged) {
                    val buttons = mutableListOf<Array<String?>>()
                    buttons.add(arrayOf(PluginVars.SECONDARY + PluginVars.ICON_CLOSE + PluginVars.RESET))
                    for (entry in allData) {
                        buttons.add(arrayOf(entry.label))
                    }
                    Call.followUpMenu(
                        player.con,
                        menuHolder.menuId,
                        title(player, 1, 1, myData),
                        desc?.invoke(player, 1, myData) ?: "",
                        buttons.toTypedArray()
                    )
                } else {
                    val totalPages = max(1, (allData.size + perPage - 1) / perPage)
                    val onlyOnePage = totalPages == 1
                    val currentPage = when {
                        page < 1 -> totalPages
                        page > totalPages -> 1
                        else -> page
                    }
                    menuPageMap[player.uuid()] = currentPage

                    val start = (currentPage - 1) * perPage
                    val end = minOf(start + perPage, allData.size)
                    val pageData = allData.subList(start, end)

                    val prevLabel =
                        if (onlyOnePage || currentPage == 1) "" else PluginVars.SECONDARY + PluginVars.ICON_PREV + PluginVars.RESET
                    val nextLabel =
                        if (onlyOnePage || currentPage == totalPages) "" else PluginVars.SECONDARY + PluginVars.ICON_NEXT + PluginVars.RESET

                    val buttons = mutableListOf<Array<String?>>()
                    buttons.add(
                        arrayOf(
                            prevLabel,
                            PluginVars.SECONDARY + PluginVars.ICON_CLOSE + PluginVars.RESET,
                            nextLabel
                        )
                    )
                    for (entry in pageData) {
                        buttons.add(arrayOf(entry.label))
                    }
                    Call.followUpMenu(
                        player.con,
                        menuHolder.menuId,
                        title(player, currentPage, totalPages, myData),
                        desc?.invoke(player, currentPage, myData) ?: "",
                        buttons.toTypedArray()
                    )
                }
            }
        }

        return menuHolder.showMenu
    }


    fun createConfirmMenu(
        title: String,
        desc: String,
        onResult: (Player, Int?) -> Unit,
        yesText: String = PluginVars.GRAY + PluginVars.ICON_PREV + PluginVars.RESET,
        noText: String = PluginVars.GRAY + PluginVars.ICON_CLOSE + PluginVars.RESET
    ): (Player) -> Unit {
        return { player ->
            verifyPermissionLevel(player, PermissionLevel.NORMAL) {
                val menuId = Menus.registerMenu { p, choice ->
                    if (p == null) return@registerMenu
                    verifyPermissionLevel(p, PermissionLevel.NORMAL) {
                        onResult(p, choice)
                    }
                }
                Call.menu(player.con, menuId, title, desc, arrayOf(arrayOf(yesText, noText)))
            }
        }
    }


    fun createTextInput(
        title: String,
        desc: String,
        placeholder: String,
        isNum: Boolean,
        maxChars: Int = PluginVars.MENU_TEXT_INPUT_MAX_CHARS,
        onInput: (Player, String) -> Unit
    ): (Player) -> Unit {
        return { player ->
            val inputId = Menus.registerTextInput { p, input ->
                if (p != null && !input.isNullOrBlank()) onInput(p, input)
            }
            Call.textInput(player.con, inputId, title, desc, maxChars, placeholder, isNum)
        }
    }
}

data class MenuEntry(
    val label: String,
    val onClick: ((Player) -> Unit)? = null
)
