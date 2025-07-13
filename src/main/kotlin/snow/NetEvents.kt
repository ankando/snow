package plugin.snow

import arc.Core
import arc.Events
import arc.graphics.Color
import arc.util.Strings
import arc.util.Time
import mindustry.Vars
import mindustry.core.Version
import mindustry.game.EventType
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
            if (vote != null && vote.creator !== p) vote.voted += p.uuid()
            return null
        }

        broadcast(p, raw)
        return null as String?
    }

    private fun broadcast(sender: Player, raw: String) {
        val plain = Strings.stripColors(raw)
        val local = "${PluginVars.GRAY}$plain${PluginVars.RESET}"
        sender.sendMessage(local)

        Groups.player.each { r ->
            if (r === sender) return@each
            val lang = DataManager.getPlayerDataByUuid(r.uuid())?.lang ?: r.locale()
            Translator.translate(plain, "auto", lang, { tr ->
                val body = if (tr != plain) "$plain ${PluginVars.SECONDARY}($tr)${PluginVars.RESET}" else plain
                r.sendMessage("$local: $body")
            }, { r.sendMessage(local) })
        }
    }

    private const val BAN_MS = 30 * 60_000L

    @JvmStatic
    fun adminRequest(con: NetConnection?, pkt: AdminRequestCallPacket?) {
        val admin = con?.player ?: return
        val target = pkt?.other ?: return
        if (!admin.admin || (target.admin && target !== admin)) return

        Events.fire(EventType.AdminRequestEvent(admin, target, pkt.action))
        val uuid = target.uuid()
        val adminName = admin.name()
        val targetName = target.name()

        fun restore() = RevertBuild.restorePlayerEditsWithinSeconds(uuid, 300)

        when (pkt.action) {
            AdminAction.kick -> {
                restore()
                announce("admin.kick", adminName, targetName)
                target.kick(KickReason.kick)
            }
            AdminAction.ban -> {
                restore()
                announce("admin.ban", adminName, targetName)
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
                announce("admin.wave", adminName)
            }
            AdminAction.switchTeam -> {}
        }
    }

    private fun announce(key: String, vararg names: String) =
        Call.announce("${PluginVars.WARN}${names.joinToString(" ")} ${I18nManager.get(key, null)}${PluginVars.RESET}")


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
