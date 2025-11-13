package ru.joutak.minigames.command.unready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.domain.GameQueue
import net.kyori.adventure.text.Component

object UnreadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {
    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("unready")
            .executes { ctx ->
                val executor = ctx.source.executor
                if (executor !is Player) {
                    ctx.source.sendFailure(Component.text("Only players can use this command"))
                    return@executes Command.SINGLE_SUCCESS
                }

                val playerDomain = GameQueue.getOrCreatePlayer(executor)
                val removed = GameQueue.removePlayer(playerDomain)

                if (removed) {
                    executor.sendMessage(Component.text("Вы больше не в очереди."))
                } else {
                    executor.sendMessage(Component.text("Вы не были в очереди."))
                }

                Command.SINGLE_SUCCESS
            }
    }