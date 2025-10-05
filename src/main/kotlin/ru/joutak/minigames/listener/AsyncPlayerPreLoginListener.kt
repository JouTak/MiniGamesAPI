package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.locale.Message

object AsyncPlayerPreLoginListener : Listener {
    @EventHandler
    fun onAsyncPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (!MiniGamesPlugin.instance.configuration.get(ConfigKeys.SPARTAKIAD_ENABLED)) {
            return
        }

        if (!MiniGamesPlugin.instance
                .spartakiadManager
                .participantsManager
                .contains(event.name)
        ) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_NON_PARTICIPANT)
            return
        }

        val uuid =
            MiniGamesPlugin.instance
                .getSpartakiadManager()
                .getParticipantsManager()
                .get(event.name)

        if (uuid == null) {
            MiniGamesPlugin.instance.logger.severe("Не удалось получить UUID игрока ${event.name} при входе!")
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
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
                    event.name,
                ).join()
                .spartakiadManager
                .playerDataManager

        // MiniGamesPlugin.instance.logger.info(playerData.toString())
        // MiniGamesPlugin.instance.logger.warning(Thread.currentThread().name)

        if (playerData == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_UNEXPECTED_ERROR)
            return
        }

        if (playerData.won) {
            // MiniGamesPlugin.instance.logger.info("${playerData.name} won: ${playerData.won}")
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_WINNER)
            return
        }

        if (playerData.attempts <= 0) {
            // MiniGamesPlugin.instance.logger.info("${playerData.name} attempts: ${playerData.attempts}")
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_NO_ATTEMPTS)
            return
        }
    }
}
