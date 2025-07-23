package plugin.core

import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.ui.Menus
import plugin.core.PermissionManager.isBanned
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
        val pageMap = mutableMapOf<String, Int>()
        val dataCache = mutableMapOf<String, T?>()

        val menuHolder = object {
            lateinit var show: (Player, Int) -> Unit
            var menuId: Int = -1
        }

        menuHolder.menuId = Menus.registerMenu { p, choice ->
            if (p == null || isBanned(p.uuid())) return@registerMenu

                val uuid = p.uuid()
                val currentPage = pageMap[uuid] ?: 1
                val userData = dataCache[uuid]
                val entries = options(p, userData, currentPage)

                if (!paged) {
                    when (choice) {
                        0 -> Call.hideFollowUpMenu(p.con, menuHolder.menuId)
                        in 1..entries.size -> entries[choice - 1].onClick?.invoke(p)
                    }
                } else {
                    val totalPages = max(1, (entries.size + perPage - 1) / perPage)
                    val page = currentPage.coerceIn(1, totalPages)
                    val start = (page - 1) * perPage
                    val end = minOf(start + perPage, entries.size)

                    when (choice) {
                        0 -> menuHolder.show(p, if (page == 1) totalPages else page - 1)
                        1 -> Call.hideFollowUpMenu(p.con, menuHolder.menuId)
                        2 -> menuHolder.show(p, if (page == totalPages) 1 else page + 1)
                        in 3 until (end - start + 3) -> {
                            val idx = start + (choice - 3)
                            if (idx in entries.indices) entries[idx].onClick?.invoke(p)
                        }
                    }
                }
            }

        menuHolder.show = { player, page ->
                val uuid = player.uuid()
                val userData = extraData?.invoke(player)
                dataCache[uuid] = userData

                val entries = options(player, userData, page)

                if (!paged) {
                    val buttons = mutableListOf<Array<String?>>()
                    buttons.add(arrayOf("${PluginVars.SECONDARY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"))
                    entries.forEach { buttons.add(arrayOf(it.label)) }

                    Call.followUpMenu(
                        player.con,
                        menuHolder.menuId,
                        title(player, 1, 1, userData),
                        desc?.invoke(player, 1, userData) ?: "",
                        buttons.toTypedArray()

                    )
                } else {
                    val totalPages = max(1, (entries.size + perPage - 1) / perPage)
                    val currentPage = when {
                        page < 1 -> totalPages
                        page > totalPages -> 1
                        else -> page
                    }
                    pageMap[uuid] = currentPage

                    val start = (currentPage - 1) * perPage
                    val end = minOf(start + perPage, entries.size)
                    val pageEntries = entries.subList(start, end)

                    val prev = if (totalPages == 1 || currentPage == 1) "" else "${PluginVars.SECONDARY}${PluginVars.ICON_LEFT}${PluginVars.RESET}"
                    val next = if (totalPages == 1 || currentPage == totalPages) "" else "${PluginVars.SECONDARY}${PluginVars.ICON_NEXT}${PluginVars.RESET}"

                    val buttons = mutableListOf<Array<String?>>()
                    buttons.add(arrayOf(prev, "${PluginVars.SECONDARY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}", next))
                    pageEntries.forEach { buttons.add(arrayOf(it.label)) }

                    Call.followUpMenu(
                        player.con,
                        menuHolder.menuId,
                        title(player, currentPage, totalPages, userData),
                        desc?.invoke(player, currentPage, userData) ?: "",
                        buttons.toTypedArray()
                    )
            }
        }

        return menuHolder.show
    }

    fun createConfirmMenu(
        title: (Player) -> String,
        desc: (Player) -> String,
        onResult: (Player, Int?) -> Unit,
        yesText: String = "${PluginVars.GRAY}${PluginVars.ICON_OK}${PluginVars.RESET}",
        noText: String = "${PluginVars.GRAY}${PluginVars.ICON_CLOSE}${PluginVars.RESET}"
    ): (Player) -> Unit {
        val menuHolder = object {
            var menuId: Int = -1
            lateinit var show: (Player) -> Unit
        }

        menuHolder.menuId = Menus.registerMenu { p, choice ->
            if (p != null && !isBanned(p.uuid())) {
                Call.hideFollowUpMenu(p.con, menuHolder.menuId)
                onResult(p, choice)
            }
        }

        menuHolder.show = { player ->
            val buttons = arrayOf(arrayOf(yesText, noText))
            Call.followUpMenu(
                player.con,
                menuHolder.menuId,
                title(player),
                desc(player),
                buttons
            )
        }

        return menuHolder.show
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
