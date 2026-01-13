package ru.joutak.minigames.command

import io.papermc.paper.command.brigadier.Commands
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager

object ForceRunCommand {

    fun getBuilder() = Commands.literal("forcerun")
        .requires { src ->
            val s = src.sender
            s.isOp || s.hasPermission("minigamesapi.admin") || s.hasPermission("minigamesapi.forcerun")
        }
        .executes { ctx ->
            val sender = ctx.source.sender

            val candidates = MatchmakingManager.getActiveInstances().filter { !it.started }
            val target = candidates
                .maxByOrNull { it.teams.sumOf { t -> t.size } }
                ?.takeIf { it.teams.sumOf { t -> t.size } > 0 }

            if (target == null) {
                sender.sendMessage(Messages.prefixedLegacyString("messages.forcerun.no_instances"))
                return@executes 1
            }

            MatchmakingManager.forceReady(target)
            sender.sendMessage(Messages.prefixedLegacyString("messages.forcerun.will_start"))
            1
        }
}
