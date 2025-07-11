package plugin.snow

import arc.Core
import arc.Events
import mindustry.Vars
import mindustry.content.UnitTypes
import mindustry.game.EventType
import mindustry.game.Gamemode
import mindustry.game.Team
import mindustry.gen.Call
import mindustry.gen.Groups
import mindustry.gen.Sounds
import mindustry.world.blocks.storage.CoreBlock
import plugin.core.*
import plugin.snow.PluginMenus.showAuthMenu
import kotlin.math.max

object EventManager {
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

    private val usedMapNames = mutableSetOf<String>()
    fun init() {
        Events.on(EventType.PlayerJoin::class.java) { e ->
            val player = e.player
            val uuid = player.uuid()
            val data = DataManager.getPlayerDataByUuid(uuid)
            if (data == null) {
                showAuthMenu(player)
            }
        }

        Events.on(EventType.PlayerLeave::class.java) { e ->
            val player = e.player
            if (VoteManager.getGlobalVoteCreator()?.uuid() == player.uuid()) {
                VoteManager.clearVote()
                return@on
            }

            val excluded = VoteManager.getGlobalVoteExcluded()
            if (excluded.contains(player.uuid())) {
                val acc = DataManager.getPlayerDataByUuid(player.uuid())
                if (acc != null) {
                    DataManager.updatePlayer(acc.id) { it.banUntil = System.currentTimeMillis() + 10 * 60 * 1000 }
                }
                return@on
            }
        }
        Events.on(EventType.GameOverEvent::class.java) { e ->
            val cx = Vars.world.width() * Vars.tilesize / 2f
            val cy = Vars.world.height() * Vars.tilesize / 2f
            Call.sound(Sounds.explosionbig, cx, cy, 1f)
            val winner = e.winner ?: return@on
            val hasWinner = winner != Team.derelict && Vars.state.teams.get(winner).hasCore()
            val allTeams = PlayerTeamManager.all()
            val teamSizes = allTeams.values.filter { it != Team.derelict }
                .groupingBy { it }
                .eachCount()

            if (Vars.state.rules.mode() == Gamemode.pvp && hasWinner) {
                val winnerOnlineCount = Groups.player.count { it.team() == winner }
                val winScore = max(25, 40 - max(1, winnerOnlineCount) * 4)

                for ((uuid, team) in allTeams) {
                    if (team == Team.derelict) continue
                    val player = Groups.player.find { it.uuid() == uuid }
                    val acc = DataManager.getPlayerDataByUuid(uuid) ?: continue

                    if (team == winner) {
                        DataManager.updatePlayer(acc.id) {
                            it.score += winScore
                            it.wins++
                        }
                        player?.let {
                            Call.announce(
                                it.con,
                                "${PluginVars.SUCCESS}${
                                    I18nManager.get(
                                        "game.victory",
                                        it
                                    )
                                } +${winScore}${PluginVars.RESET}"
                            )
                        }
                    } else {
                        val size = teamSizes.getOrDefault(team, 1)
                        val penalty = max(20, 50 - size * 3)
                        DataManager.updatePlayer(acc.id) {
                            it.score = max(0, it.score - penalty)
                        }
                        player?.let {
                            Call.announce(
                                it.con,
                                "${PluginVars.ERROR}${
                                    I18nManager.get(
                                        "game.defeat",
                                        it
                                    )
                                } -${penalty}${PluginVars.RESET}"
                            )
                        }
                    }
                }
            } else if (hasWinner) {
                val winners = Groups.player.copy().select { it.team() == winner }
                val total = winners.size
                val score = max(25, 40 - total * 4)

                winners.each { p ->
                    val acc = DataManager.getPlayerDataByUuid(p.uuid()) ?: return@each
                    DataManager.updatePlayer(acc.id) {
                        it.score += score
                        it.wins++
                    }
                    Call.announce(
                        p.con,
                        "${PluginVars.SUCCESS}${I18nManager.get("game.victory", p)} +${score}${PluginVars.RESET}"
                    )
                }
            }
        }

        Events.on(EventType.BlockDestroyEvent::class.java) { event ->
            val team = event.tile.team()
            if (event.tile.block() is CoreBlock) {
                if (team !== Team.derelict && team.cores().size <= 1) {
                    team.data().players.each { p ->
                        val msg = if (Vars.state.rules.mode() == Gamemode.pvp)
                            "${PluginVars.ERROR}${I18nManager.get("core.lost.team", p)}${PluginVars.RESET}"
                        else
                            "${PluginVars.WARN}${I18nManager.get("core.lost.single", p)}${PluginVars.RESET}"
                        p?.let { Call.announce(it.con, msg) }
                    }
                }
            }
        }

        Events.on(EventType.PlayEvent::class.java) {
            PlayerTeamManager.clear()
            RevertBuild.clearAll()
            VoteManager.clearVote()

            val map = Vars.state.map
            val desc = map.description().lowercase()
            val tags = TagUtil.getTags(desc).map { it.lowercase() }
            val modeMap = mapOf(
                "pvp" to Gamemode.pvp,
                "survival" to Gamemode.survival,
                "sandbox" to Gamemode.sandbox,
                "attack" to Gamemode.attack
            )
            val matched = modeMap.keys.filter { key -> tags.any { it == key } }
            val modeKey = if (matched.size == 1) matched.first()
            else {
                val saved = Core.settings.get("lastServerMode", "sandbox")
                if (saved in modeMap) saved else "sandbox"
            }
            val gamemode = modeMap[modeKey] ?: Gamemode.sandbox

            if (tags.contains("fly")) {
                applyfly()
            } else applyUnfly()

            Vars.state.rules = map.applyRules(gamemode)

            val currentMapName = map.file.name()
            if (!usedMapNames.contains(currentMapName)) usedMapNames.add(currentMapName)

            val candidates = Vars.maps.customMaps().select { m ->
                val d = m?.description()?.lowercase() ?: ""
                val mtags = TagUtil.getTags(d).map { it.lowercase() }
                modeMap.keys.none { key -> mtags.any { it == key } }
            }
            val pool = candidates.filter { m ->
                val name = m.file.name()
                name !in usedMapNames && m != Vars.state.map
            }
            if (pool.isEmpty()) {
                usedMapNames.clear()
                usedMapNames.add(currentMapName)
                Vars.maps.setNextMapOverride(null)
            } else {
                Vars.maps.setNextMapOverride(pool.random())
            }
        }
    }
}
