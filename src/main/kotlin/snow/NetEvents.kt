package plugin.snow

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.util.Strings
import arc.util.Time
import mindustry.Vars
import mindustry.core.Version
import mindustry.game.EventType
import mindustry.game.Team
import mindustry.gen.AdminRequestCallPacket
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Player
import mindustry.net.Administration.TraceInfo
import mindustry.net.NetConnection
import mindustry.net.Packets.*
import plugin.core.*
import plugin.core.PermissionManager.isBanned

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

    private const val MAX_CHAT_LEN = 256
    private val ctrlCharRegex = Regex("""[\u0000-\u001F]""")

    fun broadcast(sender: Player, raw: String) {
        val plain = Strings.stripColors(raw).trim()
        if (plain.isBlank() || ctrlCharRegex.containsMatchIn(plain)) return

        val limited = plain.take(MAX_CHAT_LEN)
        val prefix = buildChatPrefix(sender)
        val local = "$prefix: ${PluginVars.GRAY}$limited${PluginVars.RESET}"

        sender.sendMessage(local)

        val langGroups = groupPlayersByLang(sender)

        langGroups.forEach { (lang, players) ->
            if (lang == "auto" || lang.equals(sender.locale(), true)) {
                players.forEach { it.sendMessage(local) }
            } else {
                sendTranslatedBroadcast(limited, prefix, lang, players, fallback = local)
            }
        }
    }

    private fun mixWithGray(hex: String): String {
        val r = Integer.parseInt(hex.substring(0, 2), 16)
        val g = Integer.parseInt(hex.substring(2, 4), 16)
        val b = Integer.parseInt(hex.substring(4, 6), 16)

        val mix = 0.5
        val nr = (r * mix + 100).toInt().coerceAtMost(255)
        val ng = (g * mix + 100).toInt().coerceAtMost(255)
        val nb = (b * mix + 100).toInt().coerceAtMost(255)

        return String.format("%02X%02X%02X", nr, ng, nb)
    }

    private fun buildChatPrefix(player: Player): String {
        val useTeamColor = Vars.state.rules.pvp &&
                player.team() != Team.derelict &&
                player.team().data().hasCore()
        return if (useTeamColor) {
            val rgb = player.team().color.toString().take(6).uppercase()
            val soft = mixWithGray(rgb)
            "[#${soft}]${player.name()}${PluginVars.RESET}"
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
            AdminAction.trace -> Call.traceInfo(con, target,
                TraceInfo("[hidden]", "[hidden]", target.locale, target.con.modclient, target.con.mobile,
                    target.info.timesJoined, target.info.timesKicked, arrayOf("[hidden]"), target.info.names.toArray(String::class.java)))
            AdminAction.wave  -> {
                Vars.logic.skipWave()
            }
            AdminAction.switchTeam -> {}
        }
    }


    @JvmStatic
    fun connect(con: NetConnection?, any: Any?) {
        con ?: return
        Events.fire(EventType.ConnectionEvent(con))

        val ip = con.address
        val admins = Vars.netServer.admins
        if (admins.isIPBanned(ip) || admins.isSubnetBanned(ip)) {
            con.kick(KickReason.banned); return
        }

        Vars.net.connections.filter { it.address == ip }.takeIf { it.size >= 5 }?.forEach(NetConnection::close)
    }

    @JvmStatic
    fun connectPacket(con: NetConnection?, pkt: ConnectPacket?) {
        if (con == null || pkt == null || con.kicked) return
        fun kick(reason: KickReason) = con.kick(reason, 0)
        val admins = Vars.netServer.admins

        val uuid = (pkt.uuid ?: "").ifBlank { con.address.removePrefix("steam:") }
        if (uuid.isEmpty()) return
        val ip = con.address
        val cleanName = Strings.stripColors(pkt.name).trim()

        if (cleanName.isBlank() || cleanName.length > 40 || cleanName.any { it.isISOControl() }) {
            kick(KickReason.kick); return
        }
        pkt.name = Vars.netServer.fixName(cleanName)

        if (admins.isIPBanned(ip) || admins.isSubnetBanned(ip) || admins.isIDBanned(uuid)) return

        if (Groups.player.any { it.uuid() == uuid } || Vars.net.connections.any { it !== con && it.uuid == uuid })
            kick(KickReason.idInUse)

        if (Version.build != -1 && pkt.version != Version.build && pkt.version != -1) {
            kick(if (pkt.version > Version.build) KickReason.serverOutdated else KickReason.clientOutdated)
        }

        val player = Player.create().apply {
            name = pkt.name
            this.con = con
            locale = pkt.locale ?: "en"
            color = Color.valueOf("#F1F1F1DD")
        }
        con.player = player
        con.uuid = uuid
        con.usid = pkt.usid
        player.admin = DataManager.getPlayerDataByUuid(uuid)?.isAdmin == true

        player.team(Vars.netServer.assignTeam(player))
        Vars.netServer.sendWorldData(player)
        Core.app.post { Vars.platform.updateRPC() }
        Events.fire(EventType.PlayerConnect(player))
    }
}
