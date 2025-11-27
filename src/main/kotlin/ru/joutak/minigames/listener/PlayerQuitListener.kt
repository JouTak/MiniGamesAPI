package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.managers.MatchmakingManager

object PlayerQuitListener : Listener {

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        // Удаляем игрока из очереди и из активных инстансов
        val wasRemoved = MatchmakingManager.removePlayer(player)

        if (wasRemoved) {
             MiniGamesCore.plugin.logger.info("Игрок ${player.name} был удален из очереди при выходе")
        }

        // Дополнительная проверка: удаляем из GameQueue, если вдруг остался
        GameQueue.removePlayer(player)
    }
}