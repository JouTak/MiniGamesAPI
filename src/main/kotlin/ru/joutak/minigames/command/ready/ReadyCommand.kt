package ru.joutak.minigames.command.ready

import com.mojang.brigadier.Command
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
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
     * "Быстрое добавление" — в первую свободную команду (по индексу) первой доступной арены.
     * Возвращает true, если игрок был добавлен в ожидание.
     */
    fun performReady(player: Player): Boolean {
        val uuid = player.uniqueId

        if (MatchmakingManager.isPlayerInStartedGame(uuid)) {
            player.sendMessage(Component.text("Сейчас вы в игре. Нельзя вставать в очередь.", NamedTextColor.RED))
            return false
        }

        if (MatchmakingManager.isPlayerInAnyInstance(uuid)) {
            player.sendMessage(Component.text("Вы уже в очереди!", NamedTextColor.RED))
            return false
        }

        val instance = MatchmakingManager.getActiveInstances().firstOrNull { !it.started && !it.isFull() }
        if (instance == null) {
            player.sendMessage(Component.text("Нет свободных арен. Ожидайте.", NamedTextColor.YELLOW))
            return false
        }

        // Первая свободная команда
        val teamIndex = instance.teams.indexOfFirst { it.size < instance.config.playersPerTeam }
        if (teamIndex == -1) {
            player.sendMessage(Component.text("Нет свободных мест в командах. Ожидайте.", NamedTextColor.YELLOW))
            return false
        }

        // Если игрок был в очереди выбора команды — снимаем.
        GameQueue.removePlayer(player)

        val added = instance.addPlayerToTeamIndex(player, teamIndex)
        if (!added) {
            player.sendMessage(Component.text("Не удалось встать в очередь. Попробуйте ещё раз.", NamedTextColor.RED))
            return false
        }

        MatchmakingManager.checkReady(instance)
        QueueBossBarManager.updateAll()

        player.sendMessage(
            Component.text(
                "Вы добавлены в очередь за команду ${teamIndex + 1}!",
                NamedTextColor.GREEN
            )
        )

        return true
    }
}
