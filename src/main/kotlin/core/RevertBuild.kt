package plugin.core

import arc.math.geom.Point2
import arc.struct.ObjectMap
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Player
import mindustry.world.Block
import mindustry.world.Tile

object RevertBuild {
    private class BlockEdit(
        val block: Block,
        val teamId: Int,
        val rotation: Int,
        val timeNanos: Long
    )

    private val lastEdits = ObjectMap<String, ObjectMap<Point2, BlockEdit>>()
    fun getAllPlayersWithEdits(): List<String> = lastEdits.keys().toList()
    fun recordRemove(player: Player, tile: Tile) {
        if (tile.build == null || !tile.build!!.isValid) return
        if (tile.block() === mindustry.content.Blocks.air) return
        if (tile.build is mindustry.world.blocks.ConstructBlock.ConstructBuild && tile.build.block.canBeBuilt()) return

        val uuid = player.uuid()
        val pos = Point2(tile.x.toInt(), tile.y.toInt())
        val block = tile.block()
        val rotation = tile.build!!.rotation
        val teamId = tile.build!!.team.id
        val map = lastEdits.get(uuid) ?: ObjectMap<Point2, BlockEdit>().also { lastEdits.put(uuid, it) }
        map.put(pos, BlockEdit(block, teamId, rotation, System.nanoTime()))
    }

    fun restorePlayerEditsWithinSeconds(uuid: String, seconds: Int) {
        val edits = lastEdits[uuid] ?: return
        val now = System.nanoTime()
        val cutoff = seconds.toLong() * 1_000_000_000L

        for (entry in edits.entries()) {
            val pos = entry.key ?: continue
            val edit = entry.value ?: continue
            if (now - edit.timeNanos > cutoff) continue

            val tile = Vars.world.tile(pos.x, pos.y) ?: continue
            if (tile.block() === mindustry.content.Blocks.air || tile.build == null) {
                val team = Team.get(edit.teamId)
                tile.setNet(edit.block, team, edit.rotation)
                tile.build?.updateTile()
            }
        }
        lastEdits.remove(uuid)
    }


    fun restoreAllPlayersEditsWithinSeconds(seconds: Int) {
        val now = System.nanoTime()
        val cutoff = seconds.toLong() * 1_000_000_000L
        for (uuid in lastEdits.keys()) {
            val edits = lastEdits[uuid] ?: continue
            for (entry in edits.entries()) {
                val pos = entry.key ?: continue
                val edit = entry.value ?: continue
                if (now - edit.timeNanos > cutoff) continue

                val tile = Vars.world.tile(pos.x, pos.y) ?: continue
                if (tile.block() === mindustry.content.Blocks.air || tile.build == null) {
                    val team = Team.get(edit.teamId)
                    tile.setNet(edit.block, team, edit.rotation)
                    tile.build?.updateTile()
                }
            }
        }
        lastEdits.clear()
    }

    fun clearAll() {
        lastEdits.clear()
    }
}
