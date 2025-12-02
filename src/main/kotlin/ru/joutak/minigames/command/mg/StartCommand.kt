package ru.joutak.minigames.command.mg

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import ru.joutak.minigames.command.PluginCommand

object StartCommand : PluginCommand<RequiredArgumentBuilder<CommandSourceStack, String>> {
    override fun getBuilder(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("start", StringArgumentType.string())
}
