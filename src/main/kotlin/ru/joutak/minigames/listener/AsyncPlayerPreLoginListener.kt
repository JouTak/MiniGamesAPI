package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.tournament.TournamentManager

object AsyncPlayerPreLoginListener : Listener {

    @EventHandler
    fun onAsyncPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (!MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) return
        if (!MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)) return

        val uuid = event.uniqueId
        if (TournamentManager.isBypassUuid(uuid)) return

        val gate = TournamentManager.checkAccess(uuid, event.name)
        if (!gate.allowed) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                TournamentManager.denyKickMessageLegacy(gate.denyReason)
            )
            return
        }

        val teamKey = gate.teamKey?.trim().orEmpty()
        if (teamKey.isEmpty()) return

        TournamentManager.rememberPreLoginTeamKey(uuid, teamKey)

        val reserved = TournamentManager.tryReserveTeamSlot(uuid, teamKey)
        if (!reserved) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST,
                TournamentManager.teamFullOnlineKickMessageLegacy()
            )
        }
    }
}
