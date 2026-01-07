package ru.joutak.minigames.command.ready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.gui.TeamSelectionGui
import ru.joutak.minigames.managers.MatchmakingManager

object ReadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("ready")
            .executes { ctx ->
                val executor = ctx.source.executor as? Player ?: run {
                    ctx.source.sender.sendMessage(Component.text("Только игроки могут использовать эту команду"))
                    return@executes Command.SINGLE_SUCCESS
                }

                val playerIsAlreadyInGame = MatchmakingManager.getActiveInstances().any { instance ->
                    instance.teams.flatten().any { it.uniqueId == executor.uniqueId }
                }

                if (playerIsAlreadyInGame) {
                    executor.sendMessage(Component.text("Вы уже находитесь в игре!", NamedTextColor.RED))
                    return@executes Command.SINGLE_SUCCESS
                }

                val added = GameQueue.addPlayer(executor)
                if (!added) {
                    executor.sendMessage(Component.text("Вы уже в очереди!", NamedTextColor.YELLOW))
                    return@executes Command.SINGLE_SUCCESS
                }

                executor.sendMessage(Component.text("Выберите команду.", NamedTextColor.GREEN))

                val instance = MatchmakingManager.getActiveInstances().firstOrNull { !it.started && !it.isFull() }

                if (instance != null) {
                    TeamSelectionGui.open(executor, instance) { player, teamIndex ->

                        // Если матч уже стартовал, не даём записаться (и очищаем очередь).
                        if (instance.started) {
                            GameQueue.removePlayer(player)
                            player.sendMessage(
                                Component.text("Этот матч уже запущен. Встаньте в очередь заново.", NamedTextColor.RED)
                            )
                            return@open
                        }

                        val chosenTeam = instance.teams[teamIndex]
                        if (chosenTeam.size < instance.config.playersPerTeam) {
                            chosenTeam.add(player)
                            GameQueue.removePlayer(player)
                            MatchmakingManager.checkReady(instance)

                            player.sendMessage(
                                Component.text("Вы добавлены в очередь за команду ${teamIndex + 1}!", NamedTextColor.GREEN)
                            )
                        } else {
                            player.sendMessage(
                                Component.text("Эта команда уже полна. Выберите другую.", NamedTextColor.RED)
                            )
                            GameQueue.removePlayer(player)
                        }
                    }
                } else {
                    executor.sendMessage(Component.text("Нет свободных арен. Ожидайте.", NamedTextColor.YELLOW))
                }

                Command.SINGLE_SUCCESS
            }
    }
}
