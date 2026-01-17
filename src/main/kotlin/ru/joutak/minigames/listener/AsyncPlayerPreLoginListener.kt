package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.dto.PlayerDto
import ru.joutak.minigames.locale.Message
import ru.joutak.minigames.tournament.TournamentManager

object AsyncPlayerPreLoginListener : Listener {

    @EventHandler
    fun onAsyncPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        // Tournament gate (new Spartakiad).
        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED) &&
            MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)
        ) {
            val uuid = event.uniqueId

            if (!TournamentManager.isBypassUuid(uuid)) {
                val gate = TournamentManager.checkAccess(uuid, event.name)
                if (!gate.allowed) {
                    event.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                        TournamentManager.denyKickMessageLegacy(gate.denyReason)
                    )
                    return
                }

                val teamKey = gate.teamKey
                if (teamKey != null) {
                    val reserved = TournamentManager.tryReserveTeamSlot(uuid, teamKey)
                    if (!reserved) {
                        event.disallow(
                            AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                            TournamentManager.teamFullOnlineKickMessageLegacy()
                        )
                        return
                    }
                }
            }
        }

        // Legacy spartakiad gate (kept for backward compatibility).
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

        if (participant == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_UNEXPECTED_ERROR)
            return
        }

        if (participant.won) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_WINNER)
            return
        }

        if (participant.attempts <= 0) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, Message.KICK_NO_ATTEMPTS)
            return
        }
    }
}
