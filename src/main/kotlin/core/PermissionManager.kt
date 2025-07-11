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

    /*
        fun isMember(uuid: String): Boolean {
            val data = DataManager.getPlayerDataByUuid(uuid)
            return data != null && !isBanned(uuid) && data.score > 20
        }
     */
    fun isNormal(uuid: String): Boolean {
        val data = DataManager.getPlayerDataByUuid(uuid)
        return data != null && !isBanned(uuid)
    }

    fun isCoreAdmin(uuid: String): Boolean {
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return false
        return data.uuids.any { Vars.netServer.admins.getInfo(it)?.admin == true }
    }

    fun fixAdminStatus(uuid: String, player: Player) {
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return
        val coreAdmin = data.uuids.any { Vars.netServer.admins.getInfo(it)?.admin == true }
        if (!coreAdmin) {
            if (data.isAdmin) {
                data.isAdmin = false
                DataManager.requestSave()
            }
            if (player.admin) player.admin = false
        } else {
            if (!data.isAdmin && player.admin) player.admin = false
            if (data.isAdmin && !player.admin) player.admin = true
        }
    }

    fun isGameAdmin(player: Player): Boolean {
        val uuid = player.uuid()
        if (uuid == null) return false
        fixAdminStatus(uuid, player)
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return false
        val coreAdmin = data.uuids.any { Vars.netServer.admins.getInfo(it)?.admin == true }
        return coreAdmin && data.isAdmin && player.admin
    }

    fun verifyPermissionLevel(
        player: Player,
        required: PermissionLevel,
        exec: () -> Unit
    ) {
        val uuid = player.uuid()
        val level = getLevel(uuid, player)
        if (level.ordinal < required.ordinal) {
            Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("no.permission", player)}${PluginVars.RESET}")
            return
        }
        exec()
    }

    fun getLevel(uuid: String, player: Player? = null): PermissionLevel {
        if (isBanned(uuid)) return PermissionLevel.BANNED
        val data = DataManager.getPlayerDataByUuid(uuid) ?: return PermissionLevel.NORMAL
        val coreAdmin = data.uuids.any { Vars.netServer.admins.getInfo(it)?.admin == true }
        if (player != null) fixAdminStatus(uuid, player)
        return when {
            coreAdmin && data.isAdmin && player != null && player.admin -> PermissionLevel.GAME_ADMIN
            coreAdmin -> PermissionLevel.CORE_ADMIN
            data.score > 20 -> PermissionLevel.MEMBER
            else -> PermissionLevel.NORMAL
        }
    }
}
