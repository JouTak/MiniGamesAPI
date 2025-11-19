package ru.joutak.minigames.command.unready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.managers.MatchmakingManager

object UnreadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {
    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("unready")
            .executes { ctx ->
                val executor = ctx.source.executor as? Player ?: run {
                    ctx.source.sender.sendMessage(Component.text("Только игроки могут использовать эту команду"))
                    return@executes Command.SINGLE_SUCCESS
                }

                MatchmakingManager.removePlayer(executor)
                executor.sendMessage(Component.text("Вы больше не в очереди."))
                Command.SINGLE_SUCCESS
            }
    }
}
