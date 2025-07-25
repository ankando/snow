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
import plugin.core.PermissionManager.isCoreAdmin
import plugin.core.TagUtil.getMode
import plugin.snow.PluginMenus.showAuthMenu
import plugin.snow.PluginMenus.showTeamMenu
import kotlin.math.max

object EventManager {
    private val modeRotation = listOf(
        Gamemode.pvp,
        Gamemode.survival,
        Gamemode.attack,
        Gamemode.sandbox
    )
    private var currentRotationMode: Gamemode? = null
    fun init() {
        Events.on(PlayerJoin::class.java) { e ->
            val player = e.player
            val pData = DataManager.getPlayerDataByUuid(player.uuid())
            if (pData == null) {
                showAuthMenu(player)
                return@on
            }
            if (isCoreAdmin(player.uuid())) {
                player.admin = true
            }
        }

        Events.on(PlayerConnectionConfirmed::class.java) { e ->
            val player = e.player
            val pData = DataManager.getPlayerDataByUuid(player.uuid())
            if (pData != null && Vars.state.rules.pvp &&
                (player.team() == null || player.team() == Team.derelict)
            ) {
                showTeamMenu(player)
            }
        }

        Events.on(PlayerLeave::class.java) { e ->
            val uuid = e.player.uuid()
            when (uuid) {
                VoteManager.globalVoteCreator?.uuid() -> VoteManager.clearVote()
                in VoteManager.globalVoteExcluded -> DataManager.getPlayerDataByUuid(uuid)
                    ?.let { acc ->
                        DataManager.updatePlayer(acc.id) {
                            it.banUntil = System.currentTimeMillis() + 600_000
                        }
                    }
            }
        }

        Events.on(GameOverEvent::class.java) { e ->
            val winner = e.winner ?: return@on
            if (winner == Team.derelict || winner.cores().isEmpty) return@on

            Call.sound(
                Sounds.explosionbig,
                Vars.world.unitWidth() / 2f,
                Vars.world.unitHeight() / 2f,
                1f
            )

            when (Vars.state.rules.mode()) {
                Gamemode.pvp -> handlePvpGameOver(winner)
                else -> handleCoopGameOver(winner)
            }
        }

        Events.on(ResetEvent::class.java) {
            PlayerTeamManager.clear()
            RevertBuild.clearAll()
            VoteManager.clearVote()
            NextMap.clear()
        }

        Events.on(PlayEvent::class.java) {
            val currentMap  = Vars.state.map
            val currentMode = getMode(currentMap.description())

            val targetMode = currentMode
                ?: Vars.maps.customMaps().firstNotNullOfOrNull { getMode(it.description()) }
                ?: return@on

            if (currentRotationMode != targetMode) {
                UsedMaps.clear()
                currentRotationMode = targetMode
            }

            UsedMaps.add(currentMap)

            val allMapsByMode = Vars.maps.customMaps()
                .groupBy { getMode(it.description()) }
                .filterKeys { it != null }
                .mapKeys { it.key!! }

            val startIndex = modeRotation.indexOf(targetMode).let { if (it >= 0) it else 0 }

            for (i in modeRotation.indices) {
                val mode = modeRotation[(startIndex + i) % modeRotation.size]
                val mapsForMode = allMapsByMode[mode] ?: continue

                val unused = mapsForMode.filterNot { UsedMaps.isUsed(it) }
                if (unused.isNotEmpty()) {
                    val selected = unused.random()
                    Vars.maps.setNextMapOverride(selected)
                    NextMap.set(selected)
                    break
                } else {
                    UsedMaps.clear()
                }
            }
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
            val pl = Groups.player.find { it.uuid() == uuid } ?: return@forEach

            if (team == winner) {
                DataManager.updatePlayer(acc.id) {
                    it.score += winScore
                    it.wins++
                }
                Call.announce(
                    pl.con,
                    "${PluginVars.SUCCESS}${I18nManager.get("game.victory", pl)} +$winScore${PluginVars.RESET}"
                )
            } else {
                val penalty = max(20, 50 - teamCounts.getValue(team) * 3)
                DataManager.updatePlayer(acc.id) {
                    it.score = max(0, it.score - penalty)
                }
                Call.announce(
                    pl.con,
                    "${PluginVars.ERROR}${I18nManager.get("game.defeat", pl)} -$penalty${PluginVars.RESET}"
                )
            }
        }
    }

    private fun handleCoopGameOver(winner: Team) {
        val winners = Groups.player.copy().filter { it.team() == winner }
        val score = max(25, 40 - winners.size * 4)

        winners.forEach { p ->
            DataManager.getPlayerDataByUuid(p.uuid())?.let { acc ->
                DataManager.updatePlayer(acc.id) {
                    it.score += score
                    it.wins++
                }
            }
            Call.announce(
                p.con,
                "${PluginVars.SUCCESS}${I18nManager.get("game.victory", p)} +$score${PluginVars.RESET}"
            )
        }
    }
}
