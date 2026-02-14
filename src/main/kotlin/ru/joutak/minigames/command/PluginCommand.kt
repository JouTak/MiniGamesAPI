package ru.joutak.minigames.command

import com.mojang.brigadier.builder.ArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack

interface PluginCommand<S : ArgumentBuilder<CommandSourceStack, S>> {
    fun getBuilder(): ArgumentBuilder<CommandSourceStack, S>
}
