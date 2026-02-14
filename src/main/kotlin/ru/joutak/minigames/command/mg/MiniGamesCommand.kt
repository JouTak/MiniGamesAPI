package ru.joutak.minigames.command.mg

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import ru.joutak.minigames.command.PluginCommand

object MiniGamesCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {
    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("mg")
}
