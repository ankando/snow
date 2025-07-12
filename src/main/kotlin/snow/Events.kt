package plugin.snow

import arc.Events
import mindustry.Vars
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

    private val usedMapNames = mutableSetOf<String>()

    fun init() {
        Events.on(EventType.PlayerJoin::class.java) { e ->
            val uuid = e.player.uuid()
            if (DataManager.getPlayerDataByUuid(uuid) == null) showAuthMenu(e.player)
        }

        Events.on(EventType.PlayerLeave::class.java) { e ->
            val uuid = e.player.uuid()
            if (VoteManager.globalVoteCreator?.uuid() == uuid) {
                VoteManager.clearVote(); return@on
            }
            if (VoteManager.globalVoteExcluded.contains(uuid)) {
                DataManager.getPlayerDataByUuid(uuid)?.let {
                    DataManager.updatePlayer(it.id) { acc ->
                        acc.banUntil = System.currentTimeMillis() + 10 * 60 * 1000
                    }
                }
            }
        }

        Events.on(EventType.GameOverEvent::class.java) { e ->
            val winner = e.winner ?: return@on
            if (winner == Team.derelict || !Vars.state.teams.get(winner).hasCore()) return@on

            val cx = Vars.world.width() * Vars.tilesize / 2f
            val cy = Vars.world.height() * Vars.tilesize / 2f
            Call.sound(Sounds.explosionbig, cx, cy, 1f)
            val allTeams = PlayerTeamManager.all()
            val teamSizes = allTeams.values.filter { it != Team.derelict }.groupingBy { it }.eachCount()

            if (Vars.state.rules.mode() == Gamemode.pvp) {
                val winScore = max(25, 40 - max(1, Groups.player.count { it.team() == winner }) * 4)
                for ((uuid, team) in allTeams) {
                    if (team == Team.derelict) continue
                    val acc = DataManager.getPlayerDataByUuid(uuid) ?: continue
                    val player = Groups.player.find { it.uuid() == uuid }

                    if (team == winner) {
                        DataManager.updatePlayer(acc.id) {
                            it.score += winScore
                            it.wins++
                        }
                        player?.let {
                            Call.announce(it.con, "${PluginVars.SUCCESS}${I18nManager.get("game.victory", it)} +$winScore${PluginVars.RESET}")
                        }
                    } else {
                        val penalty = max(20, 50 - teamSizes.getOrDefault(team, 1) * 3)
                        DataManager.updatePlayer(acc.id) {
                            it.score = max(0, it.score - penalty)
                        }
                        player?.let {
                            Call.announce(it.con, "${PluginVars.ERROR}${I18nManager.get("game.defeat", it)} -$penalty${PluginVars.RESET}")
                        }
                    }
                }
            } else {
                val winners = Groups.player.copy().select { it.team() == winner }
                val score = max(25, 40 - winners.size * 4)
                winners.each { p ->
                    val acc = DataManager.getPlayerDataByUuid(p.uuid()) ?: return@each
                    DataManager.updatePlayer(acc.id) {
                        it.score += score
                        it.wins++
                    }
                    Call.announce(p.con, "${PluginVars.SUCCESS}${I18nManager.get("game.victory", p)} +$score${PluginVars.RESET}")
                }
            }
        }

        Events.on(EventType.BlockDestroyEvent::class.java) { e ->
            val team = e.tile.team()
            if (e.tile.block() is CoreBlock && team != Team.derelict && team.cores().size <= 1) {
                team.data().players.each { p ->
                    val msgKey = if (Vars.state.rules.mode() == Gamemode.pvp) "core.lost.team" else "core.lost.single"
                    Call.announce(p.con, "${PluginVars.ERROR}${I18nManager.get(msgKey, p)}${PluginVars.RESET}")
                }
            }
        }

        Events.on(EventType.ResetEvent::class.java) {
            PlayerTeamManager.clear()
            RevertBuild.clearAll()
            VoteManager.clearVote()
        }

        Events.on(EventType.PlayEvent::class.java) {
            val currentMap = Vars.state.map
            val name = currentMap.file.name()
            usedMapNames += name

            val pool = Vars.maps.customMaps().filter { it.file.name() !in usedMapNames && it != currentMap }

            if (pool.isEmpty()) {
                usedMapNames.clear()
                usedMapNames += name
                Vars.maps.setNextMapOverride(null)
            } else Vars.maps.setNextMapOverride(pool.random())
        }
    }
}