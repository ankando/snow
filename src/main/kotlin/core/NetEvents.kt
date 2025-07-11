package plugin.core

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
import plugin.core.PermissionManager.isBanned
import plugin.core.RevertBuild.restorePlayerEditsWithinSeconds
import plugin.core.Translator.translate
import plugin.snow.PluginVars

object NetEvents {

    @JvmStatic
    fun chat(player: Player?, message: String?): String? {
        if (message.isNullOrBlank() || player == null) return null
        if (isBanned(player.uuid())) return null
        val trimmed = message.trim()
        val lower = trimmed.lowercase()

        fun getPlayerLevel(player: Player): Int {
            val acc = DataManager.getPlayerDataByUuid(player.uuid())
            val wins = acc?.wins ?: 0
            return maxOf(1, wins / 20)
        }

        fun isYes(text: String) = text.equals("y", ignoreCase = true)

        fun broadcast(sender: Player, raw: String) {
            val msg = Strings.stripColors(raw)
            val senderName = sender.name()
            val level = getPlayerLevel(sender)
            val levelPrefix = "<lv$level> "
            val senderTag = "${PluginVars.INFO}$levelPrefix$senderName${PluginVars.RESET}"

            sender.sendMessage("$senderTag: ${PluginVars.GRAY}$msg${PluginVars.RESET}")
            Groups.player.each { receiver ->
                if (receiver == sender) return@each
                val acc = DataManager.getPlayerDataByUuid(receiver.uuid())
                val lang = acc?.lang ?: receiver.locale()
                val needsTranslation = !lang.isNullOrEmpty() && lang != "off"
                if (needsTranslation) {
                    translate(msg, "auto", lang, { translated ->
                        val disp =
                            if (translated != msg) "$msg ${PluginVars.SECONDARY}($translated)${PluginVars.RESET}" else msg
                        receiver.sendMessage("$senderTag: ${PluginVars.GRAY}$disp${PluginVars.RESET}")
                    }, {
                        receiver.sendMessage("$senderTag: ${PluginVars.GRAY}$msg${PluginVars.RESET}")
                    })
                } else {
                    receiver.sendMessage("$senderTag: ${PluginVars.GRAY}$msg${PluginVars.RESET}")
                }
            }
        }

        if (isYes(lower)) {
            val session = VoteManager.getGlobalVoteSession() ?: return null
            if (player == session.creator) return null
            if (session.voted.contains(player.uuid())) return null
            VoteManager.addVote(player.uuid())
            return null
        } else if (trimmed.startsWith("/")) {
            return null
        } else {
            broadcast(player, message)
        }
        return null as String?
    }


    @JvmStatic
    fun adminRequest(con: NetConnection?, packet: AdminRequestCallPacket?) {
        val admin = con?.player ?: return
        val target = packet?.other ?: return
        val action = packet.action ?: return
        if (!admin.admin) return
        if (target.admin && target !== admin) return
        Events.fire(EventType.AdminRequestEvent(admin, target, action))
        when (action) {
            AdminAction.kick -> {
                restorePlayerEditsWithinSeconds(target, 200)
                Call.announce(
                    "${PluginVars.WARN}${admin.name()} ${
                        I18nManager.get(
                            "admin.kick",
                            null
                        )
                    } ${target.name()}${PluginVars.RESET}"
                )
                target.kick(KickReason.kick)
            }

            AdminAction.ban -> {
                restorePlayerEditsWithinSeconds(target, 200)
                Call.announce(
                    "${PluginVars.WARN}${admin.name()} ${
                        I18nManager.get(
                            "admin.ban",
                            null
                        )
                    } ${target.name()}${PluginVars.RESET}"
                )
                DataManager.getPlayerDataByUuid(target.uuid())?.let { acc ->
                    acc.banUntil = Time.millis() + 30 * 60_000L
                    DataManager.requestSave()
                }
                target.kick(KickReason.banned, 30 * 60 * 1000)
            }

            AdminAction.trace -> {
                val trace = TraceInfo(
                    "[hidden]", "[hidden]", target.locale,
                    target.con.modclient, target.con.mobile,
                    target.info.timesJoined, target.info.timesKicked,
                    arrayOf("[hidden]"), target.info.names.toArray(String::class.java)
                )
                Call.traceInfo(con, target, trace)
            }

