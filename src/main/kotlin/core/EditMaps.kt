package plugin.core

import mindustry.Vars
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.io.MapIO
import mindustry.maps.Map
import plugin.snow.PluginVars
import java.util.concurrent.ConcurrentLinkedQueue

object EditMaps {

    private data class EditRequest(
        val map: Map,
        val editorUuid: String,
        val newName: String? = null,
        val newDesc: String? = null,
        val newAuthor: String? = null
    )

    private val pending = ConcurrentLinkedQueue<EditRequest>()

    fun requestEdit(
        player: Player,
        map: Map,
        newName: String? = null,
        newDesc: String? = null,
        newAuthor: String? = null
    ): Boolean {
        if (newName.isNullOrBlank() && newDesc.isNullOrBlank() && newAuthor.isNullOrBlank()) return false
        if (!hasPermission(player, map)) return false

        pending += EditRequest(map, player.uuid(), newName, newDesc, newAuthor)
        Call.announce(player.con,
            "${PluginVars.INFO}${I18nManager.get("mapEdit.queued", player)}${PluginVars.RESET}")
        return true
    }

    fun applyEdit(): Int {
        var applied = 0
        while (true) {
            val job = pending.poll() ?: break
            kotlin.runCatching { process(job) }
                .onSuccess { applied++ }
                .onFailure { it.printStackTrace() }
        }
        if (applied > 0) Vars.maps.reload()
        return applied
    }

    private fun process(job: EditRequest) {
        val tags = job.map.tags
        job.newName?.let   { tags.put("name",        it) }
        job.newDesc?.let   { tags.put("description", it) }
        job.newAuthor?.let { tags.put("author",      it) }
        MapIO.writeMap(job.map.file, job.map)
        DataManager.requestSave()
    }

    private fun hasPermission(player: Player, map: Map): Boolean {
        val uploaderId = DataManager.maps[map.file.name()]?.uploaderId
        return player.admin() || uploaderId == DataManager.getIdByUuid(player.uuid())
    }
}
