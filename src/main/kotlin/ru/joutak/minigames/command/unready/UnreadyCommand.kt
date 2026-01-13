package ru.joutak.minigames.command.unready

import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager

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

            val removed = MatchmakingManager.removePlayer(player)
            if (removed) {
                player.sendMessage(Messages.prefixedLegacyString("messages.unready.removed"))
            } else {
                player.sendMessage(Messages.prefixedLegacyString("messages.unready.not_in_queue"))
            }
            1
        }
}
