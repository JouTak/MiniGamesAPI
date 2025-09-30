package ru.joutak.minigames.listener

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.locale.Message
import ru.joutak.minigames.spartakiad.SpartakiadManager

object PlayerLoginListener : Listener {
    @EventHandler
    fun onPlayerLogin(event: PlayerLoginEvent) {
        if (!MiniGamesPlugin.instance.getConfiguration().get(ConfigKeys.SPARTAKIAD_ENABLED)) {
            return
        }

        if (!MiniGamesPlugin.instance
                .getSpartakiadManager()
                .getParticipantsManager()
                .contains(event.player.name)
        ) {
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, Message.KICK_NON_PARTICIPANT)
            return
        }

        val uuid =
            MiniGamesPlugin.instance
                .getSpartakiadManager()
                .getParticipantsManager()
                .get(event.player.name)

        if (uuid == null) {
            MiniGamesPlugin.instance.logger.severe("Не удалось получить UUID игрока ${event.player.name} при входе!")
            event.disallow(
                PlayerLoginEvent.Result.KICK_OTHER,
                Message.KICK_UNEXPECTED_ERROR,
            )
            return
        }

        val playerData =
            MiniGamesPlugin.instance
                .getSpartakiadManager()
                .getPlayerDataManager()
                .getPlayerData(
                    uuid,
                    event.player.name,
                ).join()

        // MiniGamesPlugin.instance.logger.info(playerData.toString())

        if (playerData.won) {
            // MiniGamesPlugin.instance.logger.info("${playerData.name} won: ${playerData.won}")
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, Message.KICK_WINNER)
            return
        }

        if (playerData.attempts <= 0) {
            // MiniGamesPlugin.instance.logger.info("${playerData.name} attempts: ${playerData.attempts}")
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, Message.KICK_NO_ATTEMPTS)
            return
        }
    }
}
