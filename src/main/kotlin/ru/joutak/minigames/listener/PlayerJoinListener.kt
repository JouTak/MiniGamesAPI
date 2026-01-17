package ru.joutak.minigames.listener

import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.tournament.TournamentManager

object PlayerJoinListener : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            if (!player.isOnline) return@Runnable

            // Tournament gate for non-strict mode (strict mode handled in AsyncPlayerPreLoginEvent).
            if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED) &&
                !MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)
            ) {
                // Permission bypass works only after join.
                val bypassPerm = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION)
                val bypass = player.hasPermission(bypassPerm)

                if (!bypass) {
                    val gate = TournamentManager.checkAccess(player.uniqueId, player.name)
                    if (!gate.allowed) {
                        player.kick(TournamentManager.denyKickMessageComponent(gate.denyReason))
                        return@Runnable
                    }

                    gate.teamKey?.let { TournamentManager.rememberPreLoginTeamKey(player.uniqueId, it) }
                }
            }

            // Tournament team cap (prevents overfilling a single team on the server).
            if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                val bypassPerm = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION)
                val bypass = player.hasPermission(bypassPerm) || TournamentManager.isBypassUuid(player.uniqueId)

                if (!bypass) {
                    val teamKey = TournamentManager.consumePreLoginTeamKey(player.uniqueId)
                        ?: TournamentManager.resolveTeamKey(player.uniqueId, player.name)

                    if (teamKey == null || teamKey.isBlank()) {
                        // Should not happen for participants, but don't crash.
                        return@Runnable
                    }

                    val maxOnline = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_MAX_ONLINE_PER_TEAM)
                    val current = TournamentManager.getOnlineCount(teamKey)
                    if (current >= maxOnline) {
                        player.kick(Messages.prefixedComponent("messages.tournament.team_full_online", mapOf("max" to maxOnline.toString())))
                        TournamentManager.clearPreLoginCache(player.uniqueId)
                        return@Runnable
                    }

                    TournamentManager.markOnline(player.uniqueId, teamKey)

                    // Auto matchmaking for tournament: assign teams to instances.
                    MatchmakingManager.rebuildTournamentWaitingAssignments()
                }
            }
            // Help message
            when {
                MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED) -> {
                    player.sendMessage(Messages.prefixedComponent("messages.join.help_tournament"))
                }
                else -> {
                    player.sendMessage(Messages.prefixedComponent("messages.join.help"))
                }
            }
        }, 20)
    }
}
