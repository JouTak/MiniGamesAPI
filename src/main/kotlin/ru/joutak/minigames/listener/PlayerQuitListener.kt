package ru.joutak.minigames.listener

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.minigames.ui.QueueBossBarManager

object PlayerQuitListener : Listener {

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player

        val wasRemoved = MatchmakingManager.removePlayer(player)
        if (wasRemoved) {
            MiniGamesCore.plugin.logger.info("Игрок ${player.name} был удален из очереди при выходе")
        }

        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
            TournamentManager.unmarkOnline(player.uniqueId)
            TournamentManager.clearPreLoginCache(player.uniqueId)

            // Delay to ensure Bukkit online player list is already updated.
            Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
                MatchmakingManager.rebuildTournamentWaitingAssignments()
            }, 1L)
        }

        // На всякий случай гарантированно скрываем бар при выходе
        QueueBossBarManager.remove(player)
    }
}
