package ru.joutak.minigames.managers

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.event.GameInstanceReadyEvent
import ru.joutak.minigames.ui.QueueBossBarManager
import java.util.*

object MatchmakingManager {
    private val activeInstances = mutableListOf<GameInstance>()
    private val readyQueue = ArrayDeque<GameInstance>()

    fun loadInstances(configs: List<GameInstanceConfig>) {
        activeInstances.clear()
        activeInstances += configs.map { GameInstance(it) }
    }

    fun addPlayer(player: Player) {
        val instance = activeInstances.firstOrNull { !it.isFull() } ?: return
        if (instance.addPlayer(player)) {
            checkReady(instance)
        }
    }

    fun removePlayer(player: Player): Boolean {
        for (instance in activeInstances) {
            if (instance.removePlayer(player)) {
                GameQueue.removePlayer(player)
                QueueBossBarManager.updateAll()
                return true
            }
        }

        val removed = GameQueue.removePlayer(player)
        if (removed) QueueBossBarManager.updateAll()
        return removed
    }

    fun checkReady(instance: GameInstance) {
        if (instance.isFull() && !readyQueue.contains(instance)) {
            readyQueue.add(instance)
            QueueBossBarManager.updateAll()
            Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                Bukkit.getPluginManager().callEvent(GameInstanceReadyEvent(instance))
            })

        }
    }

    fun pollReady(): GameInstance? = readyQueue.poll()
    fun getActiveInstances(): List<GameInstance> = activeInstances.toList()
    fun forceReady(instance: GameInstance) {
        if (!readyQueue.contains(instance)) {
            readyQueue.add(instance)
        }
    }

}
