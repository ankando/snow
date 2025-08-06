package plugin.snow

import arc.Core
import mindustry.Vars
import mindustry.content.Blocks
import mindustry.content.Items
import mindustry.gen.Building
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
        for (consumer in block.consumers) {
            when (consumer) {
                is ConsumeItems -> {
                    if (block == Blocks.thoriumReactor) {
                        Core.app.post {
                            build.items.add(
                                Items.thorium,
                                30 - build.items.get(Items.thorium)
                            )
                        }
                        continue
                    }

                    for (stack in consumer.items) {
                        if (build.items.get(stack.item) < 2000) {
                            Core.app.post {
                                build.items.add(stack.item, 2000)
                            }
                        }
                    }
                }

                is ConsumeLiquid -> {
                    if (build.liquids.get(consumer.liquid) < 2000) {
                        Core.app.post {
                            build.liquids.add(consumer.liquid, 2000f)
                        }
                    }
                }

                is ConsumeLiquids -> {
                    for (stack in consumer.liquids) {
                        if (build.liquids.get(stack.liquid) < 2000) {
                            Core.app.post {
                                build.liquids.add(stack.liquid, 2000f)
                            }
                        }
                    }
                }

                is ConsumeItemFilter -> {
                    for (item in Vars.content.items().select(consumer.filter)) {
                        if (build.items.get(item) < 2000) {
                            Core.app.post {
                                build.items.add(item, 2000)
                            }
                        }
                    }
                }

                is ConsumeLiquidFilter -> {
                    for (liquid in Vars.content.liquids().select(consumer.filter)) {
                        if (build.liquids.get(liquid) < 2000) {
                            Core.app.post {
                                build.liquids.add(liquid, 2000f)
                            }
                        }
                    }
                }
            }
        }
    }
}
