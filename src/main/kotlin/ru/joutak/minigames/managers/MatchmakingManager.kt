package ru.joutak.minigames.managers

import org.bukkit.Bukkit
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
     * When "start with threshold" is enabled, we keep a per-instance timestamp
     * for the moment it became eligible. If the instance stays eligible for
     * [ConfigKeys.MATCHMAKING_START_DELAY_SECONDS], it will become ready.
     */
    private val eligibleSinceMs = mutableMapOf<GameInstance, Long>()

    /**
     * Scheduled re-check tasks for delayed start.
     */
    private val scheduledRecheckTaskId = mutableMapOf<GameInstance, Int>()

    fun loadInstances(configs: List<GameInstanceConfig>) {
        // Cancel any delayed checks from previous configuration.
        scheduledRecheckTaskId.values.forEach { Bukkit.getScheduler().cancelTask(it) }
        scheduledRecheckTaskId.clear()
        eligibleSinceMs.clear()

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

    private fun shouldBeReady(instance: GameInstance): Boolean {
        // Full instance always starts as before.
        if (instance.isFull()) return true

        val enabled = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_ENABLED)
        if (!enabled) {
            // Old behavior: only full instances.
            clearDelayedState(instance)
            return false
        }

        val maxPlayers = max(1, instance.config.teamCount * instance.config.playersPerTeam)
        val waitingPlayers = instance.teams.sumOf { it.size }

        val percent = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_MIN_FILL_PERCENT)
        val minPlayers = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_MIN_PLAYERS)

        val requiredByPercent = ceil(maxPlayers * percent).toInt()
        val required = min(maxPlayers, max(1, max(minPlayers, requiredByPercent)))

        if (waitingPlayers < required) {
            clearDelayedState(instance)
            return false
        }

        val delaySeconds = MiniGamesCore.configuration.get(ConfigKeys.MATCHMAKING_START_DELAY_SECONDS)
        if (delaySeconds <= 0) {
            return true
        }

        val now = System.currentTimeMillis()
        val since = eligibleSinceMs.getOrPut(instance) { now }
        val readyAt = since + (delaySeconds * 1000L)
        if (now >= readyAt) {
            return true
        }

        // Schedule a one-shot re-check when delay expires.
        scheduleRecheck(instance, readyAt - now)
        return false
    }

    private fun scheduleRecheck(instance: GameInstance, delayMs: Long) {
        if (instance.started) {
            clearDelayedState(instance)
            return
        }

        // Do not schedule if already scheduled.
        if (scheduledRecheckTaskId.containsKey(instance)) return

        val delayTicks = max(1L, (delayMs / 50L))
        val taskId = Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            scheduledRecheckTaskId.remove(instance)
            // Re-check eligibility (might enqueue the instance).
            checkReady(instance)
        }, delayTicks).taskId

        scheduledRecheckTaskId[instance] = taskId
    }

    private fun clearDelayedState(instance: GameInstance) {
        eligibleSinceMs.remove(instance)
        val taskId = scheduledRecheckTaskId.remove(instance)
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId)
        }
    }

    private fun fireReadyEvent(instance: GameInstance) {
        Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
            Bukkit.getPluginManager().callEvent(GameInstanceReadyEvent(instance))
        })
    }
}
