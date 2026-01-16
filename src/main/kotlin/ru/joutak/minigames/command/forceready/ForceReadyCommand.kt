package ru.joutak.minigames.command.forceready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.tournament.TournamentManager

/**
 * Tournament-only command: marks team as "force ready" (to allow starting with incomplete roster).
 * This is intentionally separated from /ready to avoid mistakes.
 */
object ForceReadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("forceready")
            .executes { ctx ->
                val player = ctx.source.executor as? Player
                if (player == null) {
                    ctx.source.sender.sendMessage(Messages.prefixedLegacyString("messages.common.only_players"))
                    return@executes Command.SINGLE_SUCCESS
                }

                if (!MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                    player.sendMessage(Messages.prefixedComponent("messages.tournament.forceready_only_in_tournament"))
                    return@executes Command.SINGLE_SUCCESS
                }

                val result = TournamentManager.toggleForceReady(player.uniqueId, player.name)
                if (!result.allowed) {
                    val key = when (result.reason) {
                        TournamentManager.ForceReadyDenyReason.NOT_PARTICIPANT -> "messages.tournament.not_participant"
                        TournamentManager.ForceReadyDenyReason.ONLY_CAPTAIN -> "messages.tournament.only_captain"
                        TournamentManager.ForceReadyDenyReason.ERROR, null -> "messages.tournament.error"
                    }
                    player.sendMessage(Messages.prefixedComponent(key))
                    return@executes Command.SINGLE_SUCCESS
                }

                val msg = if (result.enabled) "messages.tournament.force_ready_on" else "messages.tournament.force_ready_off"
                player.sendMessage(Messages.prefixedComponent(msg))

                Command.SINGLE_SUCCESS
            }
    }
}
