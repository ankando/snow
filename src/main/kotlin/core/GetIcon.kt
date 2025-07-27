package plugin.core

import mindustry.gen.Iconc
import mindustry.type.UnitType
import mindustry.world.Block

object GetIcon {
    fun getUnitIcon(unit: UnitType): String {
        return try {
            val camel = unit.name.replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }
            val icon = Iconc::class.java.getField("unit${camel.replaceFirstChar { it.uppercase() }}").get(null)
            icon.toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun getBuildingIcon(block: Block): String {
        return try {
            val type = block.contentType.name.lowercase()
            val camel = block.name.replace(Regex("-([a-z])")) { it.groupValues[1].uppercase() }
            val icon = Iconc::class.java.getField("${type}${camel.replaceFirstChar { it.uppercase() }}").get(null)
            icon.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
