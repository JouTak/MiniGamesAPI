package ru.joutak.minigames.command.unready

import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.tournament.TournamentManager

object UnreadyCommand {

    fun getBuilder() = Commands.literal("unready")
        .executes { ctx ->
            val sender = ctx.source.sender
            val player = sender as? Player
            if (player == null) {
                sender.sendMessage(Messages.prefixedLegacyString("messages.common.only_players"))
                return@executes 1
            }

            if (MatchmakingManager.isPlayerInStartedGame(player.uniqueId)) {
                // /unready is a lobby command: it must not affect running matches.
                player.sendMessage(Messages.prefixedLegacyString("messages.lobby.command_unavailable"))
                return@executes 1
            }

            if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                if (TournamentManager.isOpenSoloEloMode()) {
                    val removed = MatchmakingManager.removePlayer(player)
                    val key = if (removed) "messages.unready.removed" else "messages.unready.not_in_queue"
                    player.sendMessage(Messages.prefixedComponent(key))
                    return@executes 1
                }

                val res = TournamentManager.clearForceReady(player.uniqueId, player.name)
                if (!res.allowed) {
                    val key = when (res.reason) {
                        TournamentManager.ForceReadyDenyReason.NOT_PARTICIPANT -> "messages.tournament.not_participant"
                        TournamentManager.ForceReadyDenyReason.ONLY_CAPTAIN -> "messages.tournament.only_captain"
                        TournamentManager.ForceReadyDenyReason.ERROR, null -> "messages.tournament.error"
                    }
                    player.sendMessage(Messages.prefixedComponent(key))
                    return@executes 1
                }

                val msg = if (res.changed) "messages.tournament.force_ready_cleared" else "messages.tournament.force_ready_already_cleared"
                player.sendMessage(Messages.prefixedComponent(msg))

                MatchmakingManager.rebuildTournamentWaitingAssignments()
                return@executes 1
            }

            val removed = MatchmakingManager.removePlayer(player)
            if (removed) {
                player.sendMessage(Messages.prefixedLegacyString("messages.unready.removed"))
            } else {
                player.sendMessage(Messages.prefixedLegacyString("messages.unready.not_in_queue"))
            }
            1
        }
}
