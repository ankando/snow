package plugin.snow

import arc.Core
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Items
import mindustry.gen.Building
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.world.blocks.defense.turrets.Turret
import mindustry.world.consumers.*
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

object InfinityWar {

    var enabled: Boolean = true

    private val consumeBuildings: MutableSet<WeakReference<Building>> =
        Collections.newSetFromMap(ConcurrentHashMap())

    var nextUpdateBuildTime: Long = System.currentTimeMillis()
    fun onBlockBuildEnd(build: Building?) {
        if (!enabled || build == null) return
        processBuild(build)
    }

    @Synchronized
    fun updateBuilding() {
        if (!enabled) return
        consumeBuildings.removeIf { ref -> ref.get() == null || !ref.get()?.isAdded!! }

        Groups.build.each { build ->
            if (isFillable(build)) {
                consumeBuildings.add(WeakReference(build))
            }
        }
    }

    fun fillBuilding() {
        if (!enabled) return

        for (weak in consumeBuildings) {
            val build = weak.get() ?: continue
            processBuild(build)
        }
    }
    private fun isFillable(build: Building?): Boolean {
        if (build == null) return false
        if (build.block is Turret) return false
        if (consumeBuildings.any { weak -> weak.get() === build }) {
            return false
        }

        for (consumer in build.block.consumers) {
            when (consumer) {
                is ConsumeItems -> return true
                is ConsumeLiquid -> return true
                is ConsumeLiquids -> return true
                is ConsumeItemFilter -> return true
                is ConsumeLiquidFilter -> return true
            }
        }
        return false
    }
    private fun processBuild(build: Building) {
        val block = build.block
        if (build.block is Turret) return

        var changed = false

        for (consumer in block.consumers) {
            when (consumer) {
                is ConsumeItems -> {
                    if (block == Blocks.thoriumReactor) {
                        if (build.items.get(Items.thorium) < 30) {
                            build.items.add(Items.thorium, 30 - build.items.get(Items.thorium))
                            changed = true
                        }
                        continue
                    }
                    for (stack in consumer.items) {
                        if (build.items.get(stack.item) < 2000) {
                            build.items.add(stack.item, 2000)
                            changed = true
                        }
                    }
                }

                is ConsumeLiquid -> {
                    if (build.liquids.get(consumer.liquid) < 2000) {
                        build.liquids.add(consumer.liquid, 2000f)
                        changed = true
                    }
                }

                is ConsumeLiquids -> {
                    for (stack in consumer.liquids) {
                        if (build.liquids.get(stack.liquid) < 2000) {
                            build.liquids.add(stack.liquid, 2000f)
                            changed = true
                        }
                    }
                }

                is ConsumeItemFilter -> {
                    for (item in Vars.content.items().select(consumer.filter)) {
                        if (build.items.get(item) < 2000) {
                            build.items.add(item, 2000)
                            changed = true
                        }
                    }
                }

                is ConsumeLiquidFilter -> {
                    for (liquid in Vars.content.liquids().select(consumer.filter)) {
                        if (build.liquids.get(liquid) < 2000) {
                            build.liquids.add(liquid, 2000f)
                            changed = true
                        }
                    }
                }
            }
        }

        if (changed) {
            try {
                val syncStreamField = Vars.netServer::class.java.getDeclaredField("syncStream").apply { isAccessible = true }
                val dataStreamField = Vars.netServer::class.java.getDeclaredField("dataStream").apply { isAccessible = true }
                val syncStream = syncStreamField.get(Vars.netServer) as arc.util.io.ReusableByteOutStream
                val dataStream = dataStreamField.get(Vars.netServer) as java.io.DataOutputStream

                Core.app.post {
                    try {
                        syncStream.reset()
                        dataStream.writeInt(build.pos())
                        dataStream.writeShort(build.block.id.toInt())
                        build.writeAll(arc.util.io.Writes.get(dataStream))
                        dataStream.flush()
                        Call.blockSnapshot(1.toShort(), syncStream.toByteArray())
                        syncStream.reset()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

}
