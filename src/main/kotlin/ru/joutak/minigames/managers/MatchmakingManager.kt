package ru.joutak.minigames.managers

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.event.GameInstanceReadyEvent
import ru.joutak.minigames.lobby.LobbyItemsManager
import ru.joutak.minigames.ui.QueueBossBarManager
import ru.joutak.minigames.ui.LobbyScoreboardManager
import ru.joutak.minigames.tournament.TournamentManager
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object MatchmakingManager {
    private val activeInstances = mutableListOf<GameInstance>()
    private val readyQueue = ArrayDeque<GameInstance>()

    /**
     * Instances explicitly forced to start by admin command.
     * Such instances should stay in the ready queue even if normal readiness rules would remove them.
     */
    private val forcedReadyInstances = mutableSetOf<GameInstance>()

    /**
     * When "start with threshold" is enabled and delay > 0, we keep a per-instance timestamp
     * for the moment it became eligible. If the instance stays eligible for
     * [ConfigKeys.MATCHMAKING_START_DELAY_SECONDS], it will become ready.
     */
    private val eligibleSinceMs = mutableMapOf<GameInstance, Long>()

    /**
     * Scheduled per-instance countdown/check tasks (1s interval) for delayed start.
     */
    private val scheduledCountdownTaskId = mutableMapOf<GameInstance, Int>()

    /**
     * Last announced remaining seconds (to avoid duplicate spam).
     */
    private val lastAnnouncedSeconds = mutableMapOf<GameInstance, Int>()

    private data class PartialStartInfo(
        val enabled: Boolean,
        val maxPlayers: Int,
        val waitingPlayers: Int,
        val requiredPlayers: Int,
        val waitingTeams: Int,
        val requiredTeams: Int,
        val delaySeconds: Int
    ) {
        val eligible: Boolean get() =
            waitingPlayers >= requiredPlayers && waitingTeams >= requiredTeams
    }

    fun loadInstances(configs: List<GameInstanceConfig>) {
        // Cancel any delayed checks from previous configuration.
        scheduledCountdownTaskId.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        scheduledCountdownTaskId.clear()
        eligibleSinceMs.clear()
        lastAnnouncedSeconds.clear()

        activeInstances.clear()
        readyQueue.clear()
        forcedReadyInstances.clear()

        val globalPoolSize = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_INSTANCE_POOL_SIZE)
            .coerceAtLeast(1)

        // Expand each configured instance into a pool of identical instances.
        // This allows running multiple matches in parallel even if the mode provides only one map config.
        // Per-config override (optional): meta["pool_size"] = Int
        for (cfg in configs) {
            val perConfig = ((cfg.meta["pool_size"] as? Number)?.toInt() ?: globalPoolSize).coerceAtLeast(1)
            repeat(perConfig) {
                activeInstances += GameInstance(cfg)
            }
        }

        QueueBossBarManager.updateAll()
        LobbyScoreboardManager.updateAll()
    }

    fun getActiveInstances(): List<GameInstance> = activeInstances.toList()

    fun isPlayerInStartedGame(uuid: UUID): Boolean {
        return activeInstances.any { it.started && it.hasActivePlayer(uuid) }
    }

    fun isPlayerInAnyInstance(uuid: UUID): Boolean {
        return activeInstances.any { it.hasWaitingPlayer(uuid) || (it.started && it.hasActivePlayer(uuid)) }
    }

    fun addPlayer(player: Player) {
        val instance = activeInstances.firstOrNull { !it.started && !it.isFull() } ?: return
        if (instance.addPlayer(player)) {
            checkReady(instance)
        }
    }

    /**
     * Removes player from:
     * - started match participants
     * - waiting teams
     * - selection queue
     * - queue bossbar
     */
    fun removePlayer(player: Player): Boolean {
        val uuid = player.uniqueId
        var removedFromInstance = false
        var removedFromActiveMatch = false

        for (instance in activeInstances) {
            // If player is in a started match, remove from active participants.
            if (instance.started && instance.hasActivePlayer(uuid)) {
                if (instance.removeActivePlayer(uuid)) {
                    removedFromInstance = true
                    removedFromActiveMatch = true
                }

                // If it's a started instance, it's not eligible for ready queue.
                readyQueue.remove(instance)
                forcedReadyInstances.remove(instance)

                // Don't break: player might also still be present in waiting teams in buggy situations.
            }

            // Remove from waiting teams (pre-game)
            if (!instance.started && instance.removePlayer(player)) {
                removedFromInstance = true

                // Recompute readiness (supports partial-start thresholds).
                checkReady(instance)

                // Player cannot be in multiple instances.
                break
            }
        }

        val removedFromQueue = GameQueue.removePlayer(player)
        QueueBossBarManager.remove(player)

        if (removedFromInstance || removedFromQueue) {
            QueueBossBarManager.updateAll()
            LobbyScoreboardManager.updateAll()
        }

        if (removedFromInstance && MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
            Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                rebuildTournamentWaitingAssignments()
            })
        }

        // If player left a running match, restore lobby items (after other plugins clean inventory).
        if (removedFromActiveMatch && player.isOnline && !isPlayerInStartedGame(uuid)) {
            Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
                if (player.isOnline) {
                    LobbyItemsManager.ensure(player)
                }
            }, 1L)
        }

        return removedFromInstance || removedFromQueue
    }

    fun checkReady(instance: GameInstance) {
        if (instance.started) return

        // Forced instances should remain in the ready queue regardless of normal readiness rules.
        if (forcedReadyInstances.contains(instance)) {
            val waitingPlayers = instance.teams.sumOf { it.size }
            if (waitingPlayers <= 0) {
                // Do not keep empty forced instances around.
                forcedReadyInstances.remove(instance)
                if (readyQueue.remove(instance)) {
                    QueueBossBarManager.updateAll()
                    LobbyScoreboardManager.updateAll()
                }
                clearDelayedState(instance)
                return
            }

            if (!readyQueue.contains(instance)) {
                readyQueue.add(instance)
                QueueBossBarManager.updateAll()
                LobbyScoreboardManager.updateAll()
                fireReadyEvent(instance)
            }
            clearDelayedState(instance)
            return
        }

        if (shouldBeReady(instance)) {
            if (!readyQueue.contains(instance)) {
                // Announce "not full but will start" loudly in chat (like CreakyWars), if applicable.
                // Tournament has its own readiness logic and does not use the generic partial-start announcer.
                if (!MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                    maybeAnnounceReady(instance)
                }

                readyQueue.add(instance)
                QueueBossBarManager.updateAll()
                LobbyScoreboardManager.updateAll()
                fireReadyEvent(instance)
            }
            clearDelayedState(instance)
        } else {
            // If instance is not eligible anymore, remove it from ready queue.
            if (readyQueue.remove(instance)) {
                QueueBossBarManager.updateAll()
                LobbyScoreboardManager.updateAll()
            }
        }
    }

    /**
     * Marks next ready instance as started and returns it to the minigame.
     * IMPORTANT: snapshots players as active match participants, because some minigames clear teams at start.
     */
    fun pollReady(): GameInstance? {
        val instance = readyQueue.poll() ?: return null

        forcedReadyInstances.remove(instance)

        clearDelayedState(instance)

        val activeIds = instance.startMatchAndSnapshotPlayers()

        // Remove queue BossBars and lobby items from all match participants.
        activeIds.mapNotNull { Bukkit.getPlayer(it) }
            .forEach {
                QueueBossBarManager.remove(it)
                LobbyItemsManager.remove(it)
            }

        QueueBossBarManager.updateAll()
        LobbyScoreboardManager.updateAll()

        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
            // Move remaining teams into the next available instances.
            rebuildTournamentWaitingAssignments()
        }

        return instance
    }

    /**
     * Tournament mode: rebuild waiting assignments by tournament team_key.
     *
     * Priority: picks the most filled eligible teams first.
     * A team becomes eligible for a partial roster only after its captain confirms readiness via /forceready.
     */
    fun rebuildTournamentWaitingAssignments() {
        if (!MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) return

        val waitingInstances = activeInstances.filter { !it.started }
        if (waitingInstances.isEmpty()) return

        // Do not reshuffle instances that are already in ready queue (they are about to be started).
        val lockedInstances = HashSet<GameInstance>()
        lockedInstances.addAll(readyQueue)
        lockedInstances.addAll(forcedReadyInstances)

        val rebuildInstances = waitingInstances.filter { !lockedInstances.contains(it) }

        val lockedPlayerIds = lockedInstances
            .flatMap { it.teams.flatten() }
            .map { it.uniqueId }
            .toHashSet()

        val lockedTeamKeys = lockedInstances
            .flatMap { it.tournamentTeamKeys }
            .filterNotNull()
            .toHashSet()

        if (rebuildInstances.isEmpty()) {
            // Still re-check locked instances to react to /forceready toggles.
            lockedInstances.forEach { checkReady(it) }
            QueueBossBarManager.updateAll()
            LobbyScoreboardManager.updateAll()
            return
        }

        data class TeamEntry(
            val key: String,
            val members: List<Player>,
            val size: Int,
            val forceReady: Boolean,
        )

        // Group online lobby players by tournament team key, excluding running matches.
        val lobbyPlayers = Bukkit.getOnlinePlayers()
            .filter { it.isOnline && !isPlayerInStartedGame(it.uniqueId) && !lockedPlayerIds.contains(it.uniqueId) }

        val byTeam = linkedMapOf<String, MutableList<Player>>()
        for (p in lobbyPlayers) {
            // IMPORTANT: do not exclude OP/admin participants from tournament matchmaking.
            // Bypass is controlled only via explicit UUID list (strict mode-safe).
            if (TournamentManager.isBypassUuid(p.uniqueId)) continue

            val teamKey = TournamentManager.getCachedTeamKey(p.uniqueId) ?: continue
            if (lockedTeamKeys.contains(teamKey)) continue
            byTeam.getOrPut(teamKey) { mutableListOf() }.add(p)

            // Tournament does not use the old ready queue / selection queue.
            GameQueue.removePlayer(p)
        }

        for (list in byTeam.values) {
            list.sortBy { it.name.lowercase() }
        }

        val allTeams = byTeam.entries
            .map { e ->
                val members = e.value
                members.sortBy { it.name.lowercase() }
                TeamEntry(
                    key = e.key,
                    members = members.toList(),
                    size = members.size,
                    forceReady = TournamentManager.isTeamForceReady(e.key),
                )
            }
            .sortedWith(compareByDescending<TeamEntry> { it.size }.thenBy { it.key })

        // Reset only instances we are allowed to rebuild.
        rebuildInstances.forEach { it.resetTournamentLobbyState() }

        val primaryCfg = rebuildInstances.first().config
        val teamCount = primaryCfg.teamCount
        val playersPerTeam = primaryCfg.playersPerTeam

        val eligibleTeams = allTeams.filter { it.size >= playersPerTeam || (it.forceReady && it.size > 0) }
        val matchableInstances = rebuildInstances.filter { it.config.teamCount == teamCount && it.config.playersPerTeam == playersPerTeam }
        val possibleMatches = min(matchableInstances.size, eligibleTeams.size / teamCount)

        val usedKeys = HashSet<String>()

        // 1) Fill instances that should become ready immediately.
        var ptr = 0
        for (i in 0 until possibleMatches) {
            val inst = matchableInstances[i]
            for (teamIndex in 0 until inst.config.teamCount) {
                val t = eligibleTeams[ptr++]
                usedKeys.add(t.key)
                inst.setTournamentTeamKey(teamIndex, t.key)
                inst.teams[teamIndex].addAll(t.members.take(inst.config.playersPerTeam))
            }
        }

        // 2) Fill the rest (for waiting UI) with remaining teams by size.
        val remainingTeams = allTeams.filter { !usedKeys.contains(it.key) }
        var remPtr = 0
        for (inst in rebuildInstances) {
            if (inst.tournamentTeamKeys.any { it != null }) continue // already filled as a match

            for (teamIndex in 0 until inst.config.teamCount) {
                if (remPtr >= remainingTeams.size) break
                val t = remainingTeams[remPtr++]
                if (t.members.isEmpty()) continue

                inst.setTournamentTeamKey(teamIndex, t.key)
                inst.teams[teamIndex].addAll(t.members.take(inst.config.playersPerTeam))
            }
        }

        // Re-check readiness for ALL waiting instances (locked are not reshuffled, but may change ready state).
        waitingInstances.forEach { checkReady(it) }

        QueueBossBarManager.updateAll()
        LobbyScoreboardManager.updateAll()
    }

    fun forceReady(instance: GameInstance) {
        if (instance.started) return
        forcedReadyInstances.add(instance)
        clearDelayedState(instance)
        if (!readyQueue.contains(instance)) {
            readyQueue.add(instance)
            QueueBossBarManager.updateAll()
            LobbyScoreboardManager.updateAll()
            fireReadyEvent(instance)
        }
    }

    private fun getPartialStartInfo(instance: GameInstance): PartialStartInfo {
        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ENABLED)
        val maxPlayers = max(1, instance.config.teamCount * instance.config.playersPerTeam)
        val waitingPlayers = instance.teams.sumOf { it.size }
        val waitingTeams = instance.teams.count { it.isNotEmpty() }

        val percent = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_MIN_FILL_PERCENT)
        val requiredByPercent = ceil(maxPlayers * percent).toInt()
        val requiredPlayers = min(maxPlayers, max(1, requiredByPercent))

        val cfgMinTeams = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_MIN_TEAMS)
        val requiredTeams = min(instance.config.teamCount, max(1, cfgMinTeams))

        val delaySeconds = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_DELAY_SECONDS)

        return PartialStartInfo(
            enabled = enabled,
            maxPlayers = maxPlayers,
            waitingPlayers = waitingPlayers,
            requiredPlayers = requiredPlayers,
            waitingTeams = waitingTeams,
            requiredTeams = requiredTeams,
            delaySeconds = delaySeconds
        )
    }

    private fun shouldBeReady(instance: GameInstance): Boolean {
        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
            clearDelayedState(instance)
            return isTournamentReady(instance)
        }

        // Full instance always starts as before.
        if (instance.isFull()) return true

        val info = getPartialStartInfo(instance)

        if (!info.enabled) {
            // Old behavior: only full instances.
            clearDelayedState(instance)
            return false
        }

        if (!info.eligible) {
            // Cancel countdown if it was running.
            if (eligibleSinceMs.containsKey(instance)) {
                maybeAnnounceCancelled(instance)
            }
            clearDelayedState(instance)
            return false
        }

        // Eligible. If no delay -> ready immediately.
        if (info.delaySeconds <= 0) {
            return true
        }

        val now = System.currentTimeMillis()
        val since = eligibleSinceMs.getOrPut(instance) {
            // Countdown starts now.
            now.also {
                // First announcement immediately.
                announceCountdown(instance, info, remainingSeconds = info.delaySeconds, force = true)
            }
        }

        val readyAt = since + (info.delaySeconds * 1000L)
        if (now >= readyAt) {
            return true
        }

        // Ensure periodic countdown + re-check.
        ensureCountdownTask(instance)
        return false
    }

    private fun isTournamentReady(instance: GameInstance): Boolean {
        if (instance.started) return false
        if (instance.teams.isEmpty()) return false

        // Tournament instance is ready when ALL team slots are ready:
        // - team is full, OR
        // - team is marked /forceready and has at least 1 online member.
        val requiredTeams = instance.config.teamCount
        val playersPerTeam = instance.config.playersPerTeam

        for (teamIndex in 0 until requiredTeams) {
            val teamKey = instance.getTournamentTeamKey(teamIndex) ?: return false
            val size = instance.teams.getOrNull(teamIndex)?.size ?: 0
            if (size >= playersPerTeam) continue
            if (size <= 0) return false
            if (!TournamentManager.isTeamForceReady(teamKey)) return false
        }

        return true
    }

    private fun ensureCountdownTask(instance: GameInstance) {
        if (instance.started) {
            clearDelayedState(instance)
            return
        }

        if (scheduledCountdownTaskId.containsKey(instance)) return

        val taskId = Bukkit.getScheduler().runTaskTimer(MiniGamesCore.plugin, Runnable {
            if (instance.started) {
                clearDelayedState(instance)
                return@Runnable
            }

            // Announce countdown if needed.
            val info = getPartialStartInfo(instance)
            if (!instance.isFull() && info.enabled && info.delaySeconds > 0 && info.eligible) {
                val since = eligibleSinceMs[instance]
                if (since != null) {
                    val readyAt = since + (info.delaySeconds * 1000L)
                    val now = System.currentTimeMillis()
                    val remaining = ceil((readyAt - now).toDouble() / 1000.0).toInt().coerceAtLeast(0)
                    if (remaining > 0) {
                        announceCountdown(instance, info, remainingSeconds = remaining, force = false)
                    }
                }
            }

            // Re-check eligibility / enqueue.
            checkReady(instance)
        }, 20L, 20L).taskId

        scheduledCountdownTaskId[instance] = taskId
    }

    private fun clearDelayedState(instance: GameInstance) {
        eligibleSinceMs.remove(instance)
        lastAnnouncedSeconds.remove(instance)

        val taskId = scheduledCountdownTaskId.remove(instance)
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId)
        }
    }

    private fun announceCountdown(instance: GameInstance, info: PartialStartInfo, remainingSeconds: Int, force: Boolean) {
        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_ENABLED)
        if (!enabled) return

        val interval = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_INTERVAL_SECONDS)
        val lastSeconds = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_LAST_SECONDS_ALWAYS)

        val prev = lastAnnouncedSeconds[instance]
        if (!force && prev == remainingSeconds) return

        val shouldAnnounce = force || remainingSeconds <= lastSeconds || (remainingSeconds % interval == 0)
        if (!shouldAnnounce) return

        lastAnnouncedSeconds[instance] = remainingSeconds

        val placeholders = mapOf(
            "seconds" to remainingSeconds.toString(),
            "current" to info.waitingPlayers.toString(),
            "max" to info.maxPlayers.toString(),
            "required" to info.requiredPlayers.toString(),
            "teams_current" to info.waitingTeams.toString(),
            "teams_required" to info.requiredTeams.toString()
        )

        val fallback = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_MESSAGE)
        val msg = buildPrefixedMessage(
            path = "messages.matchmaking.start.announce.message",
            fallbackTemplate = fallback,
            placeholders = placeholders
        )

        broadcastToLobbyPlayers(msg)
    }

    private fun maybeAnnounceCancelled(instance: GameInstance) {
        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_ENABLED)
        if (!enabled) return

        val info = getPartialStartInfo(instance)
        val placeholders = mapOf(
            "current" to info.waitingPlayers.toString(),
            "max" to info.maxPlayers.toString(),
            "required" to info.requiredPlayers.toString(),
            "teams_current" to info.waitingTeams.toString(),
            "teams_required" to info.requiredTeams.toString()
        )

        val fallback = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_CANCELLED_MESSAGE)
        val msg = buildPrefixedMessage(
            path = "messages.matchmaking.start.announce.cancelled_message",
            fallbackTemplate = fallback,
            placeholders = placeholders
        )

        broadcastToLobbyPlayers(msg)
    }

    private fun maybeAnnounceReady(instance: GameInstance) {
        if (instance.isFull()) return

        val info = getPartialStartInfo(instance)
        if (!info.enabled) return
        if (!info.eligible) return

        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_ENABLED)
        if (!enabled) return

        val placeholders = mapOf(
            "current" to info.waitingPlayers.toString(),
            "max" to info.maxPlayers.toString(),
            "required" to info.requiredPlayers.toString(),
            "teams_current" to info.waitingTeams.toString(),
            "teams_required" to info.requiredTeams.toString()
        )

        val fallback = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_READY_MESSAGE)
        val msg = buildPrefixedMessage(
            path = "messages.matchmaking.start.announce.ready_message",
            fallbackTemplate = fallback,
            placeholders = placeholders
        )

        broadcastToLobbyPlayers(msg)
    }

    private fun broadcastToLobbyPlayers(message: String) {
        // Broadcast to everyone who is in lobby (i.e., not in started games).
        Bukkit.getOnlinePlayers().forEach { p ->
            if (!isPlayerInStartedGame(p.uniqueId)) {
                p.sendMessage(message)
            }
        }
    }

    private fun buildPrefixedMessage(path: String, fallbackTemplate: String, placeholders: Map<String, String>): String {
        // Prefer messages.yml (supports prefix + placeholders).
        if (Messages.has(path)) {
            return Messages.prefixedLegacyString(path, placeholders)
        }

        // Backward compatibility: old configs stored templates in config.yml.
        val body = formatMessage(fallbackTemplate, placeholders)
        val prefix = Messages.legacyString("messages.prefix")
        return prefix + body
    }

    private fun formatMessage(template: String, placeholders: Map<String, String>): String {
        var msg = template
        placeholders.forEach { (k, v) ->
            msg = msg.replace("{$k}", v)
        }
        return ChatColor.translateAlternateColorCodes('&', msg)
    }

    private fun fireReadyEvent(instance: GameInstance) {
        Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
            Bukkit.getPluginManager().callEvent(GameInstanceReadyEvent(instance))
        })
    }
}
