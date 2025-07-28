package plugin.core

import mindustry.Vars

object PermissionManager {

    fun isBanned(uuid: String): Boolean {
        val data = DataManager.getPlayerDataByUuid(uuid)
        return data == null || data.banUntil > System.currentTimeMillis()
    }

    fun isCoreAdmin(uuid: String): Boolean {
        return DataManager.getPlayerDataByUuid(uuid)
            ?.uuids
            ?.any { Vars.netServer.admins.getInfo(it)?.admin == true } == true
    }

}
