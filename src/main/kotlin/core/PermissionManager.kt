package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Player
import plugin.snow.PluginVars

enum class PermissionLevel {
    BANNED,
    NORMAL,
    CORE_ADMIN,
}

object PermissionManager {

    fun isBanned(uuid: String): Boolean {
        val data = DataManager.getPlayerDataByUuid(uuid)
        return data == null || data.banUntil > System.currentTimeMillis()
    }

    fun isNormal(uuid: String): Boolean {
        return !isBanned(uuid) && DataManager.getPlayerDataByUuid(uuid) != null
    }

    fun isCoreAdmin(uuid: String): Boolean {
        return DataManager.getPlayerDataByUuid(uuid)
            ?.uuids
            ?.any { Vars.netServer.admins.getInfo(it)?.admin == true } == true
    }

    fun fixAdminStatus(uuid: String, player: Player) {
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return
        val isCoreAdmin = isCoreAdmin(uuid)

        when {
            !isCoreAdmin -> {
                if (data.isAdmin) {
                    data.isAdmin = false
                    DataManager.requestSave()
                }
                player.admin = false
            }
            else -> {
                if (!data.isAdmin && player.admin) player.admin = false
                if (data.isAdmin && !player.admin) player.admin = true
            }
        }
    }

    fun verifyPermissionLevel(player: Player, required: PermissionLevel, exec: () -> Unit) {
        val uuid = player.uuid()
        if (getLevel(uuid, player).ordinal < required.ordinal) {
            Call.announce(
                player.con,
                "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}"
            )
        } else {
            exec()
        }
    }

    fun getLevel(uuid: String, player: Player? = null): PermissionLevel {
        if (DataManager.getPlayerDataByUuid(uuid) == null) return PermissionLevel.NORMAL
        if (isBanned(uuid)) return PermissionLevel.BANNED
        val isCoreAdmin = isCoreAdmin(uuid)

        if (player != null) fixAdminStatus(uuid, player)

        return when {
            isCoreAdmin -> PermissionLevel.CORE_ADMIN
            else -> PermissionLevel.NORMAL
        }
    }
}
