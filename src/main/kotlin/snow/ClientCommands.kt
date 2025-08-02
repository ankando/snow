package plugin.snow

import arc.graphics.Color
import arc.graphics.Pixmap
import arc.util.CommandHandler
import arc.util.Strings
import arc.util.Time
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.game.MapObjectives
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.graphics.Layer
import mindustry.logic.LMarkerControl
import mindustry.net.Packets
import plugin.core.*
import plugin.core.PermissionManager.isBanned
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.RevertBuild.restorePlayerEditsWithinSeconds
import plugin.core.Translator.translate
import plugin.snow.PluginMenus.beginVotekick
import plugin.snow.PluginMenus.showConfirmMenu
import plugin.snow.PluginMenus.showVoteKickPlayerMenu
import java.net.URL
import javax.imageio.ImageIO
import kotlin.math.max

object ClientCommands {
    fun printIcon(
        player: Player,
        url: String,
        size: Int = -1,
        scale: Float = -1f
    ) {
        try {
            val lower = url.lowercase()
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg"))) {
                Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("emoji.unsupported", player)}${PluginVars.RESET}")
                return
            }

            val connection = URL(url).openConnection()
            connection.connect()

            val length = connection.getHeaderFieldInt("Content-Length", -1)
            if (length > 2_000 * 1024) {
                Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("emoji.too_large", player)}${PluginVars.RESET}")
                return
            }

            val input = connection.getInputStream()
            val bytes = input.readBytes()
            input.close()

            if (bytes.size > 2_000 * 1024) {
                Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("emoji.too_large", player)}${PluginVars.RESET}")
                return
            }

            val image = ImageIO.read(bytes.inputStream()) ?: run {
                Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("emoji.decode_failed", player)}${PluginVars.RESET}")
                return
            }

            val width = image.width
            val height = image.height

            if (width > 5000 || height > 5000) {
                Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("emoji.too_large", player)}${PluginVars.RESET}")
                return
            }

            val pixmap = Pixmap(width, height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val argb = image.getRGB(x, y)
                    val a = (argb shr 24) and 0xFF
                    val r = (argb shr 16) and 0xFF
                    val g = (argb shr 8) and 0xFF
                    val b = argb and 0xFF
                    val rgba = (r shl 24) or (g shl 16) or (b shl 8) or a
                    val flippedY = height - 1 - y
                    pixmap.set(x, flippedY, rgba)
                }
            }

            val optimalSize = if (size <= 0) minOf(160, width) else size
            val optimalScale = if (scale <= 0f) 0.08f else scale

            val div = width / height.toDouble()
            val xAdd = max(1.0, width.toDouble() / optimalSize)
            val yAdd = max(1.0, height.toDouble() / optimalSize) * div

            val color = Color()
            val fontSize = 0.8f * optimalScale
            val charHeight = 3f * optimalScale

            val baseId = player.id

            for (y in 0 until (optimalSize / div).toInt()) {
                val builder = StringBuilder()
                var last: Int? = null

                for (x in 0 until optimalSize) {
                    val px = ((x + 0.5) * xAdd).toInt().coerceIn(0, width - 1)
                    val py = ((y + 0.5) * yAdd).toInt().coerceIn(0, height - 1)
                    val raw = pixmap.get(px, py)
                    color.set(raw)

                    if (color.a <= 0.05f) {
                        builder.append(' ')
                        continue
                    }

                    if (last != color.rgba8888()) builder.append("[#${color}]")
                    builder.append('\uF8ED')
                    last = color.rgba8888()
                }

                val markerId = baseId * 1000 + y
                val marker = MapObjectives.TextMarker().apply {
                    this.text = builder.toString()
                    this.pos.set(player.x, player.y + y * charHeight)
                    this.fontSize = fontSize
                    this.flags = 0
                    this.control(LMarkerControl.drawLayer, (Layer.block + 0.5f).toDouble(), Double.NaN, Double.NaN)
                }

                Call.createMarker(markerId, marker)
            }

        } catch (e: Exception) {
            Call.announce(player.con, "${PluginVars.ERROR}${I18nManager.get("emoji.error", player)}: ${e.message}${PluginVars.RESET}")
        }
    }

    fun register(handler: CommandHandler) {
        fun parsePageArg(args: Array<String>): Int {
            if (args.isNotEmpty()) {
                val n = args[0].toIntOrNull()
                if (n != null && n >= 1) return n
            }
            return 1
        }

        fun register(
            name: String,
            args: String,
            desc: String,
            exec: (Array<String>, Player) -> Unit
        ) {
            handler.register<Player>(
                name,
                args,
                desc
            ) { args, player ->
                if ( isBanned(player.uuid())) return@register
                exec(args, player)
            }
        }
        register("help", "[page]", "helpCmd.help") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showHelpMenu(player, page)
        }
        register("maps", "[page]", "helpCmd.maps") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showMapMenu(player, page)
        }
        register("emoji", "[page]", "helpCmd.emoji") { args, player ->
            val page = parsePageArg(args)
            PluginMenus.showEmojisMenu(player, page)
        }
        register("rank", "[page]", "helpCmd.rank") { args, player ->
            parsePageArg(args)
            PluginMenus.showRankMenu(player)
        }
        register("players", "", "helpCmd.players") { _, player ->
            PluginMenus.showPlayersMenu(player, 1)
        }
        register("games", "", "helpCmd.games") { _, player ->
            PluginMenus.showGamesMenu(player)
        }
        register("join", "", "helpCmd.join") { _, player ->
            if (!Vars.state.rules.pvp || !PlayerTeam.wasAutoAssigned(player.uuid())) {
                Call.announce(player.con, I18nManager.get("join.joined", player))
                return@register
            }

            PluginMenus.showTeamMenu(player)
        }
        register("rules", "", "helpCmd.rules") { _, player ->
            PluginMenus.showRulesMenu(player)
        }
        register("ban", "[id] [seconds]", "Ban a player by id") { args, viewer ->
            if (!isCoreAdmin(viewer.uuid())) {
                return@register
            }
            if (args.size != 2) {
                Call.announce(viewer.con, "${PluginVars.WARN}[id [seconds]${PluginVars.RESET}")
                return@register
            }
            val id = args[0].toIntOrNull()
            val seconds = args[1].toLongOrNull()

            if (id == null || seconds == null || seconds <= 0) {
                Call.announce(viewer.con, "${PluginVars.WARN}Invalid arguments.${PluginVars.RESET}")
                return@register
            }

            val playerData = DataManager.players[id]
            if (playerData == null) {
                Call.announce(viewer.con, "${PluginVars.WARN}${I18nManager.get("login.notFound", viewer)}${PluginVars.RESET}")
                return@register
            }

            if (isCoreAdmin(id)) {
                Call.announce(viewer.con, "${PluginVars.WARN}${I18nManager.get("votekick.noadmin", viewer)}${PluginVars.RESET}")
                return@register
            }

            val banUntil = System.currentTimeMillis() + seconds * 1000
            playerData.banUntil = banUntil

            Groups.player.each { target ->
                if (target != null && playerData.uuids.contains(target.uuid())) {
                    target.kick("")
                    restorePlayerEditsWithinSeconds(target.uuid(), 200)
                    UnitEffects.clear(target.uuid())
                }
            }

            Call.announce(
                viewer.con,
                "${PluginVars.SUCCESS}${I18nManager.get("playerInfo.setban.success", viewer)}${PluginVars.RESET}"
            )

            DataManager.requestSave()
        }

        register("upload", "", "helpCmd.upload") { _, player ->
            PluginMenus.showUploadMapMenu(player)
        }
        register("message", "", "helpCmd.message") { _, player ->
            PluginMenus.showMessageMenu(player)
        }
        register("about", "", "helpCmd.about") { _, player ->
            PluginMenus.showAboutMenu(player)
        }
        register("snapshot", "", "helpCmd.snapshot") { _, player ->
            PluginMenus.showSnapshotMenu(player)
        }

        register("printIcon", "[url]", "Print an image") { args, player ->
            if (args.isEmpty()) {
                val baseId = player.id * 1000
                repeat(5000) { Call.removeMarker(baseId + it) }
                return@register
            }
            printIcon(player, args[0])
        }

        register("print", "[text]", "helpCmd.snapshot") { args, player ->
            val id = player.id
            if (args.isEmpty()) {
                Call.removeMarker(id)
                return@register
            }
            val text = args.joinToString(" ").take(50)
            if (text == "clear" && isCoreAdmin(player.uuid())) {
                Emoji.clearAll()
                return@register
            }
            val marker = MapObjectives.TextMarker().apply {
                this.text = text
                this.pos.set(player.x, player.y)
                this.fontSize = 1f
                this.flags = 0.toByte()
            }
            Call.createMarker(id, marker)
        }
        register("revert", "", "helpCmd.revert") { _, player ->
            PluginMenus.showRevertMenu(player)
        }
        register("profile", "", "helpCmd.profile") { _, player ->
            PluginMenus.showSetProfileMenu(player)
        }
        register("over", "", "helpCmd.over") { _, player ->
            PluginMenus.showGameOverMenu(player)
        }
        register("misc", "", "helpCmd.misc") { _, player ->
            PluginMenus.showOthersMenu(player)
        }
        register("sync", "", "helpCmd.sync") { _, player ->
            if (!player.isLocal) {
                if (Time.timeSinceMillis(player.info.lastSyncTime) < 1000 * 5) {
                    return@register

                }
                player.info.lastSyncTime = Time.millis()
                Call.worldDataBegin(player.con)
                netServer.sendWorldData(player)
            }
        }

        register("votekick", "[player] [reason]", "helpCmd.votekick") { args, player ->
            if (args.isEmpty()) {
                showVoteKickPlayerMenu(player)
                return@register
            }


            val targetName = args[0]

            val target = if (
                targetName.startsWith("#") &&
                targetName.length > 1 &&
                Strings.canParseInt(targetName.substring(1))
            ) {
                val id = Strings.parseInt(targetName.substring(1))
                Groups.player.find { it?.id() == id }
            } else {
                Groups.player.find { it?.name?.contains(targetName, ignoreCase = true) == true }
            }

            if (target == null) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.notfound", player)}${PluginVars.RESET}"
                )
                return@register
            }

            if (target == player) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.noself", player)}${PluginVars.RESET}"
                )
                return@register
            }

            if (isCoreAdmin(target.uuid())) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.noadmin", player)}${PluginVars.RESET}"
                )
                return@register
            }

            if (isCoreAdmin(player.uuid())) {
                Call.kick(target.con, Packets.KickReason.kick)
                Call.announce(
                    "@${target.name} ${PluginVars.WARN}${I18nManager.get("votekick.kicked.byadmin", player)}${PluginVars.RESET}"
                )
                return@register
            }
            if (Groups.player.size() < 3) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("votekick.too_few", player)}${PluginVars.RESET}"
                )
                return@register
            }
            beginVotekick(player, target)
        }


        register("a", "<...>", "Send Messages to admins") { args, player ->
            if (args.isEmpty()) return@register

            val message = args.joinToString(" ")
            val playerName = player.name() ?: I18nManager.get("unknown", player)

            val selfPrefix = "${PluginVars.INFO}<\uE82C>${PluginVars.RESET} " +
                    "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
            val selfMessage = "$selfPrefix$message${PluginVars.RESET}"
            player.sendMessage(selfMessage)

            Groups.player.each { receiver ->
                if (receiver === player || !isCoreAdmin(receiver.uuid())) return@each
                val rPrefix = "${PluginVars.INFO}<\uE82C>${PluginVars.RESET} " +
                        "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
                val acc = DataManager.getPlayerDataByUuid(receiver.uuid())
                val lang = acc?.lang ?: receiver.locale()

                translate(message, "auto", lang, { translated ->
                    val msg = if (translated != message)
                        "$rPrefix$message ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}"
                    else
                        selfMessage
                    receiver.sendMessage(msg)
                }, {
                    receiver.sendMessage(selfMessage)
                })
            }
        }

        register("t", "<...>", "helpCmd.t") { args, player ->
            if (args.isEmpty()) return@register

            val message = args.joinToString(" ")
            val playerName = player.name() ?: I18nManager.get("unknown", player)
            val prefix = "${PluginVars.INFO}<${I18nManager.get("team.tag", player)}>${PluginVars.RESET} " +
                    "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
            val formatted = "$prefix$message${PluginVars.RESET}"

            player.sendMessage(formatted)

            Groups.player.each { receiver ->
                if (receiver === player || receiver.team() != player.team()) return@each
                if (RecordMessage.isDisabled(receiver.uuid())) return@each
                val rPrefix = "${PluginVars.INFO}<${I18nManager.get("team.tag", receiver)}>${PluginVars.RESET} " +
                        "${PluginVars.INFO}$playerName${PluginVars.RESET}: ${PluginVars.GRAY}"
                val acc = DataManager.getPlayerDataByUuid(receiver.uuid())
                val lang = acc?.lang ?: receiver.locale()
                translate(message, "auto", lang, { translated ->
                    val msg = if (translated != message)
                        "$rPrefix$message ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}"
                    else
                        formatted
                    receiver.sendMessage(msg)
                }, {
                    receiver.sendMessage(formatted)
                })
            }
        }


        register("surrender", "", "helpCmd.surrender") { _, player ->
            val team = player.team()
            val teamPlayerCount = Groups.player.count { it.team() == team && !it.dead() }

            if (team == Team.derelict || !team.data().hasCore() || teamPlayerCount < 3) {
                Call.announce(
                    player.con,
                    "${PluginVars.WARN}${I18nManager.get("surrender.no_team", player)}${PluginVars.RESET}"
                )
                return@register
            }

            showConfirmMenu(player) {
                VoteManager.createTeamVote(player) { ok ->
                    if (ok) {
                        team.cores().forEach { core ->
                            mindustry.entities.Damage.damage(
                                Team.derelict, core.x, core.y,
                                1.2f, 9999999f, false, false
                            )
                        }
                        Groups.build.each { if (it.team == team) it.kill() }
                        Groups.player.each { p ->
                            if (p.team() == team) {
                                Call.announce(
                                    p.con,
                                    "${PluginVars.INFO}${I18nManager.get("surrender.success", p)}${PluginVars.RESET}"
                                )
                            }
                        }
                    } else {
                        Call.announce(
                            player.con,
                            "${PluginVars.WARN}${I18nManager.get("surrender.fail", player)}${PluginVars.RESET}"
                        )
                    }
                }
                Groups.player.each { p ->
                    if (p.team() == team && p != player && !p.dead() && !isBanned(p.uuid())) {
                        val title = "${PluginVars.WARN}${I18nManager.get("surrender.vote.title", p)}${PluginVars.RESET}"
                        val desc = "${PluginVars.GRAY}${I18nManager.get("surrender.vote.desc", p)}${PluginVars.RESET}"

                        val menu = MenusManage.createConfirmMenu(
                            title = {title},
                            desc = {desc},
                            onResult = { pl, choice ->
                                if (choice == 0) {
                                    VoteManager.addVote(pl.uuid(), team)
                                }
                            }
                        )

                        menu(p)
                    }
                }
            }
        }


    }
}