package plugin

import arc.Core
import arc.util.*
import com.maxmind.geoip2.DatabaseReader
import mindustry.Vars
import mindustry.core.NetServer
import mindustry.core.Version
import mindustry.game.Team
import mindustry.gen.*
import mindustry.mod.Plugin
import mindustry.net.Administration
import mindustry.net.Administration.ActionType
import mindustry.net.Packets
import plugin.core.*
import plugin.snow.*
import plugin.snow.PluginMenus.showTeamMenu
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlin.math.max

class Main : Plugin() {

    private val dbFile = Vars.saveDirectory.child("GeoLite2-Country.mmdb")
    private val isoLang = mapOf(
        "CN" to "zh", "TW" to "zh", "HK" to "zh", "MO" to "zh", "SG" to "zh", "MY" to "zh",
        "JP" to "ja", "KR" to "ko", "RU" to "ru", "KZ" to "ru", "KG" to "ru", "TJ" to "ru", "UZ" to "ru",
        "AM" to "ru", "GE" to "ru", "UA" to "uk", "BY" to "be", "RS" to "sr", "ME" to "sr", "BA" to "sr",
        "BG" to "bg", "FR" to "fr", "ES" to "es", "MX" to "es", "AR" to "es", "CL" to "es", "CO" to "es",
        "VE" to "es", "PE" to "es", "EC" to "es", "UY" to "es", "PY" to "es", "BO" to "es", "GT" to "es",
        "SV" to "es", "HN" to "es", "NI" to "es", "CR" to "es", "PA" to "es", "CU" to "es", "DO" to "es",
        "PR" to "es", "PT" to "pt", "BR" to "pt", "AO" to "pt", "MZ" to "pt", "GW" to "pt", "CV" to "pt",
        "ST" to "pt", "TL" to "pt", "IT" to "it", "DE" to "de", "AT" to "de", "CH" to "de", "NL" to "nl",
        "BE" to "nl", "PL" to "pl", "CZ" to "cs", "SK" to "cs", "RO" to "ro", "MD" to "ro", "HU" to "hu",
        "FI" to "fi", "SE" to "sv", "DK" to "da", "EE" to "et", "LT" to "lt", "TR" to "tr", "TM" to "tk",
        "VN" to "vi", "TH" to "th", "ID" to "id", "PH" to "fil", "ES-PV" to "eu", "GR" to "el", "IN" to "hi"
    )
    private val langName = mapOf(
        "zh" to "雪", "ja" to "スノー", "ko" to "스노우", "ru" to "Сноу", "uk" to "Сніг", "be" to "Снег",
        "sr" to "Снег", "bg" to "Сняг", "fr" to "Neige", "es" to "Nieve", "pt" to "Neve", "it" to "Neve",
        "de" to "Schnee", "nl" to "Sneeuw", "pl" to "Śnieg", "cs" to "Sníh", "ro" to "Zăpadă", "hu" to "Hó",
        "fi" to "Lumi", "sv" to "Snö", "da" to "Sne", "et" to "Lumi", "lt" to "Sniegas", "tr" to "Kar",
        "tk" to "Gar", "vi" to "Tuyết", "th" to "หิมะ", "id" to "Salju", "fil" to "Niyebe", "eu" to "Elur",
        "el" to "Χιόνι", "hi" to "बर्फ", "en" to "Snow"
    )
    private val geoDB by lazy {
        if (!dbFile.exists()) javaClass.classLoader.getResourceAsStream(dbFile.name())!!.use {
            dbFile.write(it, false)
        }
        DatabaseReader.Builder(dbFile.file()).build()
    }

    override fun init() {
        DataManager.init()
        EventManager.init()
        I18nManager.init()
        WebUploader.init()

        setupDiscovery()
        Vars.netServer.assigner = NetServer.TeamAssigner { player, _ -> assignTeam(player) }
        Vars.netServer.invalidHandler = invalidCommandHandler
        Vars.netServer.admins.addChatFilter(NetEvents::chat)
        with(Vars.net) {
            handleServer(AdminRequestCallPacket::class.java, NetEvents::adminRequest)
            handleServer(Packets.Connect::class.java, NetEvents::connect)
            handleServer(Packets.ConnectPacket::class.java, NetEvents::connectPacket)
        }
        Vars.netServer.admins.addActionFilter(::actionFilter)
    }

    override fun registerClientCommands(handler: CommandHandler) {
        listOf("a", "t", "vote", "votekick", "sync").forEach(handler::removeCommand)
        ClientCommands.register(handler)
    }