            AdminAction.wave -> {
                Vars.logic.skipWave()
                Call.announce(
                    "${PluginVars.INFO}${admin.name()} ${
                        I18nManager.get(
                            "admin.wave",
                            null
                        )
                    }${PluginVars.RESET}"
                )
            }

            AdminAction.switchTeam -> {}
        }
    }

    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun connect(con: NetConnection?, unused: Any?) {
        if (con == null) return
        Events.fire(EventType.ConnectionEvent(con))
        val admins = Vars.netServer.admins
        val ip = con.address
        if (admins.isIPBanned(ip) || admins.isSubnetBanned(ip)) {
            con.kick(KickReason.banned)
            return
        }
        val sameIp = Vars.net.connections.filter { it.address == ip }
        if (sameIp.size >= 5) {
            sameIp.forEach(NetConnection::close)
            return
        }
    }

    @JvmStatic
    fun connectPacket(con: NetConnection?, pkt: ConnectPacket?) {
        if (con == null || pkt == null || con.kicked) return
        fun kick(msg: String = "", kr: KickReason? = null) {
            kr?.let { con.kick(it, 0) } ?: con.kick(msg, 0); return
        }

        if (con.address.startsWith("steam:")) pkt.uuid = con.address.substring(6)
        Events.fire(EventType.ConnectPacketEvent(con, pkt))
        con.connectTime = Time.millis()

        val uuid = pkt.uuid ?: return
        val ip = con.address
        val admins = Vars.netServer.admins
        val cleanName = Strings.stripColors(pkt.name).trim()
        if (cleanName.isBlank() || cleanName.length > 40 || cleanName.any { Character.isISOControl(it) })
            kick(I18nManager.get("err.illegal_name", null))
        pkt.name = Vars.netServer.fixName(cleanName)

        if (admins.isIPBanned(ip) || admins.isSubnetBanned(ip) || admins.isIDBanned(uuid) || !con.isConnected) return
        if (con.hasBegunConnecting) kick(kr = KickReason.idInUse)
        con.hasBegunConnecting = true
        con.mobile = pkt.mobile
        if (pkt.usid == null) kick(kr = KickReason.idInUse)
        if (Time.millis() < admins.getKickTime(uuid, ip)) kick(kr = KickReason.recentKick)
        if (admins.playerLimit > 0 && Groups.player.size() >= admins.playerLimit && !admins.isAdmin(uuid, pkt.usid))
            kick(kr = KickReason.playerLimit)

        val extraMods = pkt.mods.copy()
        val missingMods = Vars.mods.getIncompatibility(extraMods)
        if (!extraMods.isEmpty || !missingMods.isEmpty) {
            val msg = buildString {
                append("${PluginVars.WARN}${I18nManager.get("mod.incompatible", null)}${PluginVars.RESET}\n\n")
                if (!missingMods.isEmpty) append("${PluginVars.SECONDARY}${I18nManager.get("mod.missing", null)}\n> ")
                    .append(missingMods.toString("\n> ")).append("${PluginVars.RESET}\n\n")
                if (!extraMods.isEmpty) append("${PluginVars.SECONDARY}${I18nManager.get("mod.extra", null)}\n> ")
                    .append(extraMods.toString("\n> ")).append(PluginVars.RESET)
            }
            Call.infoMessage(con, msg)
        }

        val info = admins.getInfo(uuid)
        if (!admins.isWhitelisted(uuid, pkt.usid)) {
            info.adminUsid = pkt.usid
            info.lastName = cleanName
            info.id = uuid
            admins.save()
            Call.infoMessage(
                con,
                "${PluginVars.WARN}${I18nManager.get("admin.not_whitelisted", null)}${PluginVars.RESET}"
            )
            kick(kr = KickReason.whitelist)
        }

        if (pkt.versionType == null ||
            ((pkt.version == -1 || pkt.versionType != Version.type) && Version.build != -1 && !admins.allowsCustomClients())
        ) {
            val reason = if (Version.type != pkt.versionType) "Type mismatch." else "Custom client not allowed."
            Call.infoMessage(con, "${PluginVars.WARN}$reason${PluginVars.RESET}")
        }

        if (Groups.player.any { Strings.stripColors(it.name).trim().equals(cleanName, true) })
            kick(kr = KickReason.nameInUse)
        if (Groups.player.any { it.uuid() == uuid || it.usid() == pkt.usid })
            kick(kr = KickReason.idInUse)
        for (other in Vars.net.connections) if (other !== con && other.uuid == uuid) kick(kr = KickReason.idInUse)

        if (pkt.version != Version.build && Version.build != -1 && pkt.version != -1)
            kick(kr = if (pkt.version > Version.build) KickReason.serverOutdated else KickReason.clientOutdated)
        if (pkt.version == -1) con.modclient = true

        val acc = DataManager.getPlayerDataByUuid(uuid)
        val coreAdmin = acc?.uuids?.any { admins.getInfo(it)?.admin == true } ?: false
        if (acc != null && !coreAdmin && acc.isAdmin) {
            acc.isAdmin = false; DataManager.requestSave()
        }

        val player = Player.create().apply {
            this.name = pkt.name
            this.con = con
            this.locale = pkt.locale ?: "en"
            color.set(Color.valueOf("#F1F1F1DD"))
            admin = coreAdmin && acc.isAdmin == true
        }

        con.usid = pkt.usid
        con.uuid = uuid
        con.player = player
        if (!player.admin && !info.admin) info.adminUsid = pkt.usid

        player.team(Vars.netServer.assignTeam(player))
        Vars.netServer.sendWorldData(player)
        Core.app.post { Vars.platform.updateRPC() }
        Events.fire(EventType.PlayerConnect(player))
    }
}
