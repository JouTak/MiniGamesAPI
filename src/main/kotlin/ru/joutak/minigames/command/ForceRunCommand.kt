package ru.joutak.minigames.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import ru.joutak.minigames.managers.MatchmakingManager

object ForceRunCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("forcerun")
            .requires { it.sender.hasPermission("minigames.admin") }
            .executes { ctx ->

                val instance = MatchmakingManager.getActiveInstances()
                    .firstOrNull { !it.isFull() }

                if (instance == null) {
                    ctx.source.sender.sendMessage(
                        Component.text("Нет активных инстансов, которые можно запустить!")
                    )
                    return@executes Command.SINGLE_SUCCESS
                }

                // Насильно отправляем инстанс в очередь ready
                MatchmakingManager.forceReady(instance)

                ctx.source.sender.sendMessage(
                    Component.text("Игра будет запущена без ожидания остальных!")
                )

                Command.SINGLE_SUCCESS
            }
    }
}
