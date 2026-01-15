package ru.joutak.minigames.command.teamselect

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.MiniGamesCore
import org.bukkit.entity.Player
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.gui.TeamSelectionGui
import ru.joutak.minigames.managers.MatchmakingManager

object TeamSelectCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("teamselect")
            .executes { ctx ->
                val executor = ctx.source.executor as? Player ?: run {
                    ctx.source.sender.sendMessage(Component.text("Только игроки могут использовать эту команду"))
                    return@executes Command.SINGLE_SUCCESS
                }

                openTeamSelect(executor)
                Command.SINGLE_SUCCESS
            }
    }

    /**
     * Общая логика для /teamselect и лобби-предмета.
     */
    fun openTeamSelect(player: Player) {
        val uuid = player.uniqueId

        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
            player.sendMessage(Messages.prefixedComponent("messages.tournament.disabled_teamselect"))
            return
        }


        if (MatchmakingManager.isPlayerInStartedGame(uuid)) {
            player.sendMessage(Component.text("Сейчас вы в игре. Нельзя выбирать команду.", NamedTextColor.RED))
            return
        }

        // Если игрок уже в какой-то команде ожидания — открываем GUI именно для этого инстанса.
        val existingWaitingInstance = MatchmakingManager.getActiveInstances().firstOrNull { instance ->
            !instance.started && instance.hasWaitingPlayer(uuid)
        }

        val instance = existingWaitingInstance
            ?: MatchmakingManager.getActiveInstances().firstOrNull { !it.started && !it.isFull() }

        if (instance == null) {
            player.sendMessage(Component.text("Нет свободных арен. Ожидайте.", NamedTextColor.YELLOW))
            return
        }

        // Механика "старого /ready": игрок попадает в очередь на время выбора команды.
        GameQueue.addPlayer(player)

        player.sendMessage(Component.text("Выберите команду.", NamedTextColor.GREEN))

        TeamSelectionGui.open(player, instance) { p, teamIndex ->
            if (instance.started) {
                GameQueue.removePlayer(p)
                p.sendMessage(Component.text("Этот матч уже запущен. Встаньте в очередь заново.", NamedTextColor.RED))
                return@open
            }

            val added = instance.addPlayerToTeamIndex(p, teamIndex)
            if (added) {
                GameQueue.removePlayer(p)
                MatchmakingManager.checkReady(instance)

                p.sendMessage(
                    Component.text(
                        "Вы добавлены в очередь за команду ${teamIndex + 1}!",
                        NamedTextColor.GREEN
                    )
                )
            } else {
                p.sendMessage(
                    Component.text(
                        "Эта команда уже полна. Выберите другую.",
                        NamedTextColor.RED
                    )
                )
                GameQueue.removePlayer(p)
            }
        }
    }
}
