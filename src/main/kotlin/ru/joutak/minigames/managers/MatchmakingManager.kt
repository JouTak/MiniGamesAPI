package ru.joutak.minigames.managers

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.GameInstanceConfig
import ru.joutak.minigames.event.GameInstanceReadyEvent
import java.util.ArrayDeque

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

    fun removePlayer(player: Player) {
        for (instance in activeInstances) {
            if (instance.removePlayer(player)) break
        }
    }

    private fun checkReady(instance: GameInstance) {
        if (instance.isFull() && !readyQueue.contains(instance)) {
            readyQueue.add(instance)
            Bukkit.getScheduler().runTask(MiniGamesCore.plugin, Runnable {
                Bukkit.getPluginManager().callEvent(GameInstanceReadyEvent(instance))
            })

        }
    }

    fun pollReady(): GameInstance? = readyQueue.poll()
    fun getActiveInstances(): List<GameInstance> = activeInstances.toList()
}
