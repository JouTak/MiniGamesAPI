package ru.joutak.minigames.managers

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.event.GameInstanceReadyEvent
import ru.joutak.minigames.lobby.LobbyItemsManager
import ru.joutak.minigames.ui.QueueBossBarManager
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

object MatchmakingManager {
    private val activeInstances = mutableListOf<GameInstance>()
    private val readyQueue = ArrayDeque<GameInstance>()

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
        val delaySeconds: Int
    ) {
        val eligible: Boolean get() = waitingPlayers >= requiredPlayers
    }

    fun loadInstances(configs: List<GameInstanceConfig>) {
        // Cancel any delayed checks from previous configuration.
        scheduledCountdownTaskId.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        scheduledCountdownTaskId.clear()
        eligibleSinceMs.clear()
        lastAnnouncedSeconds.clear()

        activeInstances.clear()
        readyQueue.clear()
        activeInstances += configs.map { GameInstance(it) }
        QueueBossBarManager.updateAll()
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

        if (shouldBeReady(instance)) {
            if (!readyQueue.contains(instance)) {
                // Announce "not full but will start" loudly in chat (like CreakyWars), if applicable.
                maybeAnnounceReady(instance)

                readyQueue.add(instance)
                QueueBossBarManager.updateAll()
                fireReadyEvent(instance)
            }
            clearDelayedState(instance)
        } else {
            // If instance is not eligible anymore, remove it from ready queue.
            if (readyQueue.remove(instance)) {
                QueueBossBarManager.updateAll()
            }
        }
    }

    /**
     * Marks next ready instance as started and returns it to the minigame.
     * IMPORTANT: snapshots players as active match participants, because some minigames clear teams at start.
     */
    fun pollReady(): GameInstance? {
        val instance = readyQueue.poll() ?: return null

        clearDelayedState(instance)

        val activeIds = instance.startMatchAndSnapshotPlayers()

        // Remove queue BossBars and lobby items from all match participants.
        activeIds.mapNotNull { Bukkit.getPlayer(it) }
            .forEach {
                QueueBossBarManager.remove(it)
                LobbyItemsManager.remove(it)
            }

        QueueBossBarManager.updateAll()
        return instance
    }

    fun forceReady(instance: GameInstance) {
        if (instance.started) return
        if (!readyQueue.contains(instance)) {
            readyQueue.add(instance)
            QueueBossBarManager.updateAll()
            fireReadyEvent(instance)
        }
    }

    private fun getPartialStartInfo(instance: GameInstance): PartialStartInfo {
        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ENABLED)
        val maxPlayers = max(1, instance.config.teamCount * instance.config.playersPerTeam)
        val waitingPlayers = instance.teams.sumOf { it.size }

        val percent = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_MIN_FILL_PERCENT)
        val minPlayers = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_MIN_PLAYERS)
        val requiredByPercent = ceil(maxPlayers * percent).toInt()
        val required = min(maxPlayers, max(1, max(minPlayers, requiredByPercent)))

        val delaySeconds = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_DELAY_SECONDS)

        return PartialStartInfo(
            enabled = enabled,
            maxPlayers = maxPlayers,
            waitingPlayers = waitingPlayers,
            requiredPlayers = required,
            delaySeconds = delaySeconds
        )
    }

    private fun shouldBeReady(instance: GameInstance): Boolean {
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

        val template = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_MESSAGE)
        val msg = formatMessage(
            template,
            mapOf(
                "seconds" to remainingSeconds.toString(),
                "current" to info.waitingPlayers.toString(),
                "max" to info.maxPlayers.toString(),
                "required" to info.requiredPlayers.toString()
            )
        )

        broadcastToWaitingPlayers(instance, msg)
    }

    private fun maybeAnnounceCancelled(instance: GameInstance) {
        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_ENABLED)
        if (!enabled) return

        val template = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_CANCELLED_MESSAGE)
        val info = getPartialStartInfo(instance)
        val msg = formatMessage(
            template,
            mapOf(
                "current" to info.waitingPlayers.toString(),
                "max" to info.maxPlayers.toString(),
                "required" to info.requiredPlayers.toString()
            )
        )

        broadcastToWaitingPlayers(instance, msg)
    }

    private fun maybeAnnounceReady(instance: GameInstance) {
        if (instance.isFull()) return

        val info = getPartialStartInfo(instance)
        if (!info.enabled) return
        if (!info.eligible) return

        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_ENABLED)
        if (!enabled) return

        val template = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ANNOUNCE_READY_MESSAGE)
        val msg = formatMessage(
            template,
            mapOf(
                "current" to info.waitingPlayers.toString(),
                "max" to info.maxPlayers.toString(),
                "required" to info.requiredPlayers.toString()
            )
        )

        broadcastToWaitingPlayers(instance, msg)
    }

    private fun broadcastToWaitingPlayers(instance: GameInstance, message: String) {
        // Only waiting players (pre-game). No global broadcast.
        val unique = HashMap<UUID, Player>()
        instance.teams.forEach { team ->
            team.forEach { p ->
                if (p.isOnline) unique[p.uniqueId] = p
            }
        }
        unique.values.forEach { it.sendMessage(message) }
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
