package plugin.snow

import arc.Core
import arc.Events
import arc.util.Strings
import arc.util.Time
import mindustry.Vars
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.AdminRequestCallPacket
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration.TraceInfo
import mindustry.net.NetConnection
import mindustry.net.Packets.AdminAction
import mindustry.net.Packets.KickReason
import plugin.core.DataManager
import plugin.core.PermissionManager.isBanned
import plugin.core.RevertBuild
import plugin.core.Translator
import plugin.core.VoteManager

object NetEvents {

    @JvmStatic
    fun chat(p: Player?, msg: String?): String? {
        if (p == null || msg.isNullOrBlank() || isBanned(p.uuid())) return null
        val raw = msg.trim()
        if (raw.startsWith("/")) return null

        if (raw.lowercase() == "y") {
            val vote = VoteManager.globalVoteSession
            if (vote != null && vote.creator !== p) VoteManager.addVote(p.uuid())
            return null
        }

        broadcast(p, raw)
        return null as String?
    }

    private val ctrlCharRegex = Regex("""[\u0000-\u001F]""")

    fun broadcast(sender: Player, raw: String) {
        val plain = Strings.stripColors(raw).trim()
        if (plain.isBlank() || ctrlCharRegex.containsMatchIn(plain)) return

        val prefix = buildChatPrefix(sender)
        val localMsg = "$prefix: ${PluginVars.GRAY}$plain${PluginVars.RESET}"
        sender.sendMessage(localMsg)

        val rawLangGroups = groupPlayersByLang(sender)

        val normLangGroups = rawLangGroups.entries
            .groupBy({ (lang, _) -> normalizeLang(lang) }) { it.value }
            .mapValues { (_, lists) -> lists.flatten() }

        normLangGroups.forEach { (lang, players) ->
            sendTranslatedBroadcast(plain, prefix, lang, players, fallback = localMsg)
        }
    }

    private fun sendTranslatedBroadcast(
        text: String,
        prefix: String,
        lang: String,
        players: List<Player>,
        fallback: String
    ) {
        Translator.translate(
            text, "auto", lang,
            onResult = { translated ->
                Core.app.post {
                    val body = if (translated != text)
                        "$text ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}"
                    else text
                    val msg = "$prefix: ${PluginVars.GRAY}$body${PluginVars.RESET}"
                    players.forEach { it.sendMessage(msg) }
                }
            },
            onError = {
                Core.app.post { players.forEach { it.sendMessage(fallback) } }
            }
        )
    }

    private fun normalizeLang(raw: String?): String {
        if (raw.isNullOrBlank()) return "auto"
        val u = raw.trim().lowercase()
        return when {
            u.startsWith("zh") -> "zh"
            u.startsWith("en") -> "en"
            u.startsWith("ru") -> "ru"
            u.startsWith("ja") -> "ja"
            u.startsWith("ko") -> "ko"
            else -> u.substringBefore('_').substringBefore('-')
        }
    }

    fun toPastelHex(baseHex: String): String {
        val clean = baseHex.removePrefix("#")
        require(clean.length == 6)

        val r0 = clean.substring(0, 2).toInt(16) / 255f
        val g0 = clean.substring(2, 4).toInt(16) / 255f
        val b0 = clean.substring(4, 6).toInt(16) / 255f

        val max = maxOf(r0, g0, b0)
        val min = minOf(r0, g0, b0)
        val d = max - min
        val h = when {
            d == 0f    -> 0f
            max == r0  -> ((g0 - b0) / d + (if (g0 < b0) 6 else 0)) / 6f
            max == g0  -> ((b0 - r0) / d + 2) / 6f
            else       -> ((r0 - g0) / d + 4) / 6f
        }
        val l = (max + min) / 2f
        val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))

        val baseHue        = h
        val baseSaturation = (s * 0.4f).coerceIn(0f, 0.35f)
        val baseLightness  = (l * 0.6f + 0.35f).coerceIn(0.60f, 0.85f)

        fun hslToRgb(hh: Float, ss: Float, ll: Float): Triple<Int, Int, Int> {
            val c = (1 - kotlin.math.abs(2 * ll - 1)) * ss
            val x = c * (1 - kotlin.math.abs(((hh * 6) % 2) - 1))
            val m = ll - c / 2
            val (r1, g1, b1) = when {
                hh < 1f / 6f -> Triple(c, x, 0f)
                hh < 2f / 6f -> Triple(x, c, 0f)
                hh < 3f / 6f -> Triple(0f, c, x)
                hh < 4f / 6f -> Triple(0f, x, c)
                hh < 5f / 6f -> Triple(x, 0f, c)
                else         -> Triple(c, 0f, x)
            }
            fun ch(v: Float) = (255f * (v + m)).toInt().coerceIn(0, 255)
            return Triple(ch(r1), ch(g1), ch(b1))
        }

        val (rFin, gFin, bFin) = hslToRgb(baseHue, baseSaturation, baseLightness)
        return String.format("#%02X%02X%02X", rFin, gFin, bFin)
    }


    private fun buildChatPrefix(player: Player): String {
        val useTeamColor = Vars.state.rules.pvp &&
                player.team() != Team.derelict &&
                player.team().data().hasCore()

        return if (useTeamColor) {
            val baseHex = "#" + player.team().color.toString().take(6).uppercase()
            val softHex = toPastelHex(baseHex)
            "[${softHex}]${Strings.stripColors(player.name()).trim()}${PluginVars.RESET}"
        } else {
            "${PluginVars.INFO}${player.name()}${PluginVars.RESET}"
        }
    }


    private fun groupPlayersByLang(exclude: Player): Map<String, List<Player>> {
        val groups = mutableMapOf<String, MutableList<Player>>()
        Groups.player.each { p ->
            if (p !== exclude) {
                val lang = DataManager.getPlayerDataByUuid(p.uuid())?.lang ?: p.locale()
                groups.getOrPut(lang) { mutableListOf() }.add(p)
            }
        }
        return groups
    }




    private const val BAN_MS = 30 * 60_000L

    @JvmStatic
    fun adminRequest(con: NetConnection?, pkt: AdminRequestCallPacket?) {
        val admin = con?.player ?: return
        val target = pkt?.other ?: return
        if (!admin.admin || (target.admin && target !== admin)) return

        Events.fire(EventType.AdminRequestEvent(admin, target, pkt.action))
        val uuid = target.uuid()

        fun restore() = RevertBuild.restorePlayerEditsWithinSeconds(uuid, 200)

        when (pkt.action) {
            AdminAction.kick -> {
                restore()
                target.kick(KickReason.kick)
            }

            AdminAction.ban -> {
                restore()
                DataManager.getPlayerDataByUuid(uuid)?.apply {
                    banUntil = Time.millis() + BAN_MS
                    DataManager.requestSave()
                }
                target.kick(KickReason.banned, BAN_MS)
            }

            AdminAction.trace -> Call.traceInfo(
                con, target,
                TraceInfo(
                    "[hidden]",
                    "[hidden]",
                    target.locale,
                    target.con.modclient,
                    target.con.mobile,
                    target.info.timesJoined,
                    target.info.timesKicked,
                    arrayOf("[hidden]"),
                    target.info.names.toArray(String::class.java)
                )
            )

            AdminAction.wave -> {
                Vars.logic.skipWave()
            }

            AdminAction.switchTeam -> {}
        }
    }


}