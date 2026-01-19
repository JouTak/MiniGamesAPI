package ru.joutak.minigames.listener

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager

object PlayerJoinListener : Listener {

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player

        Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            if (!player.isOnline) return@Runnable

            val tournamentEnabled = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)
            val strictPrelogin = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)
            val bypassPerm = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION).trim()
            val hasBypassPerm = bypassPerm.isNotBlank() && player.hasPermission(bypassPerm)
            val bypassUuid = TournamentManager.isBypassUuid(player.uniqueId)

            // IMPORTANT: in strict pre-login mode, permission bypass should NOT exclude a real participant
            // from matchmaking, because OP/admin accounts typically have all permissions.
            // Only bypass UUID list works in strict mode.
            var treatAsBypass = bypassUuid

            // Tournament gate for non-strict mode (strict mode handled in AsyncPlayerPreLoginEvent).
            if (tournamentEnabled && !strictPrelogin && !treatAsBypass) {
                // Permission bypass works only after join.
                // If player has bypass permission, we still TRY to treat them as a participant if they are in roster.
                val gate = TournamentManager.checkAccess(player.uniqueId, player.name)
                if (!gate.allowed) {
                    if (!hasBypassPerm) {
                        player.kick(TournamentManager.denyKickMessageComponent(gate.denyReason))
                        return@Runnable
                    }
                    // Admin bypass: allow joining server, but keep them out of tournament matchmaking.
                    treatAsBypass = true
                } else {
                    gate.teamKey?.let { TournamentManager.rememberPreLoginTeamKey(player.uniqueId, it) }
                }
            }

            // Tournament team cap (prevents overfilling a single team on the server).
            if (tournamentEnabled) {
                // In strict mode, permission bypass does not apply (see comment above).
                if (!strictPrelogin && hasBypassPerm) {
                    // If player is NOT a valid participant (no cached teamKey), keep bypass.
                    // If they are a participant, treatAsBypass stays false (gate above cached teamKey).
                    if (TournamentManager.consumePreLoginTeamKey(player.uniqueId) == null &&
                        TournamentManager.resolveTeamKey(player.uniqueId, player.name).isNullOrBlank()
                    ) {
                        treatAsBypass = true
                    }
                }

                if (!treatAsBypass) {
                    var markedOnlineByFinalize = false
                    val teamKey = if (strictPrelogin) {
                        // Strict mode: pre-login should have reserved a slot already.
                        TournamentManager.finalizeJoinFromPending(player.uniqueId)?.also {
                            markedOnlineByFinalize = true
                        } ?: (TournamentManager.consumePreLoginTeamKey(player.uniqueId)
                            ?: TournamentManager.resolveTeamKey(player.uniqueId, player.name))
                    } else {
                        TournamentManager.consumePreLoginTeamKey(player.uniqueId)
                            ?: TournamentManager.resolveTeamKey(player.uniqueId, player.name)
                    }

                    if (teamKey == null || teamKey.isBlank()) {
                        // Should not happen for participants, but don't crash.
                        return@Runnable
                    }

                    // In strict mode, finalizeJoinFromPending(...) already called markOnline when it succeeded.
                    if (!markedOnlineByFinalize) {
                        val maxOnline = MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_MAX_ONLINE_PER_TEAM)
                        val current = TournamentManager.getOnlineCount(teamKey)
                        if (current >= maxOnline) {
                            player.kick(Messages.prefixedComponent("messages.tournament.team_full_online", mapOf("max" to maxOnline.toString())))
                            TournamentManager.clearPreLoginCache(player.uniqueId)
                            TournamentManager.releasePending(player.uniqueId)
                            return@Runnable
                        }

                        TournamentManager.markOnline(player.uniqueId, teamKey)
                    }

                    // Auto matchmaking for tournament: assign teams to instances.
                    MatchmakingManager.rebuildTournamentWaitingAssignments()
                } else {
                    // If bypass is permission-based, pre-login might have reserved a slot.
                    TournamentManager.releasePending(player.uniqueId)
                }
            }
            // Help message
            when {
                MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED) -> {
                    val key = if (TournamentManager.isEloTournamentMode() && Messages.has("messages.join.help_tournament_elo")) {
                        "messages.join.help_tournament_elo"
                    } else {
                        "messages.join.help_tournament"
                    }

                    player.sendMessage(Messages.prefixedComponent(key))
                }
                else -> {
                    player.sendMessage(Messages.prefixedComponent("messages.join.help"))
                }
            }

            val fb = Messages.feedbackLinkComponent()
            if (fb != Component.empty()) {
                player.sendMessage(fb)
            }

            // Show current Elo in lobby (best-effort; requires a recent qualifier snapshot).
            if (tournamentEnabled) {
                val teamKey = TournamentManager.getCachedTeamKey(player.uniqueId)
                if (!teamKey.isNullOrBlank()) {
                    val info = TournamentQualifierManager.getTeamEloInfo(teamKey)
                    if (info != null) {
                        val delta = info.delta
                        val deltaStr = when {
                            delta == null || delta == 0 -> ""
                            delta > 0 -> "+$delta"
                            else -> delta.toString()
                        }
                        val deltaSuffix = if (deltaStr.isBlank()) "" else " (&e$deltaStr&7)"

                        player.sendMessage(
                            Messages.prefixedComponent(
                                "messages.elo.join_rating",
                                mapOf(
                                    "team_key" to teamKey,
                                    "elo" to info.rating.toString(),
                                    "delta_suffix" to deltaSuffix,
                                )
                            )
                        )
                    } else {
                        player.sendMessage(Messages.prefixedComponent("messages.elo.join_no_snapshot", mapOf("team_key" to teamKey)))
                    }
                }
            }
        }, 20)
    }
}
