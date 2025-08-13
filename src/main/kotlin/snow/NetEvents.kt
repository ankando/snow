package plugin.snow

import arc.Core
import arc.Events
import arc.util.Strings
import arc.util.Time
import arc.util.io.Writes
import mindustry.Vars
import mindustry.Vars.netServer
import mindustry.core.Version
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.NetConnection
import mindustry.net.Packets
import mindustry.net.Packets.KickReason
import plugin.core.*
import plugin.core.PermissionManager.isBanned
import plugin.core.PermissionManager.isCoreAdmin
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

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
        RecordMessage.add(localMsg)
        val rawLangGroups = groupPlayersByLang(sender)

        rawLangGroups.forEach { (lang, players) ->
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
                    players.forEach { it.sendMessage(netServer.chatFormatter.format(it,msg), it, msg) }
                }
            },
            onError = {
                Core.app.post { players.forEach { it.sendMessage(netServer.chatFormatter.format(it,fallback), it, fallback) } }
            }
        )
    }


    fun toPastelHex(baseHex: String): String {
        val clean = baseHex.removePrefix("#")
        require(clean.length == 6)

        val r0 = clean.take(2).toInt(16) / 255f
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

        val baseSaturation = (s * 0.4f).coerceIn(0f, 0.35f)
        val baseLightness  = (l * 0.6f + 0.38f).coerceIn(0.60f, 0.90f)

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

        val (rFin, gFin, bFin) = hslToRgb(h, baseSaturation, baseLightness)
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
            if (p !== exclude && !RecordMessage.isDisabled(p.uuid())) {
                val lang = DataManager.getPlayerDataByUuid(p.uuid())?.lang ?: p.locale()
                groups.getOrPut(lang) { mutableListOf() }.add(p)
            }
        }
        return groups
    }


    @JvmStatic
    fun adminRequest(con: NetConnection?, any: Any?) {
     return
    }

    @JvmStatic
    fun connect(con: NetConnection?, any: Any?) {
        con ?: return
        Events.fire(EventType.ConnectionEvent(con))

        val ip = con.address
        val admins = netServer.admins
        if (admins.isIPBanned(ip) || admins.isSubnetBanned(ip)) {
            con.kick(KickReason.banned); return
        }

        Vars.net.connections
            .filter { it.address == ip }
            .takeIf { it.size >= 5 }
            ?.let { conns ->
                netServer.admins.blacklistDos(con.address)
                conns.forEach { it.close() }
            }

    }

    @JvmStatic
    fun connectPacket(con: NetConnection?, pkt: Packets.ConnectPacket?) {
        if (con == null || pkt == null || con.kicked) return

        fun kick(msg: String = "", reason: KickReason? = null) {
            reason?.let { con.kick(it, 0) } ?: con.kick(msg, 0)
        }

        val admins = netServer.admins
        val uuid = pkt.uuid ?: return
        val usid = pkt.usid ?: run { kick(reason = KickReason.idInUse); return }
        val ip = con.address

        if (con.address.startsWith("steam:")) {
            pkt.uuid = con.address.removePrefix("steam:")
        }

        Events.fire(EventType.ConnectPacketEvent(con, pkt))
        con.connectTime = Time.millis()

        if (admins.isIPBanned(ip) || admins.isSubnetBanned(ip) || admins.isIDBanned(uuid) || con.kicked || !con.isConnected) return

        if (con.hasBegunConnecting) {
            kick(reason = KickReason.idInUse)
            return
        }

        con.hasBegunConnecting = true
        con.mobile = pkt.mobile

        val cleanName = Strings.stripColors(pkt.name).trim()
        if (cleanName.isBlank() || cleanName.length > 40 || cleanName.any { it.isISOControl() }) {
            kick(reason = KickReason.nameEmpty)
            return
        }
        pkt.name = netServer.fixName(cleanName)

        val info = admins.getInfo(uuid)

        if (Time.millis() < admins.getKickTime(uuid, ip)) {
            val remainingTime = (admins.getKickTime(uuid, ip) - Time.millis()) / 1000
            kick("[#D0D0D8DD]You have been recently kicked. Please wait $remainingTime seconds before rejoining.")
            return
        }


        if (admins.playerLimit > 0 && Groups.player.size() >= admins.playerLimit && !admins.isAdmin(uuid, usid)) {
            kick(reason = KickReason.playerLimit)
            return
        }

        val extraMods = pkt.mods.copy()
        val missingMods = Vars.mods.getIncompatibility(extraMods)

        if (!extraMods.isEmpty || !missingMods.isEmpty) {
            val msg = buildString {
                append("${PluginVars.WARN}${I18nManager.get("mod.incompatible", null)}${PluginVars.RESET}\n\n")
                if (!missingMods.isEmpty) {
                    append("${PluginVars.SECONDARY}${I18nManager.get("mod.missing", null)}\n> ")
                    append(missingMods.joinToString("\n> ")).append("${PluginVars.RESET}\n\n")
                }
                if (!extraMods.isEmpty) {
                    append("${PluginVars.SECONDARY}${I18nManager.get("mod.extra", null)}\n> ")
                    append(extraMods.joinToString("\n> ")).append(PluginVars.RESET)
                }
            }
            Call.infoMessage(con, msg)
        }

        if (!admins.isWhitelisted(uuid, usid)) {
            info.adminUsid = usid
            info.lastName = cleanName
            info.id = uuid
            admins.save()
            kick(reason = KickReason.whitelist)
            return
        }

        if (pkt.versionType == null || ((pkt.version == -1 || pkt.versionType != Version.type) && Version.build != -1 && !admins.allowsCustomClients())) {
            kick(reason = if (pkt.versionType != Version.type) KickReason.typeMismatch else KickReason.customClient)
            return
        }

        if (Groups.player.any { Strings.stripColors(it.name).trim().equals(cleanName, ignoreCase = true) }) {
            kick(reason = KickReason.nameInUse)
            return
        }

        if (Groups.player.any { it.uuid() == uuid || it.usid() == usid }) {
            con.uuid = uuid
            kick(reason = KickReason.idInUse)
            return
        }

        for (other in Vars.net.connections) {
            if (other !== con && uuid == other.uuid) {
                con.uuid = uuid
                kick(reason = KickReason.idInUse)
                return
            }
        }

        if (pkt.version != Version.build && Version.build != -1 && pkt.version != -1) {
            kick(reason = if (pkt.version > Version.build) KickReason.serverOutdated else KickReason.clientOutdated)
            return
        }


        if (pkt.version == -1) {
            con.modclient = true
        }

        val player = Player.create().apply {
            this.name = pkt.name
            this.con = con
            this.locale = pkt.locale ?: "en"
            this.color.set(pkt.color).a = 1f
        }

        con.usid = usid
        con.uuid = uuid
        con.player = player

        if (isCoreAdmin(uuid) && info.adminUsid != usid) {
            val pData = DataManager.getPlayerDataByUuid(uuid)

            if (pData != null) {
                val revoked = mutableListOf<String>()

                for (u in pData.uuids) {
                    val info = netServer.admins.getInfo(u)
                    if (info != null && info.admin) {
                        netServer.admins.unAdminPlayer(u)
                        revoked += u
                    }
                }

                if (revoked.isNotEmpty()) {
                    netServer.admins.save()
                    Call.infoMessage(player.con, "Your USID has changed. Admin privileges have been revoked.")
                }
            }
        }

        if (!info.admin) {
            info.adminUsid = usid
        }

        admins.updatePlayerJoined(uuid, ip, pkt.name)

        try {
            val output = Writes(DataOutputStream(ByteArrayOutputStream(127)))
            player.write(output)
        } catch (_: Throwable) {
            con.kick(KickReason.nameEmpty)
            return
        }

        player.team(netServer.assignTeam(player))
        netServer.sendWorldData(player)
        Core.app.post { Vars.platform.updateRPC() }
        Events.fire(EventType.PlayerConnect(player))
    }
}