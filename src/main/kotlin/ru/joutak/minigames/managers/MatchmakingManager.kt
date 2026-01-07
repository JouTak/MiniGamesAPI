package ru.joutak.minigames.managers

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.event.GameInstanceReadyEvent
import ru.joutak.minigames.ui.QueueBossBarManager
import java.util.ArrayDeque

object MatchmakingManager {
    private val activeInstances = mutableListOf<GameInstance>()
    private val readyQueue = ArrayDeque<GameInstance>()

    fun loadInstances(configs: List<GameInstanceConfig>) {
        activeInstances.clear()
        readyQueue.clear()
        activeInstances += configs.map { GameInstance(it) }
        QueueBossBarManager.updateAll()
    }

    fun addPlayer(player: Player) {
        val instance = activeInstances.firstOrNull { !it.started && !it.isFull() } ?: return
        if (instance.addPlayer(player)) {
            checkReady(instance)
        }
    }

    fun removePlayer(player: Player): Boolean {
        var removedFromInstance = false

        for (instance in activeInstances) {
            if (instance.removePlayer(player)) {
                removedFromInstance = true

                // если перестал быть полным — убираем из readyQueue
                if (!instance.isFull()) {
                    readyQueue.remove(instance)
                }

                // если матч стартовал и все вышли — сброс started
                if (instance.teams.all { it.isEmpty() }) {
                    instance.started = false
                }

                break
            }
        }

        val removedFromQueue = GameQueue.removePlayer(player)
        QueueBossBarManager.remove(player)

        if (removedFromInstance || removedFromQueue) {
            QueueBossBarManager.updateAll()
        }

        return removedFromInstance || removedFromQueue
    }

    fun checkReady(instance: GameInstance) {
        if (instance.started) return

        if (instance.isFull() && !readyQueue.contains(instance)) {
            readyQueue.add(instance)
            QueueBossBarManager.updateAll()
            fireReadyEvent(instance)
        }
    }

    fun pollReady(): GameInstance? {
        val instance = readyQueue.poll() ?: return null

        // матч стартовал
        instance.started = true

        // снимаем BossBar у всех игроков инстанса
        instance.teams.flatten()
            .distinctBy { it.uniqueId }
            .forEach { QueueBossBarManager.remove(it) }

        QueueBossBarManager.updateAll()
        return instance
    }

    fun getActiveInstances(): List<GameInstance> = activeInstances.toList()

    fun forceReady(instance: GameInstance) {
        if (instance.started) return
        if (!readyQueue.contains(instance)) {
            readyQueue.add(instance)
            QueueBossBarManager.updateAll()
            fireReadyEvent(instance)
        }
    }

    private fun fireReadyEvent(instance: GameInstance) {
        Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
            Bukkit.getPluginManager().callEvent(GameInstanceReadyEvent(instance))
        })
    }
}