    private fun setupDiscovery() {
        val provider = Reflect.get<mindustry.net.ArcNetProvider>(Vars.net, "provider")
        Reflect.get<arc.net.Server>(provider, "server").setDiscoveryHandler { addr, h ->
            val ip = addr?.address?.let(InetAddress::getByAddress) ?: return@setDiscoveryHandler
            val iso = runCatching { geoDB.country(ip).country.isoCode }.getOrNull() ?: "EN"
            val lang = isoLang[iso] ?: "en"
            val name = langName[lang] ?: "Snow"
            val minutes = (Vars.state.tick / 3600f).toInt()
            val buffer = ByteBuffer.allocate(512).apply {
                putString(name, 100)
                putString(Vars.state.map.name(), 64)
                putInt(max(0, Core.settings.getInt("totalPlayers", Groups.player.size())))
                putInt(Vars.state.wave)
                putInt(Version.build)
                putString(Version.type, 50)
                put(Vars.state.rules.mode().ordinal.toByte())
                putInt(max(Vars.netServer.admins.playerLimit, 0))
                putString(getLocalizedDisplayDesc(lang, minutes), 200)
                putString(Vars.state.rules.modeName ?: "", 50)
                flip()
            }
            h?.respond(buffer)
        }
    }

    private fun ByteBuffer.putString(text: String, max: Int) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8).let { if (it.size > max) it.copyOf(max) else it }
        put(bytes.size.toByte())
        put(bytes)
    }

    private fun assignTeam(player: Player?): Team = when {
        player == null -> Vars.state.rules.defaultTeam
        !PermissionManager.isNormal(player.uuid()) -> Team.derelict
        !Vars.state.rules.pvp -> Vars.state.rules.defaultTeam
        else -> PlayerTeamManager.getTeam(player.uuid())?.also {
            if (!it.data().hasCore() && it != Team.derelict)
                Call.announce(player.con, "${PluginVars.WARN}${I18nManager.get("team.lost", player)}${PluginVars.RESET}")
        } ?: run { showTeamMenu(player); Team.derelict }
    }

    private fun actionFilter(action: Administration.PlayerAction?): Boolean {
        val player = action?.player ?: return false
        val uuid = player.uuid()

        if (player.unit().let { it == null || it.dead() }) return false
        if (!PermissionManager.isNormal(uuid)) return false
        if (!player.team().data().hasCore() || player.team() == Team.derelict) return false
        if (PermissionManager.isBanned(uuid)) return false

        if (action.type == ActionType.breakBlock) action.tile?.let { RevertBuild.recordRemove(player, it) }

        return true
    }

    private val invalidCommandHandler = NetServer.InvalidCommandHandler { player, res ->
        val key = when (res.type) {
            CommandHandler.ResponseType.manyArguments -> "cmd.too_many_args"
            CommandHandler.ResponseType.fewArguments -> "cmd.too_few_args"
            else -> null
        }
        key?.let {
            return@InvalidCommandHandler "${PluginVars.WARN}${I18nManager.get(it, player)} ${PluginVars.INFO}${res.command.text} ${res.command.paramText}${PluginVars.RESET}"
        }
        val hint = Vars.netServer.clientCommands.commandList
            .minByOrNull { Strings.levenshtein(it.text, res.runCommand) }
            ?.takeIf { Strings.levenshtein(it.text, res.runCommand) < 3 }
        if (hint != null)
            "${PluginVars.INFO}${I18nManager.get("cmd.hint", player)} /${hint.text}${PluginVars.RESET}"
        else
            "${PluginVars.WARN}${I18nManager.get("cmd.unknown", player)}${PluginVars.RESET}"
    }

    private fun getLocalizedDisplayDesc(lang: String, minutes: Int) = when (lang) {
        "zh" -> "[#D0D0D8DD]游戏已进行 $minutes 分钟。[]"
        "ja" -> "[#D0D0D8DD]ゲーム開始から $minutes 分経過。[]"
        "ru" -> "[#D0D0D8DD]Игра началась $minutes минут назад.[]"
        "ko" -> "[#D0D0D8DD]게임 시작 $minutes 분 경과.[]"
        "pt" -> "[#D0D0D8DD]Jogo iniciado há $minutes minutos.[]"
        "es" -> "[#D0D0D8DD]Partida iniciada hace $minutes minutos.[]"
        "uk" -> "[#D0D0D8DD]Гру розпочато $minutes хвилин тому.[]"
        "ms" -> "[#D0D0D8DD]Permainan telah bermula sejak $minutes minit.[]"
        "fil" -> "[#D0D0D8DD]Nagsimula ang laro $minutes minuto na ang nakalipas.[]"
        "tr" -> "[#D0D0D8DD]Oyun $minutes dakika önce başladı.[]"
        else -> "[#D0D0D8DD]Game started $minutes minutes ago.[]"
    }
}
