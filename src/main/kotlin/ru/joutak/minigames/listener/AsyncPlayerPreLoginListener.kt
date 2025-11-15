package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.dto.PlayerDto
import ru.joutak.minigames.locale.Message

object AsyncPlayerPreLoginListener : Listener {
    @EventHandler
    fun onAsyncPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (!MiniGamesCore.configuration.get(ConfigKeys.SPARTAKIAD_ENABLED)) {
            return
        }

        if (!MiniGamesCore.spartakiadManager
                .whitelistManager
                .contains(PlayerDto(event.name))
        ) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_NON_PARTICIPANT)
            return
        }

        val participant =
            MiniGamesCore
                .spartakiadManager
                .participantManager
                .createIfNotExists(event.name)
                .exceptionally { t ->
                    MiniGamesCore.plugin.logger.severe("Не удалось получить данные об участнике: ${t.message}")
                    return@exceptionally null
                }.join()

        // MiniGamesCore.plugin.logger.info(playerData.toString())
        // MiniGamesCore.plugin.logger.warning(Thread.currentThread().name)

        if (participant == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_UNEXPECTED_ERROR)
            return
        }

        if (participant.won) {
            // MiniGamesCore.plugin.logger.info("${playerData.name} won: ${playerData.won}")
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_WINNER)
            return
        }

        if (participant.attempts <= 0) {
            // MiniGamesCore.plugin.logger.info("${playerData.name} attempts: ${playerData.attempts}")
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_NO_ATTEMPTS)
            return
        }
    }
}

