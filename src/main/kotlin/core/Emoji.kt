package plugin.core

import arc.files.Fi
import arc.graphics.Color
import arc.graphics.Pixmap
import arc.util.Log
import mindustry.Vars
import mindustry.game.MapObjectives
import mindustry.gen.Call
import mindustry.gen.Player
import mindustry.graphics.Layer
import mindustry.logic.LMarkerControl
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.max

object Emoji {
    private val emojiDir: Fi = Vars.saveDirectory.child("emojis")
    private val printed = ConcurrentHashMap<String, Triple<Long, List<Int>, Int>>()

    init {
        if (!emojiDir.exists()) emojiDir.mkdirs()
    }



    fun print(player: Player, filename: String, size: Int = -1, scale: Float = -1f, parts: Int = 5) {
        try {
            val file = emojiDir.child(filename)
            if (!file.exists()) return

            val bytes = file.readBytes()
            val image = ImageIO.read(bytes.inputStream()) ?: return

            val width = image.width
            val height = image.height
            val pixmap = Pixmap(width, height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val argb = image.getRGB(x, y)
                    val a = (argb shr 24) and 0xFF
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    val rgba = (r shl 24) or (g shl 16) or (b shl 8) or a
                    pixmap.set(x, y, rgba)
                }
            }

            val optimalSize = if (size <= 0) minOf(100, width) else size
            val div = width / height.toDouble()
            val xAdd = max(1.0, width.toDouble() / optimalSize)
            val yAdd = max(1.0, height.toDouble() / optimalSize) * div

            val color = Color()
            val fontSize = if (scale <= 0f) 0.1f else scale
            val totalRows = minOf((optimalSize / div).toInt(), optimalSize)

            val safeParts = parts.coerceIn(1, 5)
            val rowsPerMarker = (totalRows + safeParts - 1) / safeParts

            val ids = mutableListOf<Int>()
            var markerCount = 0

            for (markerIndex in 0 until safeParts) {
                val startRow = markerIndex * rowsPerMarker
                if (startRow >= totalRows) break
                val endRow = minOf(startRow + rowsPerMarker, totalRows)

                val builder = StringBuilder()

                for (y in startRow until endRow) {
                    var lastColor: Int? = null
                    for (x in 0 until optimalSize) {
                        val px = ((x + 0.5) * xAdd).toInt().coerceIn(0, width - 1)
                        val py = ((y + 0.5) * yAdd).toInt().coerceIn(0, height - 1)
                        val raw = pixmap.get(px, py)
                        color.set(raw)

                        if (color.a <= 0.05f) {
                            builder.append(' ')
                        } else {
                            val currentColor = color.rgba8888()
                            if (currentColor != lastColor) {
                                builder.append("[#${color}]")
                                lastColor = currentColor
                            }
                            builder.append('\uF8ED')
                        }
                    }
                    builder.append('\n')
                }

                val markerId = player.id * 1000 + markerIndex
                val marker = MapObjectives.TextMarker().apply {
                    text = builder.toString()
                    pos.set(player.x, player.y + (totalRows - startRow) * 4.5f * fontSize)
                    this.fontSize = fontSize
                    this.flags = 0
                    this.control(LMarkerControl.drawLayer, (Layer.block + 0.5f).toDouble(), Double.NaN, Double.NaN)
                }
                Call.createMarker(markerId, marker)
                ids.add(markerId)
                markerCount++
            }

            printed[player.uuid()] = Triple(System.currentTimeMillis(), ids, markerCount)

        } catch (e: Exception) {
            Log.err("[red]Error displaying emoji: ${e.message}")
        }
    }





    fun removePrint(uuid: String, seconds: Int) {
        val (time, ids, _) = printed[uuid] ?: return
        if (System.currentTimeMillis() - time > seconds * 1000L) {
            ids.forEach { Call.removeMarker(it) }
            printed.remove(uuid)
        }
    }

    fun clearAll() {
        for ((_, triple) in printed) {
            val (_, ids, _) = triple
            ids.forEach { Call.removeMarker(it) }
        }
        printed.clear()
    }
}
