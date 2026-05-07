package ru.joutak.minigames.domain

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.event.GameInstanceEndedEvent
import ru.joutak.minigames.ui.QueueBossBarManager
import java.util.UUID

class GameInstance(val config: GameInstanceConfig) {
    /**
     * Teams list is used ONLY for lobby/waiting phase (team picking / queue filling).
     * Many minigames (e.g. Splatoon) clear these lists on match start.
     */
    val teams = MutableList(config.teamCount) { mutableListOf<Player>() }

    /**
     * Tournament-only mapping: teamIndex -> tournament team_key (for results / roster binding).
     * It is maintained by the tournament auto-matchmaking logic.
     */
    val tournamentTeamKeys: MutableList<String?> = MutableList(config.teamCount) { null }

    /**
     * Round-robin cursor for /ready (and lobby hotbar READY action).
     * It is intentionally per-instance to distribute players across teams evenly.
     */
    private var readyRoundRobinCursor: Int = 0

    /**
     * "Started" means the match has begun and this instance is not available for matchmaking.
     */
    @Volatile
    var started: Boolean = false
        private set

    /**
     * Player UUIDs who are currently participating in the running match.
     * This set is independent from [teams] (because modes may clear teams at start).
     */
    private val activePlayerIds: MutableSet<UUID> = mutableSetOf()

    /**
     * Balanced add (used by generic matchmaking).
     */
    fun addPlayer(player: Player): Boolean {
        if (started) return false

        // Prevent duplicates across teams
        removeFromWaitingTeams(player.uniqueId)

        val availableTeams = teams.filter { it.size < config.playersPerTeam }
        if (availableTeams.isEmpty()) return false

        val targetTeam = availableTeams.minByOrNull { it.size } ?: return false
        targetTeam.add(player)

        QueueBossBarManager.updateAll()
        return true
    }

    /**
     * Picks the next team index using round-robin among teams that still have free slots.
     * Returns null if no teams have free slots.
     */
    fun pickTeamIndexRoundRobin(): Int? {
        if (started) return null
        if (teams.isEmpty()) return null

        val n = teams.size
        for (offset in 0 until n) {
            val idx = (readyRoundRobinCursor + offset) % n
            if (teams[idx].size < config.playersPerTeam) {
                readyRoundRobinCursor = (idx + 1) % n
                return idx
            }
        }
        return null
    }

    /**
     * Add to specific team index (used by /ready and GUI).
     */
    fun addPlayerToTeamIndex(player: Player, teamIndex: Int): Boolean {
        if (started) return false
        if (teamIndex !in teams.indices) return false

        removeFromWaitingTeams(player.uniqueId)

        val team = teams[teamIndex]
        if (team.size >= config.playersPerTeam) return false

        team.add(player)
        QueueBossBarManager.updateAll()
        return true
    }

    /**
     * Removes from waiting teams ONLY.
     * IMPORTANT: does NOT affect [activePlayerIds] when match is started.
     * (Splatoon removes players from [teams] at match start).
     */
    fun removePlayer(player: Player): Boolean {
        val uuid = player.uniqueId
        val removed = removeFromWaitingTeams(uuid)
        if (removed) {
            QueueBossBarManager.updateAll()
        }
        return removed
    }

    fun isFull(): Boolean = !started && teams.all { it.size >= config.playersPerTeam }

    /**
     * Transition instance to started state and snapshot all waiting-team players as active match participants.
     */
    fun startMatchAndSnapshotPlayers(): Set<UUID> {
        started = true
        activePlayerIds.clear()
        teams.flatten().forEach { activePlayerIds.add(it.uniqueId) }
        return activePlayerIds.toSet()
    }

    fun hasActivePlayer(uuid: UUID): Boolean = activePlayerIds.contains(uuid)

    fun getActivePlayerIds(): Set<UUID> = activePlayerIds.toSet()

    /**
     * Remove a player from active match participants (used when a game ends / player leaves).
     * When the last participant leaves, instance becomes available again.
     */
    fun removeActivePlayer(uuid: UUID): Boolean {
        val removed = activePlayerIds.remove(uuid)
        if (removed && activePlayerIds.isEmpty()) {
            started = false
            val instance = this
            // Dispatch on the main thread so listeners (e.g. VoiceChat integration)
            // can safely touch Bukkit/SVC state.
            Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                Bukkit.getPluginManager().callEvent(GameInstanceEndedEvent(instance))
            })
        }
        return removed
    }

    fun hasWaitingPlayer(uuid: UUID): Boolean = teams.any { team -> team.any { it.uniqueId == uuid } }

    private fun removeFromWaitingTeams(uuid: UUID): Boolean {
        var removedAny = false
        for (team in teams) {
            if (team.removeIf { it.uniqueId == uuid }) {
                removedAny = true
            }
        }
        return removedAny
    }

    fun setTournamentTeamKey(teamIndex: Int, teamKey: String?) {
        if (teamIndex !in 0 until tournamentTeamKeys.size) return
        tournamentTeamKeys[teamIndex] = teamKey
    }

    fun getTournamentTeamKey(teamIndex: Int): String? {
        if (teamIndex !in 0 until tournamentTeamKeys.size) return null
        return tournamentTeamKeys[teamIndex]
    }

    /**
     * Clears waiting teams and tournament mapping for reuse (only for non-started instances).
     */
    fun resetTournamentLobbyState() {
        if (started) return
        for (t in teams) t.clear()
        for (i in tournamentTeamKeys.indices) tournamentTeamKeys[i] = null
        readyRoundRobinCursor = 0
    }

}