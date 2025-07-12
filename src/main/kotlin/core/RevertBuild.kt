package plugin.core

import arc.math.geom.Point2
import arc.struct.ObjectMap
import arc.struct.Seq
import mindustry.Vars
import mindustry.game.Team
import mindustry.gen.Player
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.ConstructBlock

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
        val build = tile.build ?: return
        if (!build.isValid || tile.block() === mindustry.content.Blocks.air) return
        if (!tile.block().canBeBuilt() || build is ConstructBlock.ConstructBuild) return

        val uuid = player.uuid()
        val pos = Point2(tile.x.toInt(), tile.y.toInt())
        val map = lastEdits.get(uuid) ?: ObjectMap<Point2, BlockEdit>().also { lastEdits.put(uuid, it) }

        map.put(pos, BlockEdit(tile.block(), build.team.id, build.rotation, System.nanoTime()))
    }

    fun restorePlayerEditsWithinSeconds(uuid: String, seconds: Int) {
        val edits = lastEdits[uuid] ?: return
        val now = System.nanoTime()
        val cutoff = seconds * 1_000_000_000L
        val toRemove = Seq<Point2>()

        for (entry in edits.entries()) {
            val pos = entry.key ?: continue
            val edit = entry.value ?: continue
            if (now - edit.timeNanos > cutoff) continue

            val tile = Vars.world.tile(pos.x, pos.y) ?: continue
            if (tile.block() === mindustry.content.Blocks.air || tile.build == null) {
                tile.setNet(edit.block, Team.get(edit.teamId), edit.rotation)
                tile.build?.updateTile()
            }

            toRemove.add(pos)
        }

        for (pos in toRemove) {
            edits.remove(pos)
        }

        if (edits.isEmpty) {
            lastEdits.remove(uuid)
        }
    }

    fun restoreAllPlayersEditsWithinSeconds(seconds: Int) {
        val now = System.nanoTime()
        val cutoff = seconds * 1_000_000_000L
        val toRemoveAll = Seq<String>()

        for (uuid in lastEdits.keys()) {
            val edits = lastEdits[uuid] ?: continue
            val toRemove = Seq<Point2>()

            for (entry in edits.entries()) {
                val pos = entry.key ?: continue
                val edit = entry.value ?: continue
                if (now - edit.timeNanos > cutoff) continue

                val tile = Vars.world.tile(pos.x, pos.y) ?: continue
                if (tile.block() === mindustry.content.Blocks.air || tile.build == null) {
                    tile.setNet(edit.block, Team.get(edit.teamId), edit.rotation)
                    tile.build?.updateTile()
                }

                toRemove.add(pos)
            }

            for (pos in toRemove) {
                edits.remove(pos)
            }

            if (edits.isEmpty) {
                toRemoveAll.add(uuid)
            }
        }

        for (uuid in toRemoveAll) {
            lastEdits.remove(uuid)
        }
    }

    fun clearAll() {
        lastEdits.clear()
    }
}
