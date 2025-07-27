package plugin.core

import arc.math.geom.Point2
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.Log
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.ConstructBlock
import plugin.snow.PluginVars

object RevertBuild {
    object RevertState {
        val historyMode = ObjectMap<String, Boolean>()  // UUID -> showHistory

        fun getHistoryMode(uuid: String): Boolean {
            return historyMode.get(uuid, false)
        }

        fun setHistoryMode(uuid: String, value: Boolean) {
            historyMode.put(uuid, value)
        }
    }

    private const val RECORD_WINDOW_SEC = 300

    private data class BlockEdit(
        val block: Block,
        val teamId: Int,
        val rotation: Int,
        val timeNanos: Long,
        val destroy: Boolean
    )

    private val lastEdits = ObjectMap<String, ObjectMap<Point2, BlockEdit>>()

    fun getAllPlayersWithEdits() = lastEdits.keys().toList()

    fun recordBuild(player: Player, tile: Tile) {
        val tb = tile.build
        if (tb is ConstructBlock.ConstructBuild) {
            if (tb.progress == 0f && tb.prevBuild != null) {
                for (old in tb.prevBuild) recordRemoveInternal(player, old.tile, old.block, old.rotation)
                return
            }
            if (tb.progress < 1f) return
        }
        recordBuildInternal(player, tile)
    }

    fun recordRemove(player: Player, tile: Tile) {
        val uuid = player.uuid()
        val pos = Point2(tile.x.toInt(), tile.y.toInt())
        val map = lastEdits.get(uuid) ?: ObjectMap<Point2, BlockEdit>().also { lastEdits.put(uuid, it) }
        val now = System.nanoTime()
        val existing = map.get(pos)
        if (existing != null && !existing.destroy) {
            map.put(pos, existing.copy(destroy = true, timeNanos = now))
        } else {
            map.put(pos, BlockEdit(Blocks.air, tile.team().id, 0, now, true))
        }
    }

    fun restorePlayerEditsWithinSeconds(uuid: String, seconds: Int) = restoreEdits(listOf(uuid), seconds)
    fun restoreAllPlayersEditsWithinSeconds(seconds: Int) = restoreEdits(getAllPlayersWithEdits(), seconds)
    fun clearAll() = lastEdits.clear()

    fun showHistory(caller: Player?, x: Short, y: Short, size: Int = 100) {
        val nowNanos = System.nanoTime()
        val cutoffNanos = RECORD_WINDOW_SEC * 1_000_000_000L
        val sb = StringBuilder()
            .append("${PluginVars.WHITE}${PluginVars.SECONDARY}($x,$y)${PluginVars.RESET}")

        var count = 0
        for (uuid in lastEdits.keys()) {
            val edits = lastEdits.get(uuid) ?: continue
            val entryIter = edits.entries()
            while (entryIter.hasNext() && count < size) {
                val entry = entryIter.next()
                val pos: Point2 = entry.key
                if (pos.x.toShort() != x || pos.y.toShort() != y) continue
                val edit: BlockEdit = entry.value
                if (nowNanos - edit.timeNanos > cutoffNanos) continue

                count += 1
                val name = Vars.netServer.admins
                    .getInfoOptional(uuid)
                    ?.lastName ?: "unknown"
                val icon = GetIcon.getBuildingIcon(edit.block)
                val deltaSec = ((nowNanos - edit.timeNanos) / 1_000_000_000L).toString()
                val action = if (edit.destroy) I18nManager.get("revertbuild.removed", caller) else I18nManager.get("revertbuild.built", caller)

                sb.append(" ")
                    .append(name)
                    .append(" ")
                    .append("${PluginVars.SECONDARY}${deltaSec}s${PluginVars.RESET} ")
                    .append(action)
                    .append(" ")
                    .append(icon)
            }
        }

        val message = sb.toString()
        if (caller == null) {
            Log.info(message)
        } else {
            caller.sendMessage(message)
        }
    }

    private fun recordBuildInternal(player: Player, tile: Tile) {
        val build = tile.build ?: return
        if (!build.isValid || tile.block() === Blocks.air) return
        val uuid = player.uuid()
        val pos = Point2(tile.x.toInt(), tile.y.toInt())
        val map = lastEdits.get(uuid) ?: ObjectMap<Point2, BlockEdit>().also { lastEdits.put(uuid, it) }
        map.put(pos, BlockEdit(tile.block(), build.team.id, build.rotation, System.nanoTime(), false))
    }

    private fun recordRemoveInternal(actor: Player, tile: Tile, removedBlock: Block, removedRotation: Int) {
        val uuid = actor.uuid()
        val pos = Point2(tile.x.toInt(), tile.y.toInt())
        val map = lastEdits.get(uuid) ?: ObjectMap<Point2, BlockEdit>().also { lastEdits.put(uuid, it) }
        map.put(pos, BlockEdit(removedBlock, tile.team().id, removedRotation, System.nanoTime(), true))
    }

    private fun restoreEdits(players: List<String>, seconds: Int) {
        val now = System.nanoTime()
        val cutoff = seconds * 1_000_000_000L
        val purge = Seq<String>()
        players.forEach { uuid ->
            lastEdits[uuid]?.let { edits ->
                val toDel = Seq<Point2>()
                for (entry in edits) {
                    val pos = entry.key
                    val edit = entry.value
                    if (now - edit.timeNanos > cutoff) continue
                    Vars.world.tile(pos.x, pos.y)?.let { tile ->
                        if (edit.destroy && edit.block.canBeBuilt()) Call.setTile(tile, edit.block, Team.get(edit.teamId), edit.rotation)
                        else Call.setTile(tile, Blocks.air, Team.derelict, 0)
                        toDel.add(Point2(pos))
                    }
                }
                toDel.forEach { edits.remove(it) }
                if (edits.isEmpty) purge.add(uuid)
            }
        }
        purge.forEach { lastEdits.remove(it) }
    }
}
