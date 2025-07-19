package plugin.snow

import arc.Events
import mindustry.Vars
import mindustry.game.EventType.*
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Sounds
import plugin.core.*
import plugin.snow.PluginMenus.showAuthMenu
import plugin.snow.PluginMenus.showTeamMenu
import kotlin.math.max

object EventManager {

    private val usedMaps = mutableSetOf<String>()
    private val modeTags = mapOf(
        "pvp" to Gamemode.pvp,
        "survival" to Gamemode.survival,
        "sandbox" to Gamemode.sandbox,
        "attack" to Gamemode.attack
    )
    private val validModeKeys = modeTags.keys

    fun init() {


        Events.on(PlayerJoin::class.java) { e ->
            val player = e.player

            val pData = DataManager.getPlayerDataByUuid(player.uuid())
            if (pData == null) {
                showAuthMenu(player)
                return@on
            }
        }

        Events.on(PlayerConnectionConfirmed::class.java) { e ->
            val player = e.player
            if (Vars.state.rules.pvp && (player.team() == null || player.team() == Team.derelict)) {
                showTeamMenu(player)
            }
        }


        Events.on(PlayerLeave::class.java) { e ->
            val uuid = e.player.uuid()
            when (uuid) {
                VoteManager.globalVoteCreator?.uuid() -> VoteManager.clearVote()
                in VoteManager.globalVoteExcluded -> DataManager.getPlayerDataByUuid(uuid)?.let { acc ->
                    DataManager.updatePlayer(acc.id) { it.banUntil = System.currentTimeMillis() + 600_000 }
                }
            }
        }

        Events.on(GameOverEvent::class.java) { e ->
            val winner = e.winner ?: return@on
            if (winner == Team.derelict || winner.cores().isEmpty) return@on
            Call.sound(Sounds.explosionbig, Vars.world.unitWidth() / 2f, Vars.world.unitHeight() / 2f, 1f)
            when (Vars.state.rules.mode()) {
                Gamemode.pvp -> handlePvpGameOver(winner)
                else         -> handleCoopGameOver(winner)
            }
        }

        Events.on(ResetEvent::class.java) {
            PlayerTeamManager.clear()
            RevertBuild.clearAll()
            VoteManager.clearVote()
            usedMaps.clear()
        }

        Events.on(PlayEvent::class.java) {
            val map = Vars.state.map
            parseModeTag(map.description())?.let { Vars.state.rules = map.applyRules(it) }

            val currentName = map.file.name()
            usedMaps += currentName
            val pool = Vars.maps.customMaps().filter { it.file.name() !in usedMaps && it != map }
            Vars.maps.setNextMapOverride(pool.randomOrNull())
            if (pool.isEmpty()) usedMaps.clear().also { usedMaps += currentName }
        }
    }

    private fun handlePvpGameOver(winner: Team) {
        val teamCounts = PlayerTeamManager.all().values
            .filter { it != Team.derelict }
            .groupingBy { it }
            .eachCount()

        val winScore = max(25, 40 - max(1, Groups.player.count { it.team() == winner }) * 4)

        PlayerTeamManager.all().forEach { (uuid, team) ->
            if (team == Team.derelict) return@forEach
            val acc = DataManager.getPlayerDataByUuid(uuid) ?: return@forEach
            val pl = Groups.player.find { it.uuid() == uuid }

            if (team == winner) {
                DataManager.updatePlayer(acc.id) { it.score += winScore; it.wins++ }
                pl?.let { Call.announce(it.con, "${PluginVars.SUCCESS}${I18nManager.get("game.victory", it)} +$winScore${PluginVars.RESET}") }
            } else {
                val penalty = max(20, 50 - teamCounts.getValue(team) * 3)
                DataManager.updatePlayer(acc.id) { it.score = max(0, it.score - penalty) }
                pl?.let { Call.announce(it.con, "${PluginVars.ERROR}${I18nManager.get("game.defeat", it)} -$penalty${PluginVars.RESET}") }
            }
        }
    }

    private fun handleCoopGameOver(winner: Team) {
        val winners = Groups.player.copy().filter { it.team() == winner }
        val score = max(25, 40 - winners.size * 4)
        winners.forEach { p ->
            DataManager.getPlayerDataByUuid(p.uuid())?.let { acc ->
                DataManager.updatePlayer(acc.id) { it.score += score; it.wins++ }
            }
            Call.announce(p.con, "${PluginVars.SUCCESS}${I18nManager.get("game.victory", p)} +$score${PluginVars.RESET}")
        }
    }

    private fun parseModeTag(desc: String): Gamemode? {
        val tags = Regex("""\[@([a-z0-9_-]+)]""").findAll(desc.lowercase())
            .map { it.groupValues[1] }
            .toList()
        val tag = tags.singleOrNull { it in validModeKeys } ?: return null
        return modeTags[tag]
    }
}
