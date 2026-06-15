package ru.joutak.minigames.command.ready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.MiniGamesCore
import org.bukkit.entity.Player
import ru.joutak.minigames.command.PluginCommand
import ru.joutak.minigames.domain.GameQueue
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.ui.QueueBossBarManager

object ReadyCommand : PluginCommand<LiteralArgumentBuilder<CommandSourceStack>> {

    override fun getBuilder(): LiteralArgumentBuilder<CommandSourceStack> {
        return Commands.literal("ready")
            .executes { ctx ->
                val executor = ctx.source.executor as? Player ?: run {
                    ctx.source.sender.sendMessage(Component.text("Только игроки могут использовать эту команду"))
                    return@executes Command.SINGLE_SUCCESS
                }

                performReady(executor)
                Command.SINGLE_SUCCESS
            }
    }

    /**
     * "Быстрое добавление":
     * - TEAM режим: round-robin по командам, чтобы игроки распределялись равномерно;
     * - SOLO режим: игрок попадает в отдельный технический слот без выбора команды.
     * Возвращает true, если игрок был добавлен в ожидание.
     */
    fun performReady(player: Player): Boolean {
        val uuid = player.uniqueId

        if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
            // Safety: in tournament mode, incomplete readiness must be confirmed via /forceready.
            player.sendMessage(Messages.prefixedComponent("messages.tournament.ready_confirm"))
            return false
        }

        if (MatchmakingManager.isPlayerInStartedGame(uuid)) {
            player.sendMessage(Component.text("Сейчас вы в игре. Нельзя вставать в очередь.", NamedTextColor.RED))
            return false
        }

        if (MatchmakingManager.isPlayerInAnyInstance(uuid)) {
            player.sendMessage(Component.text("Вы уже в очереди!", NamedTextColor.RED))
            return false
        }

        val instance = MatchmakingManager.pickWaitingInstanceForJoin()
        if (instance == null) {
            player.sendMessage(Component.text("Нет свободных арен. Ожидайте.", NamedTextColor.YELLOW))
            return false
        }

        // Если игрок был в очереди выбора команды — снимаем.
        GameQueue.removePlayer(player)

        if (instance.config.isSoloMode) {
            val added = instance.addPlayer(player)
            if (!added) {
                player.sendMessage(Messages.prefixedComponent("messages.ready.failed"))
                return false
            }

            MatchmakingManager.checkReady(instance)
            QueueBossBarManager.updateAll()
            player.sendMessage(Messages.prefixedComponent("messages.ready.added_solo"))
            return true
        }

        // Round-robin по командам.
        val teamIndex = instance.pickTeamIndexRoundRobin()
        if (teamIndex == null) {
            player.sendMessage(Messages.prefixedComponent("messages.ready.no_free_slots"))
            return false
        }

        val added = instance.addPlayerToTeamIndex(player, teamIndex)
        if (!added) {
            player.sendMessage(Messages.prefixedComponent("messages.ready.failed"))
            return false
        }

        MatchmakingManager.checkReady(instance)
        QueueBossBarManager.updateAll()

        player.sendMessage(
            Messages.prefixedComponent(
                "messages.ready.added",
                mapOf("team" to (teamIndex + 1).toString())
            )
        )

        return true
    }
}
