package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.ui.QueueBossBarManager
import ru.joutak.minigames.tournament.TournamentManager

object PlayerQuitListener : Listener {

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        val wasRemoved = MatchmakingManager.removePlayer(player)
        if (wasRemoved) {
            MiniGamesCore.plugin.logger.info("Игрок ${player.name} был удален из очереди при выходе")
        }

        // На всякий случай гарантированно скрываем бар при выходе
        QueueBossBarManager.remove(player)

        // Tournament: free online slot for the team
        TournamentManager.unregisterOnline(player.uniqueId)
    }
}