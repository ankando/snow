package plugin

import arc.Core
import arc.Events
import arc.files.Fi
import arc.func.Cons
import arc.func.Cons2
import arc.graphics.Color
import arc.math.Mathf
import arc.math.geom.Point2
import arc.net.Server
import arc.net.ServerDiscoveryHandler.ReponseHandler
import arc.struct.ObjectMap
import arc.struct.Seq
import arc.util.*
import arc.util.CommandHandler.CommandRunner
import arc.util.serialization.Json
import arc.util.serialization.JsonReader
import com.maxmind.geoip2.DatabaseReader
import fi.iki.elonen.NanoHTTPD
import mindustry.Vars
import mindustry.ai.UnitCommand
import mindustry.content.Blocks
import mindustry.content.Fx
import mindustry.content.UnitTypes
import mindustry.content.Weathers
import mindustry.core.GameState
import mindustry.core.NetClient
import mindustry.core.NetServer.TeamAssigner
import mindustry.core.Version
import mindustry.game.EventType.*
import mindustry.game.Gamemode
import mindustry.game.Rules
import mindustry.game.Team
import mindustry.gen.*
import mindustry.io.SaveIO
import mindustry.maps.Map
import mindustry.mod.Plugin
import mindustry.net.Administration.*
import mindustry.net.ArcNetProvider
import mindustry.net.NetConnection
import mindustry.net.Packets.*
import mindustry.net.WorldReloader
import mindustry.type.Weather.WeatherEntry
import mindustry.ui.Menus
import mindustry.world.Block
import mindustry.world.Tile
import mindustry.world.blocks.ConstructBlock.ConstructBuild
import mindustry.world.blocks.storage.CoreBlock
import mindustry.world.blocks.storage.CoreBlock.CoreBuild
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class SimplePlugin : Plugin() {
    private var server: MapUploadServer? = null
    private val updatedBuilder = StringBuilder()
    private val usedMaps = mutableSetOf<Map>()
    private class BlockEdit(var block: Block?, var teamId: Int, var rotation: Int, var timeNanos: Long)
    override fun init() {
        Events.on<ServerLoadEvent?>(ServerLoadEvent::class.java) { s: ServerLoadEvent? ->
            loadAccounts()
            loadServerConfig()
            Timer.schedule({
                Groups.player.each { p ->
                    getAccountByUuid(p.uuid())?.let { acc ->
                        acc.playTime += 2
                        updateAccount(acc)
                    }
                }
            }, 120f, 120f)

            Timer.schedule({
                if (!Vars.state.isGame) return@schedule
                val mapDescription = Vars.state.map?.description() ?: ""
                if (mapDescription.lowercase().contains("[@noweather]")) return@schedule

                val minutesSinceStart = (Vars.state.tick / 60f / 60f).toInt()
                val cycleMinutes = 60
                val halfCycle = cycleMinutes / 2
                val remainder = minutesSinceStart % cycleMinutes

                val targetColor = if (remainder < halfCycle) {
                    Color(0.1f, 0.1f, 0.2f, 0.3f)
                } else {
                    Color(0.1f, 0.1f, 0.1f, 0.8f)
                }

                val currentColor = Vars.state.rules.ambientLight
                if (!colorsAreEqual(currentColor, targetColor, 0.01f)) {
                    currentColor.set(targetColor)
                    Call.setRules(Vars.state.rules)
                }

            }, 0f, 60f)
/*
            Timer.schedule({
                if (Vars.state.isGame) {
                    val fx = Fx.mineWallSmall
                    val color = Color(109f, 119f, 152f, 1f)
                    val baseX = 0f
                    val baseY = -30f
                    val spacing = 5f
                    val charSpacing = 8f

                    val letters = arrayOf<Array<IntArray?>>(
                        arrayOf( // S
                            intArrayOf(1, 1, 1, 1, 0),
                            intArrayOf(1, 0, 0, 0, 0),
                            intArrayOf(1, 1, 1, 0, 0),
                            intArrayOf(0, 0, 0, 1, 0),
                            intArrayOf(1, 1, 1, 1, 0)
                        ), arrayOf( // n
                            intArrayOf(1, 1, 0, 0, 0),
                            intArrayOf(1, 0, 1, 0, 0),
                            intArrayOf(1, 0, 1, 0, 0),
                            intArrayOf(1, 0, 1, 0, 0),
                            intArrayOf(1, 0, 1, 0, 0)
                        ), arrayOf( // o
                            intArrayOf(0, 1, 1, 0, 0),
                            intArrayOf(1, 0, 0, 1, 0),
                            intArrayOf(1, 0, 0, 1, 0),
                            intArrayOf(1, 0, 0, 1, 0),
                            intArrayOf(0, 1, 1, 0, 0)
                        ), arrayOf( // w
                            intArrayOf(1, 0, 0, 0, 1),
                            intArrayOf(1, 0, 0, 0, 1),
                            intArrayOf(1, 0, 1, 0, 1),
                            intArrayOf(1, 1, 0, 1, 1),
                            intArrayOf(1, 0, 0, 0, 1)
                        )
                    )

                    for (l in letters.indices) {
                        val letter = letters[l]
                        val offsetX: Float = l * (5 * spacing + charSpacing)

                        for (row in 0..4) {
                            val rowData = letter[row] ?: continue
                            for (col in 0..4) {
                                if (rowData[col] == 1) {
                                    val x = baseX + offsetX + col * spacing
                                    val y = baseY + (4 - row) * spacing
                                    Call.effect(fx, x, y, 0f, color)
                                }
                            }
                        }
                    }
                }
            }, 0f, 30f)


 */
            try {
                server = MapUploadServer(WEBPORT)
            } catch (_: Exception) {
            }

            Timer.schedule({
                if (Vars.state.isGame) {
                    val minutes =(Vars.state.tick / 60f / 60f).toInt()
                    val folder: Fi = Vars.saveDirectory.child("snapshots")
                    val file = folder.child("autosave-${minutes}m.msav")
                    SaveIO.save(file)
                }
            }, 70f, 70f)

            val folder: Fi = Vars.saveDirectory.child("snapshots")
            folder.list()?.filter { it.name().startsWith("autosave-") }?.forEach {
                it.delete()
            }

            Timer.schedule({
                if (Vars.state.isGame && Vars.state.rules.pvp && hexMode) {
                    if(!voteInProgress) {
                        showHexHud()
                    }
                    checkHexGameEnd()
                }
            }, 0f, 2f)
        }

        Events.on<PlayEvent?>(PlayEvent::class.java) { event: PlayEvent? ->
            resetGameState()
            playerTeams.clear()
            clearStatus()
            val map = Vars.state.map
            val mapDescription = Vars.state.map.description()
            val descLower = mapDescription.lowercase()
            if (descLower.contains("[@pvp]")) {
                Vars.state.rules = map.applyRules(Gamemode.pvp)
            } else if (descLower.contains("[@survival]")) {
                Vars.state.rules = map.applyRules(Gamemode.survival)
            } else if (descLower.contains("[@sandbox]")) {
                Vars.state.rules = map.applyRules(Gamemode.sandbox)
            } else if (descLower.contains("[@attack]")) {
                Vars.state.rules = map.applyRules(Gamemode.attack)
            } else {
                Vars.state.rules = map.applyRules(Gamemode.pvp)
            }
            Vars.state.rules.pvpAutoPause = false
            if (Vars.state.rules.unitCap == 0) {
                Vars.state.rules.unitCap = 10
            }
            val snow = WeatherEntry()
            snow.weather = Weathers.snow
            snow.intensity = 0.1f
            snow.always = false
            snow.minFrequency = 5 * Time.toMinutes
            snow.maxFrequency = 10 * Time.toMinutes
            snow.minDuration = 6 * Time.toMinutes
            snow.maxDuration = 12 * Time.toMinutes
            Vars.state.rules.weather.add(snow)
            if (!Vars.state.rules.lighting) {
                Vars.state.rules.ambientLight.set(Color(0.1f, 0.1f, 0.2f, 0.3f))
                Vars.state.rules.lighting = true
            }
            Vars.state.rules.hideBannedBlocks = true
            if (descLower.contains("[@fly]")) {
                applyfly()
            } else {
                applyUnfly()
            }
            UnitTypes.poly.defaultCommand = UnitCommand.assistCommand
            Timer.schedule({
                Groups.player.each { p: Player? ->
                    if (p?.con != null && !p.isLocal) {
                        showMapLabel(p)
                    }
                }
            }, 2f)

            if (descLower.contains("[@hex=")) {
                try {
                    val start: Int = descLower.indexOf("[@hex=") + 6
                    val end: Int = descLower.indexOf("]", start)
                    val numberStr = descLower.substring(start, end).trim { it <= ' ' }
                    val hexTime: Int = numberStr.toInt()
                    if (hexTime >= 15 && hexTime <= 55) {
                        hexMode = true
                        hexGameDurationMinutes = hexTime
                    }
                } catch (_: Exception) {
                }
            }
            if (hexMode) {
                coreNumberMap.clear()
                lastOwnerMap.clear()
                var coreIdCounter = 1

                for (b in Groups.build) {
                    if (b is CoreBuild) {
                        val key = b.tile.x.toString() + "," + b.tile.y
                        coreNumberMap.put(key, coreIdCounter++)
                        lastOwnerMap.put(key, b.team)
                    }
                }
            }
            val all = Vars.maps.customMaps().copy()
            val candidates = all.select { m ->
                val desc = m?.description()?.lowercase() ?: ""
                !listOf("[@survival]", "[@sandbox]", "[@attack]").any { desc.contains(it) }
            }.toMutableList()
            usedMaps.add(Vars.state.map)
            val unusedCandidates = candidates.filter { it !in usedMaps && it != Vars.state.map }.toMutableList()
            if (unusedCandidates.isEmpty()) {
                usedMaps.clear()
                usedMaps.add(Vars.state.map)
                unusedCandidates.addAll(candidates.filter { it != Vars.state.map })
            }
            if (unusedCandidates.isEmpty()) {
                Vars.maps.setNextMapOverride(null)
            } else {
                val next = unusedCandidates.random()
                Vars.maps.setNextMapOverride(next)
            }
        }

        Events.on<PlayerLeave?>(PlayerLeave::class.java, Cons { e: PlayerLeave? ->
            val player = e?.player ?: return@Cons

            if (player.uuid() == null) {
                return@Cons
            }
            val uuid = player.uuid()
            if (votes.contains(uuid)) {
                votes.remove(uuid)
            }
            if (voteInProgress && voteInitiator?.uuid() == player.uuid()) {
                Call.announce("[#D0D0D8DD]Vote cancelled: initiator ${player.plainName()} left the game.[]")
                endVote()
            }
            if (voteInProgress && kickVote && targetPlayer?.uuid() == player.uuid()) {
                val name = player.name()
                restorePlayerEditsWithinSeconds(uuid, 180)
                player.kick("Leave midway", 5 * 60 * 60)
                Call.announce("[#D0D0D8DD]${name} left during vote and was banned.[]")
                endVote()
            }
        })

        Events.on<GameOverEvent?>(GameOverEvent::class.java) { e: GameOverEvent? ->
            clearStatus()
            val cx = Vars.world.width() * Vars.tilesize / 2f
            val cy = Vars.world.height() * Vars.tilesize / 2f
            Call.sound(Sounds.explosionbig, cx, cy, 1f)

            val winner = e?.winner ?: return@on

            val hasWinner = winner !== Team.derelict && Vars.state.teams.get(winner).hasCore()

            if (Vars.state.rules.mode() == Gamemode.pvp && hasWinner) {
                val winnerOnline = Groups.player.count { p ->
                    p?.team() === winner
                }
                val winScore: Int = max(25, 40 - max(1, winnerOnline) * 4)

                val teamSizes: kotlin.collections.Map<Team, Int> = playerTeams.values
                    .filter { it != Team.derelict }
                    .groupingBy { it }
                    .eachCount()

                for ((uuid, team) in playerTeams) {
                    if (team == Team.derelict) continue

                    val online = Groups.player.find { it?.uuid() == uuid }

                    if (team == winner) {
                        getAccountByUuid(uuid)?.let { acc ->
                            acc.score += winScore
                            ++acc.wins
                            updateAccount(acc)
                        }
                        if (online != null) {
                            Call.announce(online.con, "Victory! You got [#D1DBF2DD]$winScore[] points.")
                        }
                    } else {
                        val size = teamSizes.getOrDefault(team, 1L).toInt()
                        val penalty = max(20, 50 - size * 3)
                        getAccountByUuid(uuid)?.let { acc ->
                            acc.score = max(0, acc.score - penalty)
                            updateAccount(acc)
                        }
                    }
                }

            } else if (hasWinner) {
                val winners = Groups.player.copy().select { p -> p?.team() === winner }

                val total = winners.size
                val score: Int = max(25, 40 - total * 4)

                winners.each(Cons { p: Player? ->
                    p?.let {
                        getAccountByUuid(p.uuid())?.let { acc ->
                            acc.score += score
                            ++acc.wins
                            updateAccount(acc)
                        }
                        Call.announce(it.con, "Victory! You got [#D1DBF2DD]$score[] points.")
                    }
                })

            }

            resetGameState()
            playerTeams.clear()

            val folder: Fi = Vars.saveDirectory.child("snapshots")
            folder.list()?.filter { it.name().startsWith("autosave-") }?.forEach {
                it.delete()
            }
        }

        Events.on<PlayerJoin?>(PlayerJoin::class.java) { e: PlayerJoin? ->
            val player = e?.player ?: return@on
            val playerUUID = player.uuid()
            if (player.team() !== Team.derelict && player.team().data().hasCore()) {
                showMeteorEffect(player)
            }
            showMapLabel(player)
            if (!playerLang.containsKey(playerUUID)) {
                val lang = player.locale
                val detectedLang = if (lang == null || lang.isEmpty()) "en" else lang
                playerLang.put(playerUUID, detectedLang)
            }
            if (!lbEnabled.contains(playerUUID)) {
                lbEnabled.put(playerUUID, true)
            }
            showIntroMenu(player)
        }

        Events.on<BlockDestroyEvent?>(BlockDestroyEvent::class.java) { event: BlockDestroyEvent? ->
            val team = event?.tile?.team() ?: return@on
            if (event.tile.block() is CoreBlock) {

                if (team !== Team.derelict && team.cores().size <= 1) {
                    team.data().players.each(Cons { p: Player? ->
                        if (Vars.state.rules.mode() == Gamemode.pvp) {
                            val message = "Your team has lost."
                            p?.let { Call.announce(it.con, message) }

                        } else {
                            val message = "You has lost."
                            p?.let { Call.announce(it.con, message) }
                        }
                    })
                }
            }
        }

        try {
            geoDB = DatabaseReader.Builder(ipGEOFile.file()).build()
        } catch (e: Exception) {
            throw RuntimeException("Cannot load GeoIP", e)
        }
        showLeaderboard()
        val provider = Reflect.get<ArcNetProvider>(Vars.net, "provider")
        val server = Reflect.get<Server>(provider, "server")

        server.setDiscoveryHandler { address: InetAddress?, handler: ReponseHandler? ->
            var lang = "en"
            try {
                val ip = address?.address?.let { InetAddress.getByAddress(it) } ?: return@setDiscoveryHandler
                val response = geoDB?.country(ip) ?: return@setDiscoveryHandler
                val isoCode = response.country.isoCode
                if (isoCode != null) {
                    lang = when (isoCode) {
                        "CN", "TW", "HK", "MO", "SG", "MY" -> "zh"
                        "JP" -> "ja"
                        "KR" -> "ko"
                        "RU", "KZ", "KG", "TJ", "UZ", "AM", "GE" -> "ru"
                        "UA" -> "uk"
                        "BY" -> "be"
                        "RS", "ME", "BA" -> "sr"
                        "BG" -> "bg"
                        "FR" -> "fr"
                        "ES", "MX", "AR", "CL", "CO", "VE", "PE", "EC", "UY", "PY", "BO", "GT", "SV", "HN", "NI", "CR", "PA", "CU", "DO", "PR" -> "es"
                        "PT", "BR", "AO", "MZ", "GW", "CV", "ST", "TL" -> "pt"
                        "IT" -> "it"
                        "DE", "AT", "CH" -> "de"
                        "NL", "BE" -> "nl"
                        "PL" -> "pl"
                        "CZ", "SK" -> "cs"
                        "RO", "MD" -> "ro"
                        "HU" -> "hu"
                        "FI" -> "fi"
                        "SE" -> "sv"
                        "DK" -> "da"
                        "EE" -> "et"
                        "LT" -> "lt"
                        "TR" -> "tr"
                        "TM" -> "tk"
                        "VN" -> "vi"
                        "TH" -> "th"
                        "ID" -> "id"
                        "PH" -> "fil"
                        "ES-PV" -> "eu"
                        "GR" -> "el"
                        "IN" -> "hi"
                        else -> "en"
                    }
                }
            } catch (_: Exception) {
                lang = "en"
            }
            val name = when (lang) {
                "zh" -> "雪"
                "ja" -> "スノー"
                "ko" -> "스노우"
                "ru" -> "Сноу"
                "uk" -> "Сніг"
                "be", "sr" -> "Снег"
                "bg" -> "Сняг"
                "fr" -> "Neige"
                "es" -> "Nieve"
                "pt" -> "Neve"
                "it" -> "Neve"
                "de" -> "Schnee"
                "nl" -> "Sneeuw"
                "pl" -> "Śnieg"
                "cs" -> "Sníh"
                "ro" -> "Zăpadă"
                "hu" -> "Hó"
                "fi" -> "Lumi"
                "sv" -> "Snö"
                "da" -> "Sne"
                "et" -> "Lumi"
                "lt" -> "Sniegas"
                "tr" -> "Kar"
                "tk" -> "Gar"
                "vi" -> "Tuyết"
                "th" -> "หิมะ"
                "id" -> "Salju"
                "fil" -> "Niyebe"
                "eu" -> "Elur"
                "el" -> "Χιόνι"
                "hi" -> "बर्फ"
                else -> "Snow"
            }

            val minutes = (Vars.state.tick / 60f / 60f).toInt()
            val displayDesc = getLocalizedDisplayDesc(lang, minutes)
            val mapName = Vars.state.map.name()
            val modeName = Vars.state.rules.modeName ?: ""
            val totalPlayers: Int = max(0, Core.settings.getInt("totalPlayers", Groups.player.size()))
            val wave = Vars.state.wave
            val build = Version.build
            val limit: Int = max(Vars.netServer.admins.playerLimit, 0)
            val buffer = ByteBuffer.allocate(500)
            writeString(buffer, name, 100)
            writeString(buffer, mapName, 64)
            buffer.putInt(totalPlayers)
            buffer.putInt(wave)
            buffer.putInt(build)
            writeString(buffer, Version.type, 50)
            buffer.put(Vars.state.rules.mode().ordinal.toByte())
            buffer.putInt(limit)
            writeString(buffer, displayDesc, 200)
            writeString(buffer, modeName, 50)

            buffer.position(0)
            handler?.respond(buffer)
        }

        Vars.netServer.assigner = assignTeam
        Vars.netServer.admins.addActionFilter(ActionFilter { action: PlayerAction? ->
            if (action?.player == null) return@ActionFilter false
            val uuid = action.player.uuid()
            if (action.tile != null) {
                val tile = action.tile
                if (action.type == ActionType.breakBlock) {
                    recordEdit(uuid, tile)
                }
            }
            return@ActionFilter true
        })

        Vars.net.handleServer<ConnectPacket?>(
            ConnectPacket::class.java,
            Cons2 { con: NetConnection?, packet: ConnectPacket? ->
                if (con?.kicked == true) return@Cons2

                con?.let {
                    Events.fire<ConnectPacketEvent?>(ConnectPacketEvent(it, packet))
                    it.connectTime = Time.millis()
                }

                val uuid = packet?.uuid ?: return@Cons2
                val name = packet.name
                val ip = con?.address ?: return@Cons2
                if (Vars.netServer.admins.isIPBanned(ip) ||
                    Vars.netServer.admins.isSubnetBanned(ip) ||
                    Vars.netServer.admins.isIDBanned(uuid)
                ) {
                    con.kick("You have been banned.", 0)
                    return@Cons2
                }

                if (name.trim { it <= ' ' }
                        .isEmpty() || name.isEmpty() || name.length > 40) {
                    con.kick("Your name $name may be illegal.", 0)
                    return@Cons2
                }
                if (con.hasBegunConnecting) {
                    con.kick(KickReason.idInUse, 0)
                    return@Cons2
                }
                con.hasBegunConnecting = true
                con.mobile = packet.mobile
                if (packet.uuid == null || packet.usid == null) {
                    con.kick(KickReason.idInUse, 0)
                    return@Cons2
                }

                val kickTime = Vars.netServer.admins.getKickTime(uuid, ip)
                if (Time.millis() < kickTime) {
                    con.kick("You were recently kicked. Please wait.", 0)
                    return@Cons2
                }

                if (packet.versionType == null || ((packet.version == -1 || packet.versionType != Version.type) && Version.build != -1 && !Vars.netServer.admins.allowsCustomClients())) {
                    con.kick(
                        if (Version.type != packet.versionType) KickReason.typeMismatch else KickReason.customClient,
                        0
                    )
                    return@Cons2
                }

                if (packet.version != Version.build && Version.build != -1 && packet.version != -1) {
                    con.kick(
                        if (packet.version > Version.build) KickReason.serverOutdated else KickReason.clientOutdated,
                        0
                    )
                    return@Cons2
                }

                if (Groups.player.contains { p -> p?.name?.equals(packet.name, ignoreCase = true) == true }) {
                    con.kick(KickReason.nameInUse, 0)
                    return@Cons2
                }

                if (!Vars.netServer.admins.isAdmin(
                        uuid,
                        packet.usid
                    ) && Vars.netServer.admins.playerLimit > 0 && Groups.player.size() >= Vars.netServer.admins.playerLimit
                ) {
                    con.kick(KickReason.playerLimit)
                    return@Cons2
                }

                if (Groups.player.contains { p -> p?.name?.equals(packet.name, ignoreCase = true) == true }) {
                    con.kick(KickReason.nameInUse, 0)
                    return@Cons2
                }

                for (other in Vars.net.connections) {
                    if (other !== con && uuid == other.uuid) {
                        con.kick(KickReason.idInUse, 0)
                        return@Cons2
                    }
                }

                packet.name = Vars.netServer.fixName(packet.name)
                if (packet.locale == null) packet.locale = "en"
                Vars.netServer.admins.updatePlayerJoined(uuid, ip, packet.name)

                if (packet.version == -1) {
                    con.modclient = true
                }

                if (!Vars.netServer.admins.isWhitelisted(packet.uuid, packet.usid)) {
                    con.kick("You are not whitelisted.", 0)
                    return@Cons2
                }

                val info = Vars.netServer.admins.getInfo(uuid)
                val player = Player.create()
                val acc = getAccountByUuid(uuid)

                if (acc != null) {
                    val coreAdmin = acc.uuids.any { accUuid ->
                        Vars.netServer.admins.getInfo(accUuid)?.admin == true
                    }
                    if (!coreAdmin && acc.isAdmin) {
                        acc.isAdmin = false
                        updateAccount(acc)
                    }
                    player.admin = coreAdmin && acc.isAdmin
                    player.name = acc.nick


                } else {
                    player.name = packet.name
                    player.admin = false
                }
                player.con = con
                player.con.usid = packet.usid
                player.con.uuid = uuid
                player.con.mobile = packet.mobile
                player.locale = packet.locale
                player.color.set(packet.color).a(1f)
                if (!player.admin && !info.admin) {
                    info.adminUsid = packet.usid
                }
                con.player = player
                player.team(Vars.netServer.assignTeam(player))

                Vars.netServer.sendWorldData(player)
                Events.fire<PlayerConnect?>(PlayerConnect(player))
            })
        Vars.net.handleServer<Connect>(
            Connect::class.java
        ) { con: NetConnection?, packet: Connect? ->

            Events.fire(ConnectionEvent(con))
            val sameIpConns = Seq.with(Vars.net.connections)
                .filter { it.address == con?.address }

            if (sameIpConns.size >= 3) {
                con?.address?.let { Vars.netServer.admins.blacklistDos(it) }
                sameIpConns.forEach(NetConnection::close)
            }
        }

        Vars.net.handleServer<AdminRequestCallPacket?>(
            AdminRequestCallPacket::class.java,
            Cons2 { con: NetConnection?, packet: AdminRequestCallPacket? ->
                val admin = con?.player ?: return@Cons2
                val target = packet?.other ?: return@Cons2
                val action = packet.action ?: return@Cons2

                if (!admin.admin) return@Cons2
                if (target.admin && target !== admin) return@Cons2

                Events.fire<AdminRequestEvent?>(AdminRequestEvent(admin, target, action))
                when (action) {
                    AdminAction.kick -> {
                        restorePlayerEditsWithinSeconds(target.uuid(),180)
                        Call.announce(
                            "${admin.name()}[#D0D0D8DD] kicked []${target.name()}[#D0D0D8DD] and reverted buildings from the last 180 seconds.[]"
                        )
                        target.kick(KickReason.kick)
                    }

                    AdminAction.ban -> {
                        restorePlayerEditsWithinSeconds(target.uuid(), 180)
                        Call.announce(
                            "${admin.name()}[#D0D0D8DD] kicked []${target.name()}[#D0D0D8DD] and reverted buildings from the last 180 seconds.[]"
                        )
                        getAccountByUuid(target.uuid())?.let { acc ->
                            acc.banUntil = Time.millis() + 30 * 60_000L
                            updateAccount(acc)
                        }
                        target.kick(KickReason.banned, 30 * 60 * 1000)
                    }


                    AdminAction.trace -> {
                        val trace = TraceInfo(
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
                        Call.traceInfo(con, target, trace)
                    }

                    AdminAction.wave -> {
                        Vars.logic.skipWave()
                        Call.announce(admin.name + "[#D0D0D8DD] has skipped the wave.")
                    }

                    AdminAction.switchTeam -> {
                        val param = packet.params
                        if (param is Team) {
                            packet.other.team(param)
                        }
                    }
                }
            })

        Vars.netServer.admins.addChatFilter(ChatFilter { player: Player?, message: String? ->
            if (message.isNullOrBlank()) return@ChatFilter null
            if (player == null) return@ChatFilter null
            if (isProhibited(player.uuid())) return@ChatFilter null
            val trimmedMessage = message.trim()
            val lowerMessage = trimmedMessage.lowercase()

            fun broadcastMessage(sender: Player, msg: String) {
                val senderName = getDisplayName(sender)
                sender.sendMessage("[#D0D0D8DD]$senderName[]: [#E2E2E2DD]$msg[]")
                Groups.player.each { receiver ->
                    if (receiver == sender) return@each

                    val lang = receiver.uuid()?.let { playerLang[it] }
                    val needTranslate = !lang.isNullOrEmpty() && lang != "off"

                    if (needTranslate) {
                        translate(msg, "auto", lang, { translated ->
                            val displayMsg = if (translated != msg) "$msg [#ACACADCC]($translated)[]" else msg
                            receiver.sendMessage("[#D0D0D8DD]$senderName[]: [#E2E2E2DD]$displayMsg[]")
                        }, {
                            receiver.sendMessage("[#D0D0D8DD]$senderName[]: [#E2E2E2DD]$msg[]")
                        })
                    } else {
                        receiver.sendMessage("[#D0D0D8DD]$senderName[]: [#E2E2E2DD]$msg[]")
                    }
                }
            }
            if (trimmedMessage.startsWith("/") || isYes(lowerMessage)) {
                if (!voteInProgress || !isYes(lowerMessage) || player == voteInitiator) return@ChatFilter null
                val uuid = player.uuid() ?: return@ChatFilter null
                if (votes.contains(uuid)) return@ChatFilter null

                votes.add(uuid)
                val currentVotes = votes.size
                val playerCount = Groups.player.size()
                val requiredVotes = when {
                    playerCount <= 2 -> playerCount
                    playerCount == 3 -> 2
                    else -> ceil(playerCount * RATIO).toInt()
                }

                if (kickVote) {
                    val tp = targetPlayer ?: return@ChatFilter null
                    if (currentVotes >= requiredVotes) {
                        Call.announce("[#D0D0D8DD]Vote passed. Kicking []${tp.name()}[#D0D0D8DD] and reverted buildings from the last 180 seconds.[]")
                        restorePlayerEditsWithinSeconds(tp.uuid(),180)
                        tp.kick("[#D0D0D8DD]You were kicked by vote.[]", 30 * 60 * 1000)
                        endVote()
                    }
                } else {
                    val map = targetMap ?: return@ChatFilter null
                    if (currentVotes >= requiredVotes) {
                        Call.announce("[#D0D0D8DD]Vote passed. Loading map []${map.name()}[#D0D0D8DD].[ ]")
                        playerTeams.clear()
                        clearStatus()
                        loadMap(map)
                        endVote()
                        resetGameState()
                    }
                }

            } else {
                broadcastMessage(player, message)
            }

            null
        })

    }

    override fun registerClientCommands(handler: CommandHandler) {
        handler.removeCommand("a")
        handler.removeCommand("t")
        handler.removeCommand("vote")
        handler.removeCommand("votekick")
        handler.register<Player>("players", "", "Show players") { _, v ->
            val list = Groups.player
                .filter { it.con != null && idByUuid.containsKey(it.uuid()) }
                .sortedBy { it.name() }
            menuCache[v.uuid()] = list
            val rows = ArrayList<Array<String>>(list.size + 1)
            rows.add(arrayOf("[#CED7E0FF]"))
            list.forEach { rows.add(arrayOf("[#F1F1F1DD]${it.name()}")) }
            Call.followUpMenu(v.con, playersMenuId, "Players", "", rows.toTypedArray())
        }
        handler.register<Player?>(
            "help",
            "",
            "Show all commands."
        ) { _, player ->
            if (player == null) return@register
            showHelpMenu(player, 1)
        }
        handler.register<Player?>(
            "logout",
            "",
            "Logout and reset player state."
        ) { _, player ->
            if (player == null) return@register

            player.unit()?.kill()
            player.team(Team.derelict)

            val acc = getAccountByUuid(player.uuid()) ?: return@register
            if (acc.isStrict) {
                Call.announce(player.con, "[#D0D0D0DD]This account is in strict mode.")
                return@register
            }
            acc.uuids.remove(player.uuid())
            idByUuid.remove(player.uuid())

            Call.announce(player.con, "[#D0D0D8DD]You have been logged out.")
        }

        handler.register<Player?>(
            "servers",
            "Show all servers."
        ) { _, player ->
            if (player == null) return@register
            if (isProhibited(player.uuid())) return@register
            showServerListMenu(player)
        }
        handler.register<Player>("rules", "Edit game rules") { _, player ->
            if (isProhibited(player.uuid())) return@register
            showRulesMenu(player)
        }
        handler.register<Player>("surrender", "Vote to surrender") { _, player ->
            if (isProhibited(player.uuid())) return@register
            val team = player.team()

            val teamPlayerCount = Groups.player.count { it.team() == team }

            if (teamPlayerCount < 3) {
                Call.announce(player.con(), "[#D0D0D8DD]You need at least 3 players \n on your team to initiate a surrender vote.[]")
                return@register
            }

            if (team == Team.derelict || !team.data().hasCore()) {
                Call.announce(player.con(), "[#D0D0D8DD]You must have a core \n and not be in the derelict team to surrender.[]")
                return@register
            }

            val buttons = arrayOf(arrayOf("[#F1F1F1DD]\uE800", "[#F1F1F1DD]\uE815"))
            val menuId = Menus.registerMenu { _, choice ->
                if (choice == 0) {
                    beginTeamVote(player)
                }
            }

            Call.menu(
                player.con,
                menuId,
                "Confirm Surrender",
                "\nAre you sure?\n",
                buttons
            )
        }


        handler.register<Player?>(
            "t",
            "<message...>",
            "Send a message to your teammates.",
            CommandRunner { args: Array<String?>?, player: Player? ->
                if (player == null) return@CommandRunner
                if (isProhibited(player.uuid())) return@CommandRunner
                if (args.isNullOrEmpty()) {
                    return@CommandRunner
                }

                val message = args.joinToString(" ")
                val playerName = player.name ?: "<unknown>"
                val prefix = "[#D0D0D8DD]<Team> [#D0D0D8DD]$playerName[]: [#D0D0D8DD]"
                val formattedMessage = "$prefix$message[]"

                player.sendMessage(formattedMessage)

                Groups.player.each { receiver ->
                    if (receiver == null || receiver === player) return@each
                    if (receiver.team() != player.team()) return@each

                    val lang = receiver.uuid()?.let { playerLang[it] }
                    val needsTranslation = !lang.isNullOrEmpty() && lang != "off"

                    if (needsTranslation) {
                        translate(message, "auto", lang, { translated ->
                            val msgToSend =
                                if (translated != message) "$prefix$message [#ACACADCC]($translated)[]" else formattedMessage
                            receiver.sendMessage(msgToSend)
                        }, {
                            receiver.sendMessage(formattedMessage)
                        })
                    } else {
                        receiver.sendMessage(formattedMessage)
                    }
                }
            }
        )

        handler.register<Player>(
            "revert",
            "[player/all] [time]",
            "Revert building edits."
        ) { args, player ->
            if (isProhibited(player.uuid())) return@register
            if (args.isEmpty()) {
                val options = mutableListOf<Array<String?>>()
                options.add(arrayOf("[#F1F1F1FF]"))
                val targets = mutableListOf<String>()
                targets.add("close")
                targets.add(ALL_PLAYERS_ID)
                options.add(arrayOf("[#D0D0D0DD]All[]"))

                lastEdits.keys().forEach { uuid ->
                    val name = Vars.netServer.admins.getInfo(uuid)?.lastName ?: "unknown"
                    options.add(arrayOf(name))
                    targets.add(uuid)
                }

                if (targets.size <= 2) {
                    Call.announce(player.con, "[#D0D0D8DD]No players with recorded edits.")
                    return@register
                }

                revertMenuTargets[player.uuid()] = targets
                Call.followUpMenu(player.con, revertMenuId, "[#E2E2E2DD]Select a player to revert", "", options.toTypedArray())
                return@register
            }

            val target = args[0]
            val seconds = args.getOrNull(1)?.toIntOrNull()

            if (seconds == null || seconds <= 0) {
                Call.announce(player.con, "[#D0D0D8DD]Invalid time: must be a number > 0.")
                return@register
            }

            val uuid = if (target.equals("all", ignoreCase = true)) ALL_PLAYERS_ID else {
                Groups.player.find { it.name.equals(target, ignoreCase = true) }?.uuid()
                    ?: lastEdits.keys().find {
                        Vars.netServer.admins.getInfo(it).lastName?.equals(target, ignoreCase = true) == true
                    }
                    ?: run {
                        Call.announce(player.con, "[#D0D0D8DD]Player not found or has no recorded edits.")
                        return@register
                    }
            }
            runRevert(uuid, seconds)
        }

        handler.register<Player?>("rollback", "[page]", "Rollback to a snapshot.") { args, player ->
            if (player == null) return@register
            if (isProhibited(player.uuid())) return@register
            val page = args?.getOrNull(0)?.toIntOrNull() ?: 1
            showRollbackMenu(player, page)
        }
        handler.register<Player>(
            "rank", "[page]", "Show player rankings."
        ) { args, player ->
            if (isProhibited(player.uuid())) return@register
            val page = args?.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
            showRankMenu(player, page)
        }

        handler.register<Player?>(
            "switch",
            "Change your team."
        ) { args, player ->
            player?.let {
                if (isProhibited(player.uuid())) return@register
                val uuid = it.uuid()
                val currentTeam = it.team()
                val rank = getAccountByUuid(uuid)?.score ?: 0
                if ((playerTeams.containsKey(uuid) && rank >= 2500) || currentTeam === Team.derelict || hexMode) {
                    playerTeams.remove(uuid)
                    it.clearUnit()
                    if (it.con != null && !it.isLocal) {
                        show(it)
                    }
                } else {
                    Call.announce(it.con(),"[#D0D0D8DD]You need to be at least 2500 points or enter hex mode.[]")
                }
            }
        }
        handler.register<Player?>(
            "msg",
            "[player] [message]",
            "Send a message to another player.",
            CommandRunner { args, sender ->
                if (sender == null) return@CommandRunner
                if (isProhibited(sender.uuid())) return@CommandRunner
                val others = Groups.player.filter { it != sender && it.con != null }
                if (others.isEmpty()) {
                    Call.announce(sender.con,"[#D0D0D8DD]No other players.[]")
                    return@CommandRunner
                }
                if (args.isNullOrEmpty()) {
                    val rows = mutableListOf<Array<String?>>()
                    rows.add(arrayOf("[#F1F1F1FF][]"))

                    val others = Groups.player.filter { it != sender && it.con != null }
                    for (p in others) {
                        rows.add(arrayOf("[#F1F1F1FF]${p.name()}[]"))
                    }

                    Call.followUpMenu(sender.con, msgMenuId, "[#E2E2E2DD]Select a player", "", rows.toTypedArray())
                    return@CommandRunner
                }

                val targetName = args[0]
                val message = args.drop(1).joinToString(" ")

                if (message.isBlank()) {
                    sender.sendMessage("[#E2E2E2DD]Please provide a message to send.")
                    return@CommandRunner
                }

                val matched = others.filter {
                    it.name().contains(targetName, ignoreCase = true)
                }

                when {
                    matched.isEmpty() -> {
                        sender.sendMessage("[#E2E2E2DD]No player found matching: [white]$targetName[]")
                    }
                    matched.size > 1 -> {
                        val names = matched.joinToString(", ") { "[white]${it.name()}[]" }
                        sender.sendMessage("[#E2E2E2DD]Multiple players matched: $names\n[lightgray]Please type a more specific name.")
                    }
                    else -> {
                        val target = matched.first()
                        target.sendMessage("[#E2E2E2DD]\uE836 ${sender.name()}:[][#E2E2E2DD] $message")
                        sender.sendMessage("[#E2E2E2DD]\uE835 ${target.name()}:[][#E2E2E2DD] $message")

                    }
                }
            }
        )

        handler.register<Player?>("upload", "Upload maps.") { _, p ->
            if (p == null) return@register
            val uuid = p.uuid()
            val approved = Vars.netServer.admins.isAdmin(uuid, p.usid())
            val score   = getAccountByUuid(uuid)?.score ?: 0
            if (score > 100 || p.admin || approved) {
                val url = "http://$WEBURL:$WEBPORT/?token=${generateToken(uuid)}"
                Call.openURI(p.con, url)
            } else {
                Call.announce(p.con(), "[#D0D0D8DD]You need 100+ points or admin to upload maps.")
            }
        }


        handler.register<Player?>(
            "settings", "", "Your settings."
        ) { _, player ->
            if (player != null) {
                if (isProhibited(player.uuid())) return@register
                showSettingsMenu(player)
            }
        }

        handler.register<Player?>(
            "maps",
            "[page]",
            "View all maps."
        ) { args, player ->
            val page = args?.getOrNull(0)?.toIntOrNull() ?: 1
            if (player != null) {
                if (isProhibited(player.uuid())) return@register
                showMapMenu(player, page)
            }
        }

        handler.register<Player?>(
            "votekick",
            "[player] [reason]",
            "Start a vote to kick a player.",
            CommandRunner { args, player ->
                if (player == null) return@CommandRunner
                if (isProhibited(player.uuid())) return@CommandRunner
                if (voteInProgress) {
                    Call.announce(player.con(),"[#D0D0D8DD]A vote is already in progress.[]")
                    return@CommandRunner
                }
                if (Groups.player.size() < 3) {
                    Call.announce(player.con(),"[#D0D0D8DD]No players available to vote kick.[]")
                    return@CommandRunner
                }
                if (args.isNullOrEmpty()) {
                    val kickablePlayers = Groups.player.filter {
                        it != null && it.team() == player.team() && !it.admin && it.con != null && it != player
                    }

                    if (kickablePlayers.isEmpty()) {
                        Call.announce(player.con(),"[#D0D0D8DD]No players available to vote kick.[]")
                        return@CommandRunner
                    }
                    val rows = mutableListOf<Array<String?>>()
                    rows.add(arrayOf("[#F1F1F1FF]"))

                    rows.addAll(kickablePlayers.map { arrayOf("[#F1F1F1FF]${it!!.name()}[]") })

                    Call.followUpMenu(player.con, voteKickMenuId, "[#E2E2E2DD]Select a player", "", rows.toTypedArray())
                    return@CommandRunner
                }

                val targetName = args[0]

                val target = if (targetName.startsWith("#") && targetName.length > 1 && Strings.canParseInt(targetName.substring(1))) {
                    val id = Strings.parseInt(targetName.substring(1))
                    Groups.player.find { it?.id() == id }
                } else {
                    Groups.player.find { it?.name?.contains(targetName, ignoreCase = true) == true }
                }

                if (target == null) {
                    Call.announce(player.con,"[#D0D0D8DD]Player not found.[]")
                    return@CommandRunner
                }

                val reason = if (args.size > 1) args.copyOfRange(1, args.size).joinToString(" ") else null

                when {
                    target == player -> Call.announce(player.con(),"[#D0D0D8DD]You cannot kick yourself.[]")
                    target.admin -> Call.announce(player.con(),"[#D0D0D8DD]You cannot kick an admin.[]")
                    player.admin -> {
                        Call.announce("${player.name()}[#D0D0D8DD] kicked []${target.name()}[#D0D0D8DD] directly. [][#ECECECEE]Reason: [][#D0D0D8DD]$reason")
                        restorePlayerEditsWithinSeconds(target.uuid(),180)
                        target.kick("[#D0D0D8DD]You were kicked by an admin. Reason: $reason")
                    }
                    else -> beginVote(player, true, null, target, reason)
                }
            }
        )
    }
    class MapUploadServer(port: Int) : NanoHTTPD(port) {

        private val mapDir: Fi = Vars.customMapDirectory
        private val recordFile: Fi = Vars.saveDirectory.child("maprecords.json")
        private val uploads = ObjectMap<String, String>()
        private val json = Json()

        init {
            if (!mapDir.exists()) mapDir.mkdirs()
            if (recordFile.exists()) {
                val txt = recordFile.readString()
                if (txt.isNotBlank()) {
                    val raw = json.fromJson(ObjectMap::class.java, txt) as ObjectMap<*, *>
                    raw.entries().forEach { e ->
                        val k = e.key as? String ?: return@forEach
                        val v = e.value as? String ?: return@forEach
                        uploads.put(k, v)
                    }
                }
            }
            start(SOCKET_READ_TIMEOUT, true)
        }

        override fun serve(session: IHTTPSession): Response {
            val token = session.parameters["token"]?.firstOrNull()
            val uuid = verifyToken(token)
            return when (session.method) {
                Method.GET  -> handleGet(session, uuid)
                Method.POST -> if (uuid == null) forbidden() else handlePost(session, uuid)
                else        -> newFixedLengthResponse("Unsupport")
            }
        }

        private fun handleGet(s: IHTTPSession, uuid: String?): Response {
            val file = s.parameters["file"]?.firstOrNull()
            return when (s.uri) {
                "/download" -> if (uuid == null) forbidden() else sendFile(file)
                "/delete"   -> if (uuid == null) forbidden() else deleteFile(file, uuid)
                else        -> if (uuid == null) forbidden() else page(uuid)
            }
        }


        private fun handlePost(s: IHTTPSession, uuid: String): Response {
            val files = HashMap<String?, String?>()
            s.parseBody(files)
            val name = s.parameters["file"]?.firstOrNull() ?: return bad("no file")
            val safe = Fi(name).name()
            if (!safe.endsWith(".msav") || !safe.matches("^[A-Za-z0-9_-]{1,30}\\.msav$".toRegex()))
                return bad("bad name")
            val upload = Fi(files["file"])
            if (upload.length() > 80 * 1024) return bad("too big")
            val dst = mapDir.child(safe)
            if (dst.exists() && uploads[safe] != uuid && !isAdmin(uuid)) return bad("denied")
            upload.copyTo(dst)
            uploads.put(safe, uuid)
            persist()
            Vars.maps.reload()
            return ok("uploaded")
        }

        private fun sendFile(name: String?): Response {
            if (name.isNullOrBlank() || !name.endsWith(".msav")) return bad("no file")
            val f = mapDir.child(name)
            if (!f.exists()) return notFound()
            return newChunkedResponse(Response.Status.OK, "application/octet-stream", f.read()).apply {
                addHeader("Content-Disposition", """attachment; filename="${f.name()}" """)
            }
        }

        private fun deleteFile(name: String?, uuid: String): Response {
            if (name.isNullOrBlank()) return bad("no file")
            val f = mapDir.child(name)
            if (!f.exists()) return notFound()
            if (uploads[name] != uuid && !isAdmin(uuid)) return bad("denied")
            f.delete()
            uploads.remove(name)
            persist()
            Vars.maps.reload()
            return ok("deleted")
        }

        private fun page(uuid: String): Response {
            val tok = "&token=${generateToken(uuid)}"
            val sb = StringBuilder()

            sb.append("""
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport"
                content="width=device-width,initial-scale=1,viewport-fit=cover">
          <title>Maps</title>
          <style>
            :root{
              --bg:#ffffff;
              --text:#1b1b1b;
              --accent:#52779b;
              --border:#e2e2e2;
            }
            @media (prefers-color-scheme:dark){
              :root{
                --bg:#1b1b1b;
                --text:#d0d0d0;
                --accent:#75adff;
                --border:#333333;
              }
            }
            *{box-sizing:border-box;margin:0;padding:0;}
            body{
              font-family:system-ui,sans-serif;
              background:var(--bg);
              color:var(--text);
              line-height:1.6;
              max-width:720px;
              margin:auto;
              padding:1rem;
              -webkit-font-smoothing:antialiased;
            }
            h1{font-size:1.5rem;margin:1.2rem 0;}
            form{margin-bottom:1rem;}
            input[type=file]{margin-right:.5rem}
            ul{list-style:none;padding-left:0}
            li{border-bottom:1px solid var(--border);padding:.6rem 0}
            a{color:var(--accent);text-decoration:none}
          </style>
        </head>
        <body>
          <h1>Upload Map</h1>
          <form method="POST" enctype="multipart/form-data">
            <input type="file" name="file" accept=".msav" required>
            <input type="submit" value="Upload">
          </form>
          <ul>
    """.trimIndent())

            Vars.maps.customMaps().forEach { m ->
                val fn = m.file.name()
                sb.append("<li><a href='/download?file=$fn$tok'>$fn</a> — ")
                    .append(m.name())
                if (uploads[fn] == uuid || isAdmin(uuid)) {
                    sb.append(" <a style=\"color:gray\" href='/delete?file=$fn$tok'>delete</a>")
                }
                sb.append("</li>")
            }

            sb.append("""
          </ul>
        </body>
        </html>
    """.trimIndent())

            return html(sb.toString())
        }

        private fun persist() = recordFile.writeString(json.toJson(uploads))

        private fun isAdmin(uuid: String): Boolean = Vars.netServer.admins.getInfo(uuid)?.admin ?: false


        private fun html(s: String) = newFixedLengthResponse(s)
        private fun ok(s: String) = html(s)
        private fun bad(s: String) = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, s)
        private fun forbidden() = newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "forbidden")
        private fun notFound() = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
    }


    private val assignTeam = TeamAssigner { player: Player?, players: Iterable<Player>? ->
        if (player == null) return@TeamAssigner Vars.state.rules.defaultTeam
        if (isProhibited(player.uuid())) return@TeamAssigner Team.derelict
        if (Vars.state.rules.pvp) {
            if (!hexMode) {
                val team: Team? = playerTeams[player.uuid()]
                if (team != null) {
                    if (!team.data().hasCore() && team != Team.derelict) {
                        Call.announce(player.con, "Your team has lost. \nPlease wait for the next round.")
                    }
                    if (team == Team.derelict) {
                        player.clearUnit()
                    }
                    return@TeamAssigner team
                } else {
                    Timer.schedule({
                        if (player.con != null) show(player)
                    }, 1.2f)
                    return@TeamAssigner Team.derelict
                }
            } else {
                if (!playerTeams.containsKey(player.uuid())) {
                    val re = Vars.state.teams.getActive().minByOrNull { data ->
                        if ((Vars.state.rules.waveTeam == data.team && Vars.state.rules.waves) ||
                            !data.hasCore() || data.team == Team.derelict
                        ) {
                            Int.MAX_VALUE.toFloat()
                        } else {
                            var count = 0
                            players?.forEach { other ->
                                if (other.team() == data.team && other != player) count++
                            }
                            count + Mathf.random(-0.1f, 0.1f)
                        }
                    }

                    val team = re?.team ?: Vars.state.rules.defaultTeam
                    playerTeams[player.uuid()] = team
                    Call.announce(player.con, "[#E2E2E2DD]You're now in ${team.coloredName()}.")
                    return@TeamAssigner team
                } else {
                    val team = playerTeams[player.uuid()] ?: Team.derelict
                    if (!team.data().hasCore() && team != Team.derelict) {
                        Call.announce(player.con, "Your team has lost. \nPlease wait for the next round.")
                    }
                    return@TeamAssigner team
                }
            }
        }
        Vars.state.rules.defaultTeam
    }

    private fun showMeteorEffect(player: Player) {
        val color = Color.valueOf("D8D490FF")
        Call.effect(Fx.shootSmokeSmite, player.x, player.y, 0f, color)
        Call.effect(Fx.fireballsmoke, player.x, player.y + 200, 0f, color)
        Timer.schedule({ Call.effect(Fx.blastExplosion, player.x, player.y, 0f, color) }, 1.2f)
    }


    private fun showHexHud() {
        val elapsedMinutes = (Vars.state.tick / 60f / 60f).toInt()

        val hexTotalMinutes: Int = hexGameDurationMinutes

        val defaultStatus: String = String.format(
            "[#D0D0D8DD]%dmin  [#F1F1F1DD]%dmin",
            hexTotalMinutes,
            elapsedMinutes
        )

        var changedCount = 0

        for (b in Groups.build) {
            if (b !is CoreBuild) continue

            val key = b.tile.x.toString() + "," + b.tile.y

            val coreId: Int? = coreNumberMap.get(key)
            if (coreId == null) continue

            val currentOwner = b.team
            val previousOwner: Team = lastOwnerMap.get(key, Team.derelict)

            if (currentOwner != previousOwner) {
                lastOwnerMap.put(key, currentOwner)

                val msg: String = String.format(
                    "[#F1F1F1DD]%s captured [#%s]Core#%d",
                    currentOwner.coloredName(),
                    previousOwner.color,
                    coreId
                )


                Call.setHudText(msg)

                changedCount++
            }
        }

        if (changedCount == 0) {

            Call.setHudText(defaultStatus)

        }
    }
    fun colorsAreEqual(c1: Color, c2: Color, epsilon: Float): Boolean {
        return abs(c1.r - c2.r) < epsilon &&
                abs(c1.g - c2.g) < epsilon &&
                abs(c1.b - c2.b) < epsilon &&
                abs(c1.a - c2.a) < epsilon
    }

    private fun checkHexGameEnd() {
        val elapsedMinutes = (Vars.state.tick / 60f / 60f).toInt()
        if (elapsedMinutes < hexGameDurationMinutes) return
        val coreCounts = ObjectMap<Team, Int>()
        Groups.build.each { b ->
            if (b is CoreBuild) {
                val team = b.team
                if (team !== Team.derelict && team.data().hasCore() && team.data().players.size > 0) {
                    val currentCount = coreCounts.get(team, 0) ?: 0
                    coreCounts.put(team, currentCount + 1)
                }
            }
        }

        if (coreCounts.isEmpty) {
            Call.announce("[#F1F1F1DD]No active players found. Derelict wins by default.")
            Events.fire(GameOverEvent(Team.derelict))
            hexMode = false
            return
        }

        if (coreCounts.size == 1) {
            val winner = coreCounts.keys().toSeq().first()
            Call.announce("[#F1F1F1DD]Winner: " + winner.coloredName() + ".")
            Events.fire(GameOverEvent(winner))
            hexMode = false
            return
        }

        val maxCores = coreCounts.keys().maxOfOrNull { coreCounts.get(it) ?: 0 } ?: 0
        val leaders = Seq<Team>()
        for (t in coreCounts.keys()) {
            if (coreCounts.get(t, 0) == maxCores) {
                leaders.add(t)
            }
        }

        if (leaders.size == 1) {
            val winner = leaders.first()
            Call.announce("[#F1F1F1DD]Winner: " + winner.coloredName() + ".")
            Events.fire(GameOverEvent(winner))
            hexMode = false
        } else {
            hexGameDurationMinutes += 10
            Call.announce("[#F1F1F1DD]Tie detected. Game extended by 10 minutes.")
        }
    }

    private fun getDisplayName(player: Player): String {
        val acc = getAccountByUuid(player.uuid())
        val title = if (acc != null) {
            if (acc.isLevelTitle) getLevelString(player.uuid())
            else if (acc.title.isNotBlank()) "<${acc.title}>" else ""
        } else ""
        return if (title.isNotEmpty()) "[lightgray]<${title}>[] ${player.name}" else player.name
    }

    private fun collectTeamCoresData() {
        updatedBuilder.setLength(0)
        val teamsWithPlayers = Seq<Team?>()
        Groups.player.each { p: Player? ->
            if (p != null && p.con != null) {
                val t = p.team()
                if (!teamsWithPlayers.contains(t)) {
                    teamsWithPlayers.add(t)
                }
            }
        }

        val teamCoreCounts = ObjectMap<Team, Int>()
        Groups.build.each { b ->
            if (b is CoreBuild) {
                val team = b.team()
                if (team !== Team.derelict && team.data().hasCore()) {
                    val currentCount = teamCoreCounts.get(team, 0) ?: 0
                    teamCoreCounts.put(team, currentCount + 1)
                }
            }
        }

        val nonNullTeams = Seq<Team>()
        teamsWithPlayers.each { t -> if (t != null) nonNullTeams.add(t) }

        val validTeams: Seq<Team> = nonNullTeams.copy()
            .select { t ->
                teamCoreCounts.get(t, 0) > 0 && t.data().hasCore()
            }

        if (validTeams.isEmpty) return

        validTeams.sort(Comparator { a: Team, b: Team ->
            val countB = teamCoreCounts.get(b, 0) ?: 0
            val countA = teamCoreCounts.get(a, 0) ?: 0
            countB.compareTo(countA)
        })

        for (i in 0..<validTeams.size) {
            val t = validTeams.get(i)
            val cores: Int = teamCoreCounts.get(t) ?: 0

            updatedBuilder.append("[#D0D0D8DD]")
                .append(i + 1)
                .append(". [#F1F1F1DD]")
                .append(t.coloredName())
                .append("[#D0D0D8DD]: []")
                .append("[#F1F1F1DD]")
                .append(cores)
                .append("[]")

            if (i < validTeams.size - 1) {
                updatedBuilder.append("\n")
            }
        }
    }

    private fun showLeaderboard() {
        Timer.schedule(Runnable {
            if (hexMode) {
                collectTeamCoresData()
                if (updatedBuilder.isEmpty()) return@Runnable
                Groups.player.each(Cons { player: Player? ->
                    if (player == null || player.con == null) return@Cons
                    if (!lbEnabled.contains(player.uuid()) || lbEnabled[player.uuid()] == false) return@Cons
                    Call.infoPopup(player.con, updatedBuilder.toString(), 10f, 8, 0, 2, 50, 0)
                })
            }
        }, 0f, 10f)
    }

    private fun isYes(message: String?): Boolean {
        return "y" == message
    }

    companion object {
        private data class TokenInfo(val expiry: Long, val uuid: String)

        private val validTokens = ObjectMap<String, TokenInfo>()
        private const val EXPIRE_MS = 5 * 60 * 1000L

        @Synchronized
        fun generateToken(uuid: String): String {
            val token = (1..16).joinToString("") { "0123456789abcdef".random().toString() }
            validTokens.put(token, TokenInfo(System.currentTimeMillis() + EXPIRE_MS, uuid))
            return token
        }

        @Synchronized
        fun verifyToken(token: String?): String? {
            if (token == null) return null
            val it = validTokens.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (System.currentTimeMillis() > e.value.expiry) it.remove()
            }
            return validTokens[token]?.uuid
        }
        private val json = Json().apply { ignoreUnknownFields = true }
        private val playerLang = HashMap<String?, String?>()
        private val reader = JsonReader()
        private val votes = Seq<String>()
        private const val VOTEDURATION = 45f
        private const val RATIO = 0.65
        private val teamVotes = mutableMapOf<Team, MutableSet<String>>()
        private val teamVoteTasks = mutableMapOf<Team, Timer.Task>()
        private const val TEAM_VOTE_DURATION = 30f
        private const val TEAM_VOTE_THRESHOLD = 0.7f
        private var voteStartTime = 0
        private var hexMode = false
        private val lbEnabled = HashMap<String?, Boolean?>()
        private var hexGameDurationMinutes = 20
        class ConfigFile() {
            var web: WebInfo = WebInfo()
            var servers: MutableList<ServerInfo> = mutableListOf()
        }

        class WebInfo() {
            var url: String = "127.0.0.1"
            var port: Int   = 52011
        }

        class ServerInfo() {
            var title: String = "Unnamed"
            var ip: String    = "127.0.0.1"
            var port: Int     = 6567
        }

        private var WEBURL = "127.0.0.1"   // 占位，真正值来自 json
        private var WEBPORT = 80
        private var serverList: List<ServerInfo> = emptyList()
        private const val ALL_PLAYERS_ID = "ALL_PLAYERS"
        private val pendingLogin = ObjectMap<String, Int>()
        private val revertTargets = mutableMapOf<String, String>()
        private val revertMenuTargets = mutableMapOf<String, List<String>>()
        private val helpPageTracker = mutableMapOf<String, Int>()
        private val rtvConfirmMenuId:Int = Menus.registerMenu { player, choice ->
            if (player == null) return@registerMenu
            val map = rtvPendingMap.remove(player.uuid()) ?: return@registerMenu

            when (choice) {
                0 -> {
                    Call.hideFollowUpMenu(player.con(), rtvConfirmMenuId)
                    startRtvVote(player, map)
                } // confirm
                else -> Call.hideFollowUpMenu(player.con, rtvConfirmMenuId) // cancel
            }
        }

        private val editableBooleanRules = listOf(
            "fire", "schematicsAllowed", "allowEditWorldProcessors", "coreCapture", "reactorExplosions", "possessionAllowed", "unitAmmo", "fog"
        )
        private val editableFloatRules = listOf(
            "blockHealthMultiplier", "blockDamageMultiplier",
            "buildCostMultiplier", "deconstructRefundMultiplier",
            "unitBuildSpeedMultiplier", "unitDamageMultiplier",
            "unitCostMultiplier", "solarMultiplier"
        )

        private val rulesMenuId: Int = Menus.registerMenu { p, choice ->
            val player = p ?: return@registerMenu
            if (!player.admin()) return@registerMenu
            val allRules = editableBooleanRules + editableFloatRules
            val blocksIndex = allRules.size
            val unitsIndex = allRules.size + 1
            val closeIndex = allRules.size + 2

            when (choice) {
                closeIndex -> Call.hideFollowUpMenu(player.con, rulesMenuId)
                blocksIndex -> showBlocksMenu(player)
                unitsIndex -> showUnitsMenu(player)
                in 0 until allRules.size -> {
                    val rule = allRules[choice]
                    if (editableBooleanRules.contains(rule)) {
                        val cur = getRuleValue(rule) as? Boolean ?: return@registerMenu
                        Vars.state.rules.setRule(rule, !cur)
                        Call.setRules(Vars.state.rules)
                        Call.announce(player.con, "[#D0D0D8DD]${player.plainName()} toggled $rule to ${!cur}")
                        showRulesMenu(player)
                    } else if (editableFloatRules.contains(rule)) {
                        promptRuleValueInput(player, rule)
                    }
                }
            }
        }

        private fun showRulesMenu(player: Player) {
            val allRules = editableBooleanRules + editableFloatRules
            val rows = allRules.chunked(3).map { row ->
                row.map { rule ->
                    val value = getRuleValue(rule)
                    "[#F1F1F1DD]$rule: [#D0D0D0EE]$value"
                }.toTypedArray()
            }.toMutableList()

            val blocksLabel = "[#F1F1F1DD]\uE871 Blocks"
            val unitsLabel  = "[#F1F1F1DD]\uE82A Units"
            val closeLabel  = "[#F1F1F1FF]\uE815"
            rows += arrayOf(blocksLabel)
            rows += arrayOf(unitsLabel)
            rows += arrayOf(closeLabel)

            val buttons = rows.toTypedArray()
            Call.followUpMenu(player.con, rulesMenuId, "Edit Game Rules", "", buttons)
        }


        private val blockMenuId: Int = Menus.registerMenu { p, choice ->
            val player = p ?: return@registerMenu
            if (!player.admin()) return@registerMenu
            val bannedBlocks = Vars.state.rules.bannedBlocks
            val blocks = Vars.content.blocks().toArray().filter { it.canBeBuilt() && it != Blocks.air }

            val closeIndex = blocks.size
            when (choice) {
                closeIndex -> Call.hideFollowUpMenu(player.con, blockMenuId)
                in 0 until blocks.size -> {
                    val block = blocks[choice]
                    if (bannedBlocks.contains(block)) {
                        bannedBlocks.remove(block)
                    } else {
                        bannedBlocks.add(block)
                    }
                    Call.setRules(Vars.state.rules)
                    showBlocksMenu(player)
                }
            }
        }

        private val unitMenuId: Int = Menus.registerMenu { p, choice ->
            val player = p ?: return@registerMenu
            if (!player.admin()) return@registerMenu
            val units = Vars.content.units().toArray().filter { !it.internal }
            val closeIndex = units.size
            when (choice) {
                closeIndex -> Call.hideFollowUpMenu(player.con, unitMenuId)
                in 0 until units.size -> {
                    val unit = units[choice]
                    if (Vars.state.rules.bannedUnits.contains(unit)) {
                        Vars.state.rules.bannedUnits.remove(unit)
                    } else {
                        Vars.state.rules.bannedUnits.add(unit)
                    }
                    Call.setRules(Vars.state.rules)
                    showUnitsMenu(player)
                }
            }
        }

        private fun showBlocksMenu(player: Player) {
            val bannedBlocks = Vars.state.rules.bannedBlocks
            val blocks = Vars.content.blocks().toArray().filter { it.canBeBuilt() && it != Blocks.air }

            val rows = blocks.chunked(4).map { row ->
                row.map { b ->
                    val banned = bannedBlocks.contains(b)
                    val color = if (banned) "[#D0D0D0EE]" else "[#F1F1F1DD]"
                    "$color${b.localizedName}"
                }.toTypedArray()
            }.toMutableList()

            rows += arrayOf("[#F1F1F1FF]\uE815")

            Call.followUpMenu(player.con, blockMenuId, "Toggle Buildings", "", rows.toTypedArray())
        }

        private fun showUnitsMenu(player: Player) {
            val units = Vars.content.units().toArray().filter { !it.internal }

            val rows = units.chunked(4).map { row ->
                row.map { u ->
                    val banned = Vars.state.rules.bannedUnits.contains(u)
                    val color = if (banned) "[#D0D0D0EE]" else "[#F1F1F1DD]"
                    "$color${u.localizedName}"
                }.toTypedArray()
            }.toMutableList()

            rows += arrayOf("[#F1F1F1FF]\uE815")

            Call.followUpMenu(player.con, unitMenuId, "Toggle Units", "", rows.toTypedArray())
        }


        private fun promptRuleValueInput(player: Player, rule: String) {
            val inputId = Menus.registerTextInput { p, input ->
                if (input == null) return@registerTextInput
                val newValue = input.toFloatOrNull()
                if (newValue == null || newValue < 0f) {
                    Call.announce(p.con, "[#D0D0D8DD]Invalid input. Must be a number >= 0.")
                    return@registerTextInput
                }
                Vars.state.rules.apply {
                    setRule(rule, newValue)
                }
                Call.setRules(Vars.state.rules)
                Call.announce(p.con, "[#D0D0D8DD]${p.plainName()} set $rule to $newValue")
                showRulesMenu(p)
            }

            Call.textInput(
                player.con,
                inputId,
                "Enter new value for $rule",
                "Value (>= 0)",
                10,
                "",
                false
            )
        }
        private fun getRuleValue(ruleName: String): Any? {
            return try {
                Vars.state.rules.javaClass.getDeclaredField(ruleName).apply {
                    isAccessible = true
                }.get(Vars.state.rules)
            } catch (_: Exception) {
                null
            }
        }
        private fun Rules.setRule(name: String, value: Any) {
            try {
                val field = this.javaClass.getDeclaredField(name)
                field.isAccessible = true
                field.set(this, value)
            } catch (_: Exception) {
            }
        }
        private val rtvPendingMap = mutableMapOf<String, Map>()

        private val revertMenuId: Int = Menus.registerMenu { player, choice ->
            if (player == null || choice < 0) return@registerMenu

            val list = revertMenuTargets[player.uuid()] ?: return@registerMenu
            if (choice >= list.size) return@registerMenu

            val target = list[choice]
            if (target == "close") {
                Call.hideFollowUpMenu(player.con, revertMenuId)
                return@registerMenu
            }
            revertTargets[player.uuid()] = target

            Call.textInput(
                player.con,
                revertTextInputId,
                "[#E2E2E2DD]Enter how many seconds to revert:",
                "Revert seconds",
                10,
                "",
                false
            )
        }

        private val revertTextInputId = Menus.registerTextInput { player, input ->
            if (player == null || input == null) return@registerTextInput
            if (!player.admin) {
                Call.announce(player.con, "[#D0D0D0DD]You must to be an admin!")
                return@registerTextInput
            }
            val seconds = input.toIntOrNull()
            if (seconds == null || seconds <= 0) {
                Call.announce(player.con, "[#D0D0D8DD]Invalid time: must be a number > 0.")
                return@registerTextInput
            }

            val uuid = revertTargets.remove(player.uuid()) ?: return@registerTextInput
            runRevert( uuid, seconds)
        }

        private val msgMenuId:Int = Menus.registerMenu { sender, choice ->
            if (sender == null || choice < 0) return@registerMenu

            if (choice == 0) {
                Call.hideFollowUpMenu(sender.con, msgMenuId)
                return@registerMenu
            }

            val others = Groups.player.filter { it != sender && it.con != null }
            val index = choice - 1

            if (index !in others.indices) return@registerMenu

            val selected = others[index]
            msgTargets[sender.uuid()] = selected

            Call.textInput(
                sender.con,
                msgTextInputId,
                "[#E2E2E2DD]Enter your message:",
                "Message to ${selected.name()}",
                200,
                "",
                false
            )
        }

        private val msgTextInputId = Menus.registerTextInput { sender, input ->
            if (sender == null || input == null || input.isBlank()) return@registerTextInput

            val recipient = msgTargets[sender.uuid()] ?: return@registerTextInput
            msgTargets.remove(sender.uuid())
            recipient.sendMessage("[#E2E2E2DD]\uE836 ${sender.name()}:[][#E2E2E2DD] $input")
            sender.sendMessage("[#E2E2E2DD]\uE835 ${recipient.name()}:[][#E2E2E2DD] $input")
        }

        private val msgTargets = mutableMapOf<String, Player>()
        private val voteMenuId:Int = Menus.registerMenu { player, choice ->
            val uuid = player.uuid()
            if (!voteInProgress || votes.contains(uuid) || player == voteInitiator){

                when (choice) {
                    0 -> {
                        Call.hideFollowUpMenu(player.con(), voteMenuId)
                        return@registerMenu
                    }
                    1 -> {
                        Call.hideFollowUpMenu(player.con(), voteMenuId)
                        return@registerMenu
                    }
                }
            } else {
                when (choice) {
                    0 -> { // Confirm
                        Call.hideFollowUpMenu(player.con(), voteMenuId)
                        votes.add(uuid)
                        val currentVotes = votes.size
                        val playerCount = Groups.player.size()
                        val requiredVotes = when {
                            playerCount <= 2 -> playerCount
                            playerCount == 3 -> 2
                            else -> ceil(playerCount * RATIO).toInt()
                        }

                        if (kickVote) {
                            val tp = targetPlayer ?: return@registerMenu
                            if (currentVotes >= requiredVotes) {
                                Call.announce("[#D0D0D8DD]Vote passed. Kicking ${tp.name()}[]")
                                restorePlayerEditsWithinSeconds(tp.uuid(), 180)
                                tp.kick("[#D0D0D8DD]You were kicked by vote.[]", 5 * 60 * 1000)
                                endVote()
                            }
                        } else {
                            val map = targetMap ?: return@registerMenu
                            if (currentVotes >= requiredVotes) {
                                Call.announce("[#D0D0D8DD]Vote passed. Changing map to ${map.name()}[]")
                                playerTeams.clear()
                                clearStatus()
                                loadMap(map)
                                resetGameState()
                                endVote()
                            }
                        }
                    }

                    1 -> {
                        Call.hideFollowUpMenu(player.con(), voteMenuId)
                        return@registerMenu
                    }
                }
            }
        }

        fun startRtvVote(player: Player, selected: Map) {
            if (Groups.player.size() <= 1 || player.admin) {
                playerTeams.clear()
                clearStatus()
                loadMap(selected)
                resetGameState()
            } else {
                beginVote(player, false, selected, null, null)
            }
        }

        private fun showHelpMenu(player: Player, page: Int) {
            val commands = Vars.netServer.clientCommands.commandList.sortedBy { it.text }
            val commandsPerPage = 10
            val totalPages = (commands.size + commandsPerPage - 1) / commandsPerPage
            val currentPage = when {
                page < 1 -> totalPages
                page > totalPages -> 1
                else -> page
            }

            helpPageTracker[player.uuid()] = currentPage

            val start = (currentPage - 1) * commandsPerPage
            val end = minOf(start + commandsPerPage, commands.size)

            val buttons = mutableListOf<Array<String?>>()
            for (i in start until end) {
                val cmd = commands[i]
                val params = if (cmd.paramText.isNotEmpty()) " ${cmd.paramText}" else ""

                val line = buildString {
                    append("[#ECECECEE]/${cmd.text}")
                    append("[#ACACACDD]$params")
                    append(" [#ACACACDD]\n${cmd.description}")
                }

                buttons.add(arrayOf(line))
            }

            buttons.add(arrayOf(if (currentPage > 1) "[#F1F1F1FF]" else "",
                "[#F1F1F1FF]",
                if (currentPage < totalPages) "[#F1F1F1FF]" else ""))

            Call.followUpMenu(player.con, helpMenuId, "[#D0D0D8DD]Help - Commands $currentPage/$totalPages", "", buttons.toTypedArray())
        }
        private val loginNameInput = Menus.registerTextInput { p, name ->
            if (name.isNullOrBlank()) {
                Call.announce(p.con, "[#D0D0D8DD]Username cannot be empty.")
                return@registerTextInput
            }
            val id = idByAcc[name] ?: run {
                Call.announce(p.con, "[#D0D0D8DD]User '$name' not found.")
                return@registerTextInput
            }
            val acc = accounts[id]
            if (acc?.isStrict == true) {
                Call.announce(p.con, "[#D0D0D0DD]This account is in strict mode \n and cannot be logged in.")
                return@registerTextInput
            }
            pendingLogin.put(p.uuid(), id)
            Call.textInput(p.con, loginPassInput,
                "Password", "Enter password", 20, "", true)
        }

        private val loginPassInput = Menus.registerTextInput { p, pass ->
            val id = pendingLogin.remove(p.uuid()) ?: return@registerTextInput
            val acc = accounts[id] ?: return@registerTextInput
            if (pass != acc.pass) {
                Call.announce(p.con, "[#D0D0D8DD]Wrong password.")
                return@registerTextInput
            }
            if (!acc.uuids.contains(p.uuid())) acc.uuids.add(p.uuid())
            idByUuid.put(p.uuid(), id)
            applyAccountToPlayer(p, acc)
            updateAccount(acc)
            Timer.schedule({
                if (p.con != null) show(p)
            }, 0.8f)
            Call.announce(p.con, "[#D0D0D8DD]Welcome back, ${acc.nick}!")
        }

        private val regNameInput = Menus.registerTextInput { p, name ->
            if (name.isNullOrBlank() || name.all { it.isDigit() }) {
                Call.announce(p.con, "[#D0D0D8DD]Username cannot be empty or all digits.")
                return@registerTextInput
            }
            if (idByAcc.containsKey(name)) {
                Call.announce(p.con, "[#D0D0D8DD]Username already taken.")
                return@registerTextInput
            }

            val id = nextId++
            val acc = Account(
                id = id,
                account = name,
                pass = "",
                uuids = mutableListOf(p.uuid()),
                nick = p.name,
                playTime = 0,
                score = 0,
                wins = 0,
                banUntil = 0,
                bio = "",
                title = "",
                isLevelTitle = true,
                isStrict = true,
                isAdmin = false
            )
            accounts.put(id, acc)
            idByAcc.put(name, id)
            idByUuid.put(p.uuid(), id)
            applyAccountToPlayer(p, acc)
            updateAccount(acc)
            Timer.schedule({
                if (p.con != null) show(p)
            }, 0.8f)
            Call.announce(p.con, "[#D0D0D8DD]Registered successfully!\nWelcome, ${acc.account}.")

        }


        private fun applyAccountToPlayer(p: Player, acc: Account) {
            p.name = acc.nick
            p.admin = acc.isAdmin
        }

        private val settingsMenuId: Int = Menus.registerMenu { p, choice ->
            val player = p ?: return@registerMenu
            val acc = getAccountByUuid(player.uuid()) ?: return@registerMenu
            val closeIdx = 0
            val usernameIdx = 1
            val nicknameIdx = 2
            val passwordIdx = 3
            val titleMenuIdx = 4
            val bioIdx = 5
            val lbIdx = 6
            val langIdx = 7
            val adminIdx = 8
            val strictIdx = 9

            when (choice) {
                closeIdx -> Call.hideFollowUpMenu(player.con, settingsMenuId)
                titleMenuIdx -> showTitleMenu(player)
                usernameIdx -> promptUsernameChange(player, acc)
                nicknameIdx -> promptNicknameChange(player, acc)
                passwordIdx -> promptPasswordChange(player, acc)
                bioIdx -> promptBioChange(player, acc)
                lbIdx -> {
                    toggleLeaderboard(player)
                    showSettingsMenu(player)
                }
                langIdx -> {
                    showLanguageMenu(player)
                }
                adminIdx -> {
                    toggleAdmin(player)
                    showSettingsMenu(player)
                }
                strictIdx -> {
                    if (acc.isStrict && acc.pass.isBlank()) {
                        Call.announce(player.con, "[#D0D0D8DD]Set a password before disabling strict mode.")
                        showSettingsMenu(player)
                        return@registerMenu
                    }
                    acc.isStrict = !acc.isStrict
                    updateAccount(acc)
                    Call.announce(player.con, "[#D0D0D8DD]Strict mode set to ${acc.isStrict}.")
                    showSettingsMenu(player)
                }

            }
        }

        fun showSettingsMenu(player: Player) {
            val acc = getAccountByUuid(player.uuid()) ?: return
            val lang = playerLang[player.uuid()] ?: "en"
            val lbOn = lbEnabled[player.uuid()] == true
            val rows = mutableListOf<Array<String>>()
            rows += arrayOf("[#F1F1F1FF]\uE815")
            rows += arrayOf("[#F1F1F1DD]Username")
            rows += arrayOf("[#F1F1F1DD]Nickname")
            rows += arrayOf("[#F1F1F1DD]Password")
            rows += arrayOf("[#F1F1F1DD]Title")
            rows += arrayOf("[#F1F1F1DD]Bio")
            rows += arrayOf("[#F1F1F1DD]Leaderboard: [#D0D0D0EE]${if (lbOn) "on" else "off"}")
            rows += arrayOf("[#F1F1F1DD]Language: [#D0D0D0EE]$lang")
            rows += arrayOf("[#F1F1F1DD]Admin[#D0D0D0EE]: ${player.admin}")
            rows += arrayOf("[#F1F1F1DD]Strict: [#D0D0D0EE]${acc.isStrict}")
            Call.followUpMenu(player.con, settingsMenuId, "Player Settings", "", rows.toTypedArray())
        }

        private val titleMenuId: Int = Menus.registerMenu { p, choice ->
            val player = p ?: return@registerMenu
            val acc = getAccountByUuid(player.uuid()) ?: return@registerMenu
            val closeIdx = 0
            val levelTitleIdx = 1
            val textTitleIdx = if (acc.title.isNotBlank()) 2 else -1

            when (choice) {
                closeIdx -> return@registerMenu
                levelTitleIdx -> {
                    acc.isLevelTitle = true
                    updateAccount(acc)
                    Call.announce(player.con, "[#D0D0D8DD]Level title selected. \n Please rejoin to apply.")
                }
                textTitleIdx -> if (textTitleIdx != -1) {
                    if (acc.title.isEmpty()) return@registerMenu
                    acc.isLevelTitle = false
                    updateAccount(acc)
                    Call.announce(player.con, "[#D0D0D8DD]Text title selected. \n Please rejoin to apply.")
                }
            }
        }

        fun showTitleMenu(player: Player) {
            val acc = getAccountByUuid(player.uuid()) ?: return
            val rows = mutableListOf<Array<String>>()
            rows += arrayOf("\uE815")
            rows += arrayOf(getLevelString(player.uuid()))
            if (acc.title.isNotBlank()) rows += arrayOf("[maroon]<${acc.title}>")
            Call.menu(player.con, titleMenuId, "Select Title", "[#D0D0D0DD]Choose your display title", rows.toTypedArray())
        }

        private fun getLevelString(uuid: String): String {
            val acc = getAccountByUuid(uuid) ?: return "[#D0D0D0DD]lv0[]"
            val level = acc.wins / 20
            val color = when {
                level >= 10 -> "[#A8C5D4DD]"
                level >= 8  -> "[#B8D0E0DD]"
                level >= 6  -> "[#C0D7E0DD]"
                level >= 4  -> "[#C6E0E0DD]"
                level >= 2  -> "[#D5E8F0DD]"
                else        -> "[#E0E0E0DD]"
            }
            return "${color}lv$level[]"
        }

        private fun promptUsernameChange(p: Player, acc: Account) {
            if (acc.isStrict) {
                Call.announce(p.con, "[#D0D0D8DD]Cannot change username in strict mode.")
                return
            }
            Call.textInput(p.con, Menus.registerTextInput { _, text ->
                if (text == null || text.isEmpty()) return@registerTextInput
                val newName = text.trim()
                if (newName.isEmpty() || newName.all { it.isDigit() }) {
                    Call.announce(p.con, "[#D0D0D8DD]Username invalid.")
                    return@registerTextInput
                }
                val exists = accounts.values().any {
                    it.account.equals(newName, ignoreCase = true) && it.id != acc.id
                }
                if (exists) {
                    Call.announce(p.con, "[#D0D0D8DD]Username already exists.")
                    return@registerTextInput
                }
                acc.account = newName
                updateAccount(acc)
                Call.announce(p.con, "[#D0D0D8DD]Username updated.")
            }, "Change Username", "", 20, acc.account, false)
        }

        private fun promptNicknameChange(p: Player, acc: Account) {
            Call.textInput(p.con, Menus.registerTextInput { _, text ->
                if (text == null || text.isEmpty()) return@registerTextInput
                acc.nick = text
                updateAccount(acc)
                Call.announce(p.con, "[#D0D0D8DD]Nickname updated. You need rejoin.")
            }, "Change Nickname", "", 20, acc.nick, false)
        }
        private fun promptPasswordChange(p: Player, acc: Account) {
            Call.textInput(p.con, Menus.registerTextInput { _, text ->
                if (text == null || text.isEmpty()) return@registerTextInput
                if (text.length < 4) {
                    Call.announce(p.con, "[#D0D0D8DD]Password too short.")
                    return@registerTextInput
                }
                acc.pass = text
                updateAccount(acc)
                Call.announce(p.con, "[#D0D0D8DD]Password updated.")
            }, "Change Password", "(>=4 characters)", 20, "", true)
        }
        private fun promptBioChange(p: Player, acc: Account) {
            Call.textInput(p.con, Menus.registerTextInput { _, text ->
                if (text == null || text.isEmpty()) return@registerTextInput
                acc.bio = text
                updateAccount(acc)
                Call.announce(p.con, "[#D0D0D8DD]Bio updated.")
            }, "Change Bio", "", 50, acc.bio, false)
        }

        private fun toggleLeaderboard(p: Player) {
            val uuid = p.uuid()
            if (lbEnabled.contains(uuid) && lbEnabled[uuid] == true) {
                lbEnabled[uuid] = false
                Call.announce(p.con, "[#D0D0D8DD]Leaderboard display disabled.")
            } else {
                lbEnabled[uuid] = true
                Call.announce(p.con, "[#D0D0D8DD]Leaderboard display enabled.")
            }
        }

        private fun toggleAdmin(p: Player) {
            val acc = getAccountByUuid(p.uuid()) ?: return
            val coreAdmin = acc.uuids.any { uuid ->
                Vars.netServer.admins.getInfo(uuid)?.admin == true
            }
            if (!coreAdmin) {
                Call.announce(p.con, "[#CED7E0FF]Permission denied.")
                return
            }
            p.admin = !p.admin()
            acc.isAdmin = p.admin()
            updateAccount(acc)
        }

        fun showIntroMenu(player: Player) {
            val acc = getAccountByUuid(player.uuid())
            if (acc != null) {
                return
            }

            val rows = arrayOf(
                arrayOf("[#F1F1F1DD]\uE83D Login"),
                arrayOf("[#F1F1F1DD]\uE813 Register")
            )
            Call.followUpMenu(player.con, introMenuId, "Welcome",
                "\n[#D0D0D8DD]Please login or register.[]\n", rows)
        }

        private val introMenuId:Int = Menus.registerMenu { p, ch ->
            when (ch) {
                0 -> {
                    Call.hideFollowUpMenu(p.con, introMenuId)
                    Call.textInput(p.con, loginNameInput,
                        "Login", "Username", 20, "", false)
                }
                1 -> {
                    Call.hideFollowUpMenu(p.con, introMenuId)
                    Call.textInput(p.con, regNameInput,
                        "Register", "Pick a username", 20, "", false)
                }
            }
        }
        private fun defaultCfg(): ConfigFile = ConfigFile().apply {
            web.url  = "46.23.90.167"
            web.port = 52011
            servers.add(ServerInfo().apply {
                title = "PvP"
                ip    = "46.23.90.167"
                port  = 6567
            })
        }

        fun loadServerConfig() {
            val cfgFile = Vars.saveDirectory.child("server.json")

            if (!cfgFile.exists()) {
                val d = defaultCfg()
                cfgFile.writeString(json.prettyPrint(d))
                applyConfig(d)
                Log.info("Created default server.json")
                return
            }

            try {
                val cfg: ConfigFile = json.fromJson(ConfigFile::class.java, cfgFile.readString())
                applyConfig(cfg)
                Log.info("Loaded server.json (${cfg.servers.size} server(s))")
            } catch (e: Exception) {
                Log.err("Failed to parse server.json: ${e.message}")
                Core.app.exit()
            }
        }

        private fun applyConfig(cfg: ConfigFile) {
            WEBURL     = cfg.web.url
            WEBPORT    = cfg.web.port
            serverList = cfg.servers
        }


        private val serverListMenuId: Int = Menus.registerMenu { player, choice ->
            if (player == null || choice < 0) return@registerMenu

            if (choice == 0) {
                Call.hideFollowUpMenu(player.con, serverListMenuId)
                return@registerMenu
            }

            val selectedServer = serverList.getOrNull(choice - 1) ?: return@registerMenu
            Call.connect(player.con, selectedServer.ip, selectedServer.port)
            Call.announce(player.con, "[#D0D0D8DD]Connecting to server: ${selectedServer.title} at ${selectedServer.ip}:${selectedServer.port}")
        }

        fun showServerListMenu(player: Player) {
            val buttons = mutableListOf(arrayOf("[#F1F1F1FF]"))
            serverList.forEach { server ->
                buttons.add(arrayOf("[#F1F1F1FF]${server.title}\n[#ACACACEE]${server.ip}:${server.port}"))
            }
            Call.followUpMenu(player.con, serverListMenuId, "[#E2E2E2DD]Select a server", "", buttons.toTypedArray())
        }

        private val helpMenuId:Int = Menus.registerMenu { player, choice ->
            if (player == null) return@registerMenu

            val commands = Vars.netServer.clientCommands.commandList.sortedBy { it.text }
            val commandsPerPage = 10
            val totalPages = (commands.size + commandsPerPage - 1) / commandsPerPage
            val currentPage = helpPageTracker[player.uuid()] ?: 1

            val startIndex = (currentPage - 1) * commandsPerPage
            val endIndex = minOf(startIndex + commandsPerPage, commands.size)
            val commandCountThisPage = endIndex - startIndex

            val footerRowStartIndex = commandCountThisPage
            val prevButtonIndex = footerRowStartIndex
            val closeButtonIndex = footerRowStartIndex + 1
            val nextButtonIndex = footerRowStartIndex + 2

            when (choice) {
                prevButtonIndex -> {
                    val prevPage = if (currentPage == 1) totalPages else currentPage - 1
                    showHelpMenu(player, prevPage)
                    return@registerMenu
                }
                closeButtonIndex -> {
                    Call.hideFollowUpMenu(player.con, helpMenuId)
                    return@registerMenu
                }
                nextButtonIndex -> {
                    val nextPage = if (currentPage == totalPages) 1 else currentPage + 1
                    showHelpMenu(player, nextPage)
                    return@registerMenu
                }
            }

            if (choice in 0 until commandCountThisPage) {
                val cmdIndex = startIndex + choice
                if (cmdIndex in commands.indices) {
                    val cmd = commands[cmdIndex]
                    NetClient.sendChatMessage(player, "/${cmd.text}")
                }
            }
        }


        private val lastOwnerMap = ObjectMap<String, Team>()
        private val coreNumberMap = ObjectMap<String, Int>()
        private val playerTeams = HashMap<String, Team>()
        private val mapMenuId = Menus.registerMenu { player, choice ->
            if (player == null || choice < 0) return@registerMenu
            handleMapMenuChoice(player, choice)
        }
        private val voteKickTextInputId = Menus.registerTextInput { player, input ->
            if (input == null || player == null) {
                return@registerTextInput
            }

            onVoteKickReasonEntered(player, input)
        }


        private val voteKickMenuId = Menus.registerMenu { player, choice ->
            if (player == null || choice < 0) return@registerMenu
            onVoteKickMenuSelected(player, choice)
        }

        private val votekickTargets = mutableMapOf<String, Player>()
        private val snapshotPages = mutableMapOf<String, List<Fi>>()
        private val snapshotPageTracker = mutableMapOf<String, Int>()
        private val menuCache = mutableMapOf<String, List<Player>>()
        private val playersMenuId: Int = Menus.registerMenu { v, c ->
            if (v == null || c < 0) return@registerMenu
            if (c == 0) {
                Call.hideFollowUpMenu(v.con, playersMenuId)
                menuCache.remove(v.uuid())
                return@registerMenu
            }
            val l = menuCache[v.uuid()] ?: return@registerMenu
            val t = l.getOrNull(c - 1) ?: return@registerMenu
            playerInfoTarget[v.uuid()] = t
            openInfo(v, t)
        }

        private val infoMenuId: Int = Menus.registerMenu { viewer, choice ->
            val target = playerInfoTarget[viewer.uuid()] ?: return@registerMenu

            if (!viewer.admin) {
                Call.hideFollowUpMenu(viewer.con, infoMenuId)
                return@registerMenu
            }
            when (choice) {
                0 -> {
                    pendingTitle[viewer.uuid()] = target
                    Call.textInput(viewer.con, titleInputId, "Title", "Text", 16, "", false)
                }
                1 -> {
                    if(target.admin() || Vars.netServer.admins.adminPlayer(target.uuid(), target.usid()) || target == viewer) return@registerMenu
                    pendingBan[viewer.uuid()] = target
                    Call.textInput(viewer.con, banInputId, "Ban (min)", "Minutes", 5, "60", false)
                }
                else -> Call.hideFollowUpMenu(viewer.con, infoMenuId)
            }
        }

        private val titleInputId = Menus.registerTextInput { v, t ->
            val tgt = pendingTitle.remove(v.uuid()) ?: return@registerTextInput
            if (tgt.admin()) {
                return@registerTextInput
            }
            val acc = getAccountByUuid(tgt.uuid()) ?: return@registerTextInput
            acc.title = t?.take(16) ?: ""
            v.sendMessage("[#D0D6DEFF]Title updated")
        }

        private val banInputId = Menus.registerTextInput { v, t ->
            val tgt = pendingBan.remove(v.uuid()) ?: return@registerTextInput
            if (tgt.admin()) {
                return@registerTextInput
            }
            val acc = getAccountByUuid(tgt.uuid()) ?: return@registerTextInput
            val m = t?.toIntOrNull()?.coerceIn(1, 43200) ?: return@registerTextInput
            acc.banUntil = Time.millis() + m * 60_000L
            tgt.kick("Banned.")
        }

        private val playerInfoTarget = mutableMapOf<String, Player>()
        private val pendingTitle     = mutableMapOf<String, Player>()
        private val pendingBan       = mutableMapOf<String, Player>()

        private fun openInfo(v: Player, tgt: Player) {
            val acc = getAccountByUuid(tgt.uuid()) ?: run {
                Call.announce(v.con, "[#A9B4C3FF]User not logged in")
                return
            }
            val coreAdmin = acc.uuids.any { uuid ->
                Vars.netServer.admins.getInfo(uuid)?.admin == true
            }
            val role = if (coreAdmin) "Administrator" else "Player"
            val level = if (acc.isLevelTitle) getLevelString(tgt.uuid()) else "lv0"
            val lang = playerLang[tgt.uuid()] ?: tgt.locale()
            val txt = buildString {
                appendLine()
                appendLine("[lightgray]${acc.account}")
                appendLine()
                appendLine("[lightgray]${acc.bio.ifBlank { "Unkown" }}")
                appendLine()
                appendLine("[#D0D0D0EE]Nickname:[] [lightgray]${tgt.name()}[]")
                appendLine("[#D0D0D0EE]Role:[] [lightgray]${role}[]")
                appendLine("[#D0D0D0EE]Lang:[] [lightgray]${lang}[]")
                appendLine("[#D0D0D0EE]Score:[] [lightgray]${acc.score}[]")
                appendLine("[#D0D0D0EE]Level:[] [lightgray]$level[]")
                appendLine("[#D0D0D0EE]Playtime:[] [lightgray]${acc.playTime}m[]")
                append("[#D0D0D0EE]Wins: [] [lightgray]${acc.wins}[]")
                appendLine()
            }
            val setTitle = if (v.admin) "[#F1F1F1DD]Set Title" else ""
            val ban = if (v.admin) "[#F1F1F1DD]Ban" else ""
            val first = arrayOf(setTitle, ban).filter { it.isNotBlank() }.toTypedArray()
            val buttons = if (first.isNotEmpty()) {
                arrayOf(first, arrayOf("[#F1F1F1DD]"))
            } else {
                arrayOf(arrayOf("[#CED7E0FF]"))
            }
            Call.followUpMenu(v.con, infoMenuId, "[#B7C5D4FF]Info", txt, buttons)
        }


        private val rankMenuId:Int = Menus.registerMenu { player, choice ->
            val current   = rankPageTracker[player.uuid()] ?: 1
            val pages     = ((accounts.values().count { it.score > 0 } + 29) / 30).coerceAtLeast(1)

            when (choice) {
                0 -> showRankMenu(player, if (current > 1) current - 1 else pages)
                1 -> Call.hideFollowUpMenu(player.con, rankMenuId)
                2 -> showRankMenu(player, if (current < pages) current + 1 else 1)
            }
        }


        private val rankPageTracker = mutableMapOf<String, Int>()

        private val rollbackMenuId = Menus.registerMenu { player, choice ->
            if (player != null) {
                handleRollbackMenuChoice(player, choice)
            }
        }

        private val mapPageTracker = mutableMapOf<String, Int>()
        private val teamMenuId = Menus.registerMenu { player, choice ->
            if (player != null) {
                onMenuChoose(player, choice)
            }
        }
        private val paginatedMaps = mutableMapOf<String, List<Map>>()

        private val languageMenuId = Menus.registerMenu { player, choice ->
            handleLanguageMenuChoice(player, choice)
        }

        private val tempTeamChoices = mutableMapOf<String, MutableList<Team>>()
        private val ipGEOFile = Vars.saveDirectory.child("GeoLite2-Country.mmdb")
        private val playersFile = Vars.saveDirectory.child("players.json")
        private val playerJson  = Json()
        data class Account(
            var id:        Int,
            var account:   String,
            var pass:      String,
            val uuids:     MutableList<String>,
            var nick:      String,
            var playTime:  Int,
            var score:     Int,
            var wins:      Int,
            var banUntil:  Long,
            var bio:       String,
            var title:       String,
            var isLevelTitle:      Boolean,
            var isStrict:   Boolean,
            var isAdmin:   Boolean
        )
        private val accounts   = ObjectMap<Int, Account>()
        private val idByAcc    = ObjectMap<String, Int>()
        private val idByUuid   = ObjectMap<String, Int>()
        private var  nextId    = 1

        private val lastEdits = ObjectMap<String, ObjectMap<Point2, BlockEdit>>()
        private var voteInProgress = false
        private var kickVote = false
        private var voteInitiator: Player? = null
        private var targetMap: Map? = null
        private var targetPlayer: Player? = null
        private var voteTimer: Timer.Task? = null
        private var voteUpdateTask: Timer.Task? = null
        private var geoDB: DatabaseReader? = null

        private fun loadAccounts() {
            if (!playersFile.exists()) return
            val root = playerJson.fromJson(HashMap::class.java, playersFile.readString()) as HashMap<*,*>

            root.forEach { (k, v) ->
                val id = when (k) {
                    is Number -> k.toInt()
                    is String -> k.toIntOrNull() ?: return@forEach
                    else       -> return@forEach
                }
                val js  = v as kotlin.collections.Map<*, *>

                val uuids = (js["uuids"] as? Iterable<*>)
                    ?.mapNotNull { it as? String }
                    ?.toMutableList() ?: mutableListOf()

                val acc = Account(
                    id            = id,
                    account       = js["account"]?.toString() ?: "",
                    pass          = js["pass"]?.toString()    ?: "",
                    uuids         = uuids,
                    nick          = js["nick"]?.toString()    ?: "Player",
                    playTime      = (js["playTime"]  as? Number ?: 0).toInt(),
                    score         = (js["score"]     as? Number ?: 0).toInt(),
                    wins          = (js["wins"]      as? Number ?: 0).toInt(),
                    banUntil      = (js["banUntil"]  as? Number ?: 0).toLong(),
                    bio           = js["bio"]?.toString() ?: "",
                    title         = js["title"]?.toString() ?: "",
                    isLevelTitle  = (js["isLevelTitle"] as? Boolean) ?: true,
                    isStrict       = (js["isStrict"]  as? Boolean) ?: true,
                    isAdmin       = (js["isAdmin"]  as? Boolean) ?: false
                )

                accounts.put(id, acc)
                idByAcc.put(acc.account.lowercase(), id)
                acc.uuids.forEach { idByUuid.put(it, id) }
                nextId = maxOf(nextId, id + 1)
            }
        }
        private fun getAccountByUuid(uuid: String): Account? {
            val it = accounts.values().iterator()
            while (it.hasNext()) {
                val acc = it.next()
                if (acc.uuids.contains(uuid)) return acc
            }
            return null
        }


        private fun saveAccounts() {
            val root = HashMap<Int, Any>()

            accounts.each { id, a ->
                root[id] = mapOf(
                    "account"  to a.account,
                    "pass"     to a.pass,
                    "uuids"    to a.uuids,
                    "nick"     to a.nick,
                    "playTime" to a.playTime,
                    "score"    to a.score,
                    "wins"     to a.wins,
                    "banUntil" to a.banUntil,
                    "bio"      to a.bio,
                    "title"       to a.title,
                    "isLevelTitle" to a.isLevelTitle,
                    "isAdmin"     to a.isAdmin
                )
            }

            playersFile.writeString(playerJson.toJson(root))
        }

        private fun updateAccount(acc: Account) {
            accounts.put(acc.id, acc)
            saveAccounts()
        }

        private fun recordEdit(uuid: String?, tile: Tile) {
            if (tile.build == null || !tile.build.isValid) return
            if (tile.block() === Blocks.air) return
            if (tile.build is ConstructBuild) return
            val pos = Point2(tile.x.toInt(), tile.y.toInt())
            val map = lastEdits.get(uuid)
                ?: ObjectMap<Point2, BlockEdit>().also { lastEdits.put(uuid, it) }

            val block = tile.block()
            val rotation = tile.build.rotation
            map.put(pos, BlockEdit(block, tile.build.team().id, rotation, System.nanoTime()))
        }

        private fun restorePlayerEditsWithinSeconds(uuid: String?, seconds: Int) {
            val edits = lastEdits[uuid] ?: return
            val now = System.nanoTime()
            val cutoff = seconds.toLong() * 1_000_000_000L
            for (entry in edits.entries()) {
                val pos = entry.key ?: continue
                val edit = entry.value ?: continue
                if (now - edit.timeNanos > cutoff) continue
                if (edit.block === Blocks.air) continue

                val tile = Vars.world.tile(pos.x, pos.y) ?: continue
                if (tile.block() === Blocks.air || tile.build == null) {
                    val team = Team.get(edit.teamId)
                    tile.setNet(edit.block, team, edit.rotation)
                    tile.build?.updateTile()
                }
            }

            lastEdits.remove(uuid)
        }

        private fun clearStatus() {
            hexMode = false
            hexGameDurationMinutes = 0
            coreNumberMap.clear()
            lastOwnerMap.clear()
        }

        private fun resetGameState() {
            votes.clear()
            lastEdits.clear()
            voteInProgress = false
            kickVote = false
            voteInitiator = null
            targetMap = null
            targetPlayer = null
            voteTimer?.cancel()
            voteTimer = null
            teamVotes.clear()
            teamVoteTasks.forEach { _, task -> task.cancel() }
            teamVoteTasks.clear()
        }

        fun runRevert(targetUuid: String, seconds: Int) {
            if (targetUuid == ALL_PLAYERS_ID) {
                var count = 0
                lastEdits.keys().forEach { uuid ->
                    restorePlayerEditsWithinSeconds(uuid, seconds)
                    count++
                }
                Call.announce("[#D0D0D8DD]Restored edits for [#ECECECEE]$count[] players in the last [#ECECECEE]$seconds[] seconds.")
            } else {
                restorePlayerEditsWithinSeconds(targetUuid, seconds)
                val name = Groups.player.find { it.uuid() == targetUuid }?.name
                    ?: Vars.netServer.admins.getInfo(targetUuid).lastName
                    ?: "unknown"
                Call.announce("[#D0D0D8DD]Restored edits for [white]$name[] in the last [white]$seconds[] seconds.")
            }
        }

        fun translate(
            text: String,
            from: String,
            to: String,
            onResult: Cons<String>,
            onError: Runnable
        ) {
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&dt=t"
            val query = "tl=$to&sl=$from&q=${Strings.encode(text)}"

            Http.post(url, query)
                .error { onError.run() }
                .submit { response ->
                    val resultText = reader.parse(response.resultAsString).get(0).get(0).asString()
                    onResult.get(resultText)
                }
        }

        private fun reloadWorld(runnable: Runnable) {
            try {
                val reloader = WorldReloader()
                reloader.begin()
                runnable.run()
                reloader.end()
            } catch (_: Exception) {
            }
        }

        fun showRankMenu(player: Player, page: Int = 1) {
            val myAcc   = getAccountByUuid(player.uuid())
            val myScore = myAcc?.score ?: 0

            val ranked  = accounts.values()
                .filter   { it.score > 0 }
                .sortedByDescending { it.score }

            if (ranked.isEmpty()) {
                Call.hideFollowUpMenu(player.con, rankMenuId)
                Call.announce(player.con, "[#D0D0D8DD]No one has a score yet.")
                return
            }

            val perPage = 30
            val total   = ranked.size
            val pages   = (total + perPage - 1) / perPage
            val curPage = page.coerceIn(1, pages)
            rankPageTracker[player.uuid()] = curPage

            val start   = (curPage - 1) * perPage
            val end     = minOf(start + perPage, total)

            val body = buildString {
                if (myScore > 0) {
                    val myRank = ranked.indexOfFirst { it.id == myAcc?.id } + 1
                    append("\n[#E2E2E2DD]Your Rank: [#D0D0D8DD]$myRank/$total | $myScore[]\n\n")
                } else {
                    append("[#D0D0D8DD]You have no points yet.[]\n\n")
                }

                for (i in start until end) {
                    val acc  = ranked[i]
                    val nick = when {
                        acc.nick.isNotBlank()  -> acc.nick
                        else                   -> "Unknown"
                    }
                    append("[#D0D0D8DD]${i + 1}. $nick: ${acc.score}[]\n")
                }
            }

            val nav = arrayOf(
                arrayOf(
                    if (curPage > 1) "[#F1F1F1DD]" else "",
                    "[#F1F1F1DD]",
                    if (curPage < pages) "[#F1F1F1DD]" else ""
                )
            )

            val title = "[#E2E2E2DD]Rankings $curPage/$pages"
            Call.followUpMenu(player.con, rankMenuId, title, body, nav)
        }

        fun showRollbackMenu(player: Player, page: Int = 1) {
            val folder: Fi = Vars.saveDirectory.child("snapshots")
            val allFiles = folder.list()
                .filter { it.name().startsWith("autosave-") && it.name().endsWith(".msav") }
                .sortedBy { it.lastModified() }

            if (allFiles.isEmpty()) {
                Call.announce(player.con,"[#D0D0D8DD]No snapshots available.[]")
                return
            }

            val mapsPerPage = 10
            val totalPages = (allFiles.size + mapsPerPage - 1) / mapsPerPage
            val currentPage = page.coerceIn(1, totalPages)

            val start = (currentPage - 1) * mapsPerPage
            val end = minOf(start + mapsPerPage, allFiles.size)
            val pageFiles = allFiles.subList(start, end)

            snapshotPages[player.uuid()] = pageFiles
            snapshotPageTracker[player.uuid()] = currentPage

            val rows = mutableListOf<Array<String?>>()
            pageFiles.forEachIndexed { i, file ->
                val displayName = file.name().removePrefix("autosave-").removeSuffix(".msav")
                rows.add(arrayOf("[#E2E2E2DD]${start + i + 1}. [#F1F1F1FF]$displayName"))
            }

            rows.add(arrayOf(
                if (currentPage > 1) "[#F1F1F1FF]" else "",
                "[#F1F1F1FF]",
                if (currentPage < totalPages) "[#F1F1F1FF]" else ""
            ))

            Call.followUpMenu(
                player.con,
                rollbackMenuId,
                "[#E2E2E2DD]Select a Snapshot $currentPage/$totalPages",
                "",
                rows.toTypedArray()
            )
        }
        fun onVoteKickMenuSelected(player: Player, choice: Int) {
            val kickablePlayers = Groups.player.filter {
                it != null && it.team() == player.team() && !it.admin && it.con != null && it != player
            }

            if (choice < 0) return

            if (choice == 0) {
                Call.hideFollowUpMenu(player.con, voteKickMenuId)
                return
            }

            val targetIndex = choice - 1
            if (targetIndex >= kickablePlayers.size) return

            val target = kickablePlayers[targetIndex] ?: return

            votekickTargets[player.uuid()] = target
            Call.textInput(
                player.con,
                voteKickTextInputId,
                "[#E2E2E2DD]Enter reason for kicking:",
                "Kick reason:",
                100,
                "",
                false
            )
        }


        fun onVoteKickReasonEntered(player: Player, reason: String) {
            val target = votekickTargets.remove(player.uuid()) ?: return

            when {
                target == player -> Call.announce(player.con(),"[#D0D0D8DD]You cannot kick yourself.[]")
                target.admin -> Call.announce(player.con(),"[#D0D0D8DD]You cannot kick an admin.[]")
                player.admin -> {
                    Call.announce("[#D0D0D8DD]${player.name()} kicked ${target.name()} directly. Reason: $reason")
                    target.kick("[#D0D0D8DD]You were kicked by an admin. Reason: $reason")
                }
                else -> beginVote(player, true, null, target, reason)
            }
        }
        fun handleRollbackMenuChoice(player: Player, choice: Int) {
            val currentPage = snapshotPageTracker[player.uuid()] ?: 1
            val pageFiles = snapshotPages[player.uuid()] ?: return

            if (choice in 0 until pageFiles.size) {
                if (!player.admin) {
                    Call.announce(player.con(),"[#D0D0D8DD]You are not an admin.[]")
                    return
                }

                val targetFile = pageFiles[choice]

                try {
                    if (Vars.state.isGame) {
                        Groups.player.each { it.kick(KickReason.serverRestarting) }
                        Vars.state.set(GameState.State.menu)
                        Vars.net.closeServer()
                    }

                    reloadWorld {
                        SaveIO.load(targetFile)
                        resetGameState()
                        Vars.state.set(GameState.State.playing)
                        Vars.netServer.openServer()
                    }

                    val displayName = targetFile.name().removePrefix("autosave-").removeSuffix(".msav")
                    Call.announce(player.con(),"[#D1DBF2DD]Rolled back to snapshot: $displayName[]")
                } catch (_: Exception) {
                    Call.announce(player.con(),"[#D1DBF2DD]Failed to load snapshot.[]")
                }
                return
            }

            if (choice < pageFiles.size) return

            val buttonIndex = choice - pageFiles.size
            val folder: Fi = Vars.saveDirectory.child("snapshots")
            val allFiles = folder.list()
                .filter { it.name().startsWith("autosave-") && it.name().endsWith(".msav") }
                .sortedBy { it.lastModified() }
            val totalPages = (allFiles.size + 9) / 10

            when (buttonIndex) {
                0 -> {
                    val prevPage = if (currentPage <= 1) totalPages else currentPage - 1
                    showRollbackMenu(player, prevPage)
                }
                1 -> {
                    Call.hideFollowUpMenu(player.con, rollbackMenuId)
                    return
                }
                2 -> {
                    val nextPage = if (currentPage >= totalPages) 1 else currentPage + 1
                    showRollbackMenu(player, nextPage)
                }
                else -> {
                }
            }
        }

        fun showMapMenu(player: Player, page: Int = 1) {
            val allMaps: Seq<Map> =
                if (Vars.maps.customMaps().isEmpty) Vars.maps.defaultMaps()
                else Vars.maps.customMaps()

            val mapsPerPage = 10
            val totalMaps   = allMaps.size
            val totalPages  = (totalMaps + mapsPerPage - 1) / mapsPerPage
            val currentPage = page.coerceIn(1, totalPages)
            mapPageTracker[player.uuid()] = currentPage

            val start = (currentPage - 1) * mapsPerPage
            val end   = minOf(start + mapsPerPage, totalMaps)

            val pageMaps = mutableListOf<Map>()
            val rows     = mutableListOf<Array<String?>>()

            for (i in start until end) {
                val map   = allMaps[i]
                pageMaps.add(map)

                val descLower = map.description()?.lowercase() ?: ""
                val mode = when {
                    descLower.contains("[@pvp]")      -> "PvP"
                    descLower.contains("[@survival]") -> "Survival"
                    descLower.contains("[@sandbox]")  -> "Sandbox"
                    descLower.contains("[@attack]")   -> "Attack"
                    else                              -> "PvP"
                }

                val author = map.author()?.ifBlank { "Unknown" } ?: "Unknown"

                val line = buildString {
                    append("[#D0D0D8DD]\uF029${i + 1} ")
                    append("[#ECECECEE]${map.name()}")
                    append("[#D0D0D0EE] | $mode")
                    append("\n[#ACACACDD]\uE809 $author")
                }

                rows.add(arrayOf(line))
            }

            paginatedMaps[player.uuid()] = pageMaps
            rows.add(
                arrayOf(
                    if (currentPage > 1) "[#F1F1F1FF]" else "",
                    "[#F1F1F1FF]",
                    if (currentPage < totalPages) "[#F1F1F1FF]" else ""
                )
            )

            Call.followUpMenu(
                player.con,
                mapMenuId,
                "[#E2E2E2DD]Choose Map $currentPage/$totalPages",
                "",
                rows.toTypedArray()
            )
        }


        fun handleMapMenuChoice(player: Player, choice: Int) {
            val page = mapPageTracker[player.uuid()] ?: 1
            val maps = paginatedMaps[player.uuid()] ?: return

            if (choice < maps.size) {
                val selectedMap = maps[choice]
                if (voteInProgress) {
                    Call.announce(player.con(),"[#D0D0D8DD]A vote is already in progress.[]")
                    return
                }
                val isAdmin = player.admin
                val rank = getAccountByUuid(player.uuid())?.score ?: 0

                val tick = Vars.state.tick

                if (!isAdmin) {
                    if (rank < 35) {
                        Call.announce(player.con(), "[#D0D0D8DD]You need at least 35 points to vote.[]")
                        return
                    }
                    if (Vars.state.isGame && tick > 5 * 60 * 60) {
                        Call.announce(player.con(), "[#D0D0D8DD]You can only vote within the first 5 minutes of a match.[]")
                        return
                    }
                }
                if (Groups.player.size() <= 1) {
                    Call.announce("[#E2E2E2DD]${player.name()}[][#D0D0D8DD] changed the map to ${selectedMap.name()}.[]")
                    playerTeams.clear()
                    resetGameState()
                    loadMap(selectedMap)
                    return
                }
                rtvPendingMap[player.uuid()] = selectedMap

                val mapAuthor = selectedMap.author() ?: "Unknown"
                val buttons = arrayOf(
                    arrayOf("[#F1F1F1DD]\uE800", "[#F1F1F1DD]\uE815")
                )

                val content = "\n${selectedMap.name()}\n[#D0D0D8DD]\uE809 $mapAuthor[]\n\n[#E2E2E2DD]Are you sure?\n"
                if (!isAdmin) {
                    Call.followUpMenu(player.con, rtvConfirmMenuId, "[#E2E2E2DD]Confirm Map Vote", content, buttons)
                } else {
                    Call.followUpMenu(player.con, rtvConfirmMenuId, "[#E2E2E2DD]Change map", content, buttons)
                }
                return
            }

            val totalMaps = if (Vars.maps.customMaps().isEmpty) Vars.maps.defaultMaps().size else Vars.maps.customMaps().size
            val totalPages = (totalMaps + 10 - 1) / 10
            val buttonIndex = choice - maps.size

            when (buttonIndex) {
                0 -> {
                    val newPage = if (page <= 1) totalPages else page - 1
                    showMapMenu(player, newPage)
                }
                1 -> {
                    Call.hideFollowUpMenu(player.con, mapMenuId)
                    return
                }
                2 -> {
                    val newPage = if (page >= totalPages) 1 else page + 1
                    showMapMenu(player, newPage)
                }
            }
        }

        private fun showLanguageMenu(player: Player) {
            val rows = arrayOf(
                arrayOf("[#F1F1F1FF]\uE815[]"),
                arrayOf("\uE868"),
                arrayOf("Auto Detect"),
                arrayOf("中文"),
                arrayOf("English"),
                arrayOf("Español"),
                arrayOf("Français"),
                arrayOf("Deutsch"),
                arrayOf("日本語"),
                arrayOf("한국어"),
                arrayOf("Русский"),
                arrayOf("Türkçe")
            )

            Call.followUpMenu(
                player.con,
                languageMenuId,
                "[#E2E2E2DD]Choose Translation Language",
                "",
                rows
            )
        }
        fun handleLanguageMenuChoice(player: Player, choice: Int) {
            when (choice) {
                1 -> {
                    playerLang[player.uuid()] = "off"
                    Call.announce(player.con(),"[#D0D0D8DD]Translation turned off.[]")
                }
                2 -> {
                    val locale = player.locale()
                    playerLang[player.uuid()] = locale
                    Call.announce(player.con(),"[#D0D0D8DD]Language auto-set to $locale.[]")
                }
                3 -> playerLang[player.uuid()] = "zh"
                4 -> playerLang[player.uuid()] = "en"
                5 -> playerLang[player.uuid()] = "es"
                6 -> playerLang[player.uuid()] = "fr"
                7 -> playerLang[player.uuid()] = "de"
                8 -> playerLang[player.uuid()] = "ja"
                9 -> playerLang[player.uuid()] = "ko"
                10 -> playerLang[player.uuid()] = "ru"
                11 -> playerLang[player.uuid()] = "tr"
                else -> {
                    Call.hideFollowUpMenu(player.con(), languageMenuId)
                    return
                }
            }

            if (choice >= 3) {
                val code = playerLang[player.uuid()]
                Call.announce(player.con(),"[#D0D0D8DD]Language set to $code.[]")
            }
        }

        private fun isProhibited(uuid: String): Boolean {
            val acc = getAccountByUuid(uuid) ?: return true
            return acc.banUntil > Time.millis()
        }

        private fun show(player: Player) {
            val teamsWithCore = Team.all.filter { t -> t.data().hasCore() && t != Team.derelict }.toMutableList()

            if (teamsWithCore.isEmpty()) {
                Call.announce(player.con, "There are no teams!")
                return
            }

            val all = Groups.player.copy().select { p ->
                val t = p.team()
                t != Team.derelict && t.data().hasCore()
            }

            val totalPlayers = all.size + 1
            val maxPerTeam = max(1, Mathf.ceil(totalPlayers / teamsWithCore.size.toFloat()))
            val desc = ""

            val columns = mutableListOf<MutableList<String?>>()

            for (t in teamsWithCore) {
                val col = mutableListOf<String?>()
                val count = all.count { p -> p.team() === t }

                col.add("${t.coloredName()}")

                all.each(Cons { p ->
                    if (p.team() === t) {
                        col.add("${p.name}")
                    }
                })

                if (count < maxPerTeam) {
                    col.add("[#E2E2E2DD]\uE813[]")
                }

                columns.add(col)
            }


            val maxRows = columns.maxOfOrNull { it.size } ?: 0

            for (col in columns) {
                while (col.size < maxRows) {
                    col.add("\uE868")
                }
            }

            val rows = mutableListOf<Array<String?>>()
            rows.add(arrayOf("[#F1F1F1FF]\uE88F"))
            for (rowIndex in 0 until maxRows) {
                val thisRow = Array(columns.size) { colIndex -> columns[colIndex][rowIndex] }
                rows.add(thisRow)
            }

            tempTeamChoices[player.uuid()] = teamsWithCore

            Call.followUpMenu(player.con, teamMenuId, "Choose a team", desc, rows.toTypedArray())
        }

        private fun onMenuChoose(player: Player, choice: Int) {
            if (choice < 0) return
            if (choice == 0) {
                Call.hideFollowUpMenu(player.con, teamMenuId)
                return
            }

            val teams = tempTeamChoices[player.uuid()]?.toList()?.toMutableList()
            if (teams.isNullOrEmpty()) return

            val totalCols = teams.size
            val adjustedChoice = choice - 1

            val colIndex = adjustedChoice % totalCols
            val rowIndex = adjustedChoice / totalCols


            val target = teams[colIndex]
            if (target === Team.derelict || !target.data().hasCore()) return

            val all = Groups.player.copy().select { p ->
                p?.team()?.let { it != Team.derelict && it.data().hasCore() } ?: false
            }
            val totalPlayers = all.size + 1
            val maxPerTeam = max(1, Mathf.ceil(totalPlayers / teams.size.toFloat()))
            val count = all.count { it?.team() === target }

            val col = all.filter { it?.team() === target }.mapNotNull { it?.name }
            val isPlusButton = (rowIndex == col.size + 1) && (count < maxPerTeam)

            if (!isPlusButton) {
                show(player)
                return
            }
            Call.hideFollowUpMenu(player.con, teamMenuId)
            player.team(target)
            playerTeams[player.uuid()] = target
            Call.announce(player.con, "[#E2E2E2DD]You're now in ${target.coloredName()}.")
        }

        private fun updateVoteHud() {
            if (!voteInProgress) return

            val playerCount = Groups.player.size()
            val requiredVotes = when {
                playerCount <= 2 -> playerCount
                playerCount == 3 -> 2
                else -> ceil(playerCount * RATIO).toInt()
            }

            val elapsed = (Vars.state.tick / 60f).toInt() - voteStartTime
            val remaining = maxOf(0f, VOTEDURATION - elapsed.toFloat()).toInt()

            val actionLine = if (kickVote) {
                "[#D0D0D8DD] \uE817 []${targetPlayer?.name ?: "<unknown>"}"
            } else {
                "[#D0D0D8DD] \uE827 []${targetMap?.name() ?: "<unknown>"}"
            }

            val statusLine =
                "[#D0D0D8DD][#ECECECEE]${votes.size}[]/$requiredVotes, [#ECECECEE]${remaining}s[] — type [#ECECECEE]y[] to confirm."

            val hudText = "$actionLine\n$statusLine"

            Call.setHudText(hudText)

        }

        fun beginTeamVote(initiator: Player) {
            val team = initiator.team()

            if (teamVotes.containsKey(team)) {
                Call.announce(initiator.con(), "[#D0D0D8DD]A surrender vote is already \n in progress for your team.")
                return
            }

            val teammates = Groups.player.filter { it.team() == team && !it.dead() }
            if (teammates.size < 3) {
                Call.announce(initiator.con(), "[#D0D0D8DD]At least 3 players \n are required to start a surrender vote.")
                return
            }

            teamVotes[team] = mutableSetOf(initiator.uuid())

            val buttons = arrayOf(arrayOf("[#F1F1F1DD]\uE800", "[#F1F1F1DD]\uE815"))
            val description = buildString {
                appendLine("\n\uF676\n\n[#D0D0D8DD] " + team.coloredName() + " is initiating a surrender vote by " + initiator.name + ".\n")
            }

            teammates.forEach { p ->
                if (p.uuid() != initiator.uuid()) {
                    Call.followUpMenu(p.con, teamVoteMenuId, "Surrender Vote", description, buttons)
                }
            }

            teamVoteTasks[team] = Timer.schedule({
                endTeamVote(team, force = false)
            }, TEAM_VOTE_DURATION)
        }

        fun checkTeamVoteResult(team: Team) {
            val teammates = Groups.player.filter { it.team() == team && !it.dead() }
            val voted = teamVotes[team]?.size ?: 0
            if (teammates.isEmpty()) return

            val percent = voted.toFloat() / teammates.size
            if (percent >= TEAM_VOTE_THRESHOLD) {
                endTeamVote(team, force = true)
            }
        }

        private val teamVoteMenuId: Int = Menus.registerMenu { player, choice ->
            val team = player.team()
            val uuid = player.uuid()

            if (!teamVotes.containsKey(team) || teamVotes[team]?.contains(uuid) == true) {
                Call.hideFollowUpMenu(player.con(), teamVoteMenuId)
                return@registerMenu
            }

            when (choice) {
                0 -> {
                    teamVotes[team]?.add(uuid)
                    Call.hideFollowUpMenu(player.con(), teamVoteMenuId)
                    checkTeamVoteResult(team)
                }

                1 -> {
                    Call.hideFollowUpMenu(player.con(), teamVoteMenuId)
                }
            }
        }

        fun endTeamVote(team: Team, force: Boolean) {
            val teammates = Groups.player.filter { it.team() == team && !it.dead() }
            val voted = teamVotes[team]?.size ?: 0
            val percent = if (teammates.isEmpty()) 0f else voted.toFloat() / teammates.size

            if (percent >= TEAM_VOTE_THRESHOLD || force) {
                Call.announce("[#D0D0D8DD]Team ${team.name} has surrendered! (${(percent * 100).toInt()}%)")
                team.cores().forEach { it.kill() }
            } else {
                Call.announce("[#D0D0D8DD]Team ${team.name} failed to surrender. (${(percent * 100).toInt()}%)")
            }
            teamVotes.remove(team)
            teamVoteTasks.remove(team)?.cancel()
        }

        private fun beginVote(
            initiator: Player,
            isKick: Boolean,
            map: Map?,
            target: Player?,
            reason: String?
        ) {
            voteInProgress = true
            voteInitiator = initiator
            kickVote = isKick
            targetMap = map
            targetPlayer = target
            votes.clear()
            votes.add(initiator.uuid())
            voteStartTime = (Vars.state.tick / 60f).toInt()
            updateVoteHud()
            voteUpdateTask = Timer.schedule({
                if (!voteInProgress) {
                    voteUpdateTask?.cancel()
                    voteUpdateTask = null
                    return@schedule
                }
                updateVoteHud()
            }, 1f, 1f)

            val buttons = arrayOf(arrayOf("[#ECECECEE]\uE800", "[#D0D0D8DD]\uE868"))
            val description = buildString {
                appendLine()
                if (isKick) {
                    append("[#ACACACEE]\uE817[] ${target?.name()}")
                } else {
                    append("[#ACACACEE]\uE827[] ${map?.name()}")
                }
                appendLine()
                append("[#F1F1F1DD]Initiator: ${initiator.plainName()}")
                appendLine()
                if (!reason.isNullOrBlank()) {
                    append("Reason: ${reason.trim()}")
                    appendLine()
                }
            }
            Timer.schedule({
                Groups.player.each { p ->
                    if (voteInitiator != null && p.uuid() != voteInitiator?.uuid() && p.uuid() != targetPlayer?.uuid()) {
                        Call.followUpMenu(p.con, voteMenuId, "Vote in Progress", description, buttons)
                    }
                }
            }, 1f)

            voteTimer = Timer.schedule({
                endVote()
            }, VOTEDURATION)
        }


        private fun endVote() {
            voteInProgress = false
            kickVote = false
            voteInitiator = null
            targetMap = null
            targetPlayer = null
            votes.clear()
            voteTimer?.cancel()
            voteTimer = null
            voteUpdateTask?.cancel()
            voteUpdateTask = null
            Call.setHudText("")
            Call.hideHudText()
        }

        private fun loadMap(map: Map?) {
            if (map == null) return
            val mode: Gamemode
            val mapDescription = map.description()
            val descLower: String = mapDescription.lowercase()

            mode = if (descLower.contains("[@pvp]")) {
                Gamemode.pvp
            } else if (descLower.contains("[@survival]")) {
                Gamemode.survival
            } else if (descLower.contains("[@sandbox]")) {
                Gamemode.sandbox
            } else if (descLower.contains("[@attack]")) {
                Gamemode.attack
            } else {
                Gamemode.pvp
            }

            val folder: Fi = Vars.saveDirectory.child("snapshots")
            folder.list()?.filter { it.name().startsWith("autosave-") }?.forEach { it.delete() }

            val finalMode = mode
            reloadWorld {
                Vars.state.map = map
                Vars.world.loadMap(map)
                Vars.state.rules = map.applyRules(finalMode)
                Vars.logic.play()
            }
        }


        fun wrapText(text: String): String {
            val wrappedText = StringBuilder()
            var start = 0

            while (start < text.length) {
                val end: Int = min(start + 50, text.length)
                wrappedText.append(text, start, end).append("\n")
                start = end
            }

            return wrappedText.toString()
        }

        fun showMapLabel(player: Player) {
            val build = Groups.build.find { b: Building? -> b is CoreBuild && b.team === player.team() }
            val core = build as? CoreBuild

            val mapName = Vars.state.map.name()
            val mapAuthor = Vars.state.map.author()
            val mapDesc = Vars.state.map.description()

            var text = "[#F1F1F1FF]$mapName"
            if (mapAuthor != null && !mapAuthor.trim { it <= ' ' }.isEmpty()) {
                text += "\n\n[#D0D0D8DD]" + mapAuthor.trim { it <= ' ' }
            }
            if (mapDesc != null && !mapDesc.trim { it <= ' ' }.isEmpty()) {
                text += "\n\n[#D0D0D8DD]" + wrapText(mapDesc.trim { it <= ' ' })
            }

            val x = core?.x ?: (Vars.world.unitWidth() / 2f)
            val y = core?.y ?: (Vars.world.unitHeight() / 2f)

            Call.label(player.con, text, 10f, x, y)
        }

        private fun writeString(buffer: ByteBuffer, string: String, maxlen: Int) {
            requireNotNull(string) { "String cannot be null." }

            var bytes: ByteArray = string.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > maxlen) {
                bytes = bytes.copyOfRange(0, maxlen)
            }

            buffer.put(bytes.size.toByte())

            buffer.put(bytes)
        }

        private fun getLocalizedDisplayDesc(lang: String, minutes: Int): String {
            val raw = when (lang) {
                "zh" -> "游戏已进行 $minutes 分钟。[]"
                "ja" -> "ゲーム開始から $minutes 分経過。[]"
                "ru" -> "Игра началась $minutes минут назад.[]"
                "ko" -> "게임 시작 $minutes 분 경과.[]"
                "pt" -> "Jogo iniciado há $minutes minutos.[]"
                "es" -> "Partida iniciada hace $minutes minutos.[]"
                "uk" -> "Гру розпочато $minutes хвилин тому.[]"
                "ms" -> "Permainan telah bermula sejak $minutes minit.[]"
                "fil" -> "Nagsimula ang laro $minutes minuto na ang nakalipas.[]"
                "tr" -> "[#EDF4FCBB]Oyun $minutes dakika önce başladı.[]"
                else -> "Game started $minutes minutes ago.[]"
            }
            return "[#D0D0D8DD]$raw"
        }

        private fun applyfly() {
            UnitTypes.risso.flying = true
            UnitTypes.minke.flying = true
            UnitTypes.bryde.flying = true
            UnitTypes.sei.flying = true
            UnitTypes.omura.flying = true
            UnitTypes.retusa.flying = true
            UnitTypes.oxynoe.flying = true
            UnitTypes.cyerce.flying = true
            UnitTypes.aegires.flying = true
            UnitTypes.navanax.flying = true

            UnitTypes.crawler.flying = true
            UnitTypes.atrax.flying = true
            UnitTypes.spiroct.flying = true
        }

        private fun applyUnfly() {
            UnitTypes.risso.flying = false
            UnitTypes.minke.flying = false
            UnitTypes.bryde.flying = false
            UnitTypes.sei.flying = false
            UnitTypes.omura.flying = false
            UnitTypes.retusa.flying = false
            UnitTypes.oxynoe.flying = false
            UnitTypes.cyerce.flying = false
            UnitTypes.aegires.flying = false
            UnitTypes.navanax.flying = false
            UnitTypes.crawler.flying = false
            UnitTypes.atrax.flying = false
            UnitTypes.spiroct.flying = false
        }
    }
}