package ru.joutak.minigames.command.ready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.domain.GameQueue
import net.kyori.adventure.text.Component

// Команда /ready — добавляет игрока в очередь
object ReadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {
    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("ready")
            .executes { ctx ->
                val executor = ctx.source.executor
                if (executor !is Player) {
                    ctx.source.sender.sendMessage(
                        Component.text("Только игроки могут использовать эту команду")
                    )
                    return@executes Command.SINGLE_SUCCESS
                }

                val playerDomain = GameQueue.getOrCreatePlayer(executor)
                val added = GameQueue.addPlayer(playerDomain)

                if (added) {
                    executor.sendMessage(Component.text("Вы добавлены в очередь!."))
                } else {
                    executor.sendMessage(Component.text("Вы уже в очереди."))
                }

                Command.SINGLE_SUCCESS
            }
    }
}