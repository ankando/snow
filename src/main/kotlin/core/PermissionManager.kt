package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Player
import plugin.snow.PluginVars

enum class PermissionLevel {
    BANNED,
    NORMAL,
    MEMBER,
    CORE_ADMIN,
    GAME_ADMIN
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

    fun isGameAdmin(player: Player): Boolean {
        val uuid = player.uuid()
        fixAdminStatus(uuid, player)
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return false
        return isCoreAdmin(uuid) && data.isAdmin && player.admin
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
        if (isBanned(uuid)) return PermissionLevel.BANNED
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return PermissionLevel.NORMAL
        val isCoreAdmin = isCoreAdmin(uuid)

        if (player != null) fixAdminStatus(uuid, player)

        return when {
            isCoreAdmin && data.isAdmin && player?.admin == true -> PermissionLevel.GAME_ADMIN
            isCoreAdmin -> PermissionLevel.CORE_ADMIN
            data.score > 20 -> PermissionLevel.MEMBER
            else -> PermissionLevel.NORMAL
        }
    }
}
