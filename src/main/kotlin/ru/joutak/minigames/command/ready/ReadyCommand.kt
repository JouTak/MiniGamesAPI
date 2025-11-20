package ru.joutak.minigames.command.ready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
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
                    ctx.source.sender.sendMessage(
                        Component.text("Только игроки могут использовать эту команду")
                    )
                    return@executes Command.SINGLE_SUCCESS
                }

                // Добавляем игрока в очередь. Если уже в очереди, выдаём сообщение
                val added = GameQueue.addPlayer(executor)
                if (!added) {
                    executor.sendMessage(Component.text("Вы уже в очереди!"))
                    return@executes Command.SINGLE_SUCCESS
                }

                executor.sendMessage(Component.text("Вы добавлены в очередь блаблабла!"))

                // Открываем GUI для выбора команды, если есть свободные инстансы
                val instance = MatchmakingManager.getActiveInstances().firstOrNull { !it.isFull() }
                if (instance != null) {
                    TeamSelectionGui.open(executor, instance) { player, teamIndex ->
                        instance.teams[teamIndex].add(player)
                        player.sendMessage(Component.text("Вы выбрали команду ${teamIndex + 1}"))
                    }
                }

                Command.SINGLE_SUCCESS
            }
    }
}
