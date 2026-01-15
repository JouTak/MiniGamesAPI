package ru.joutak.minigames.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.tournament.TournamentManager

object PlayerJoinListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        // НЕ используем player.scheduler.* (EntityScheduler): на Purpur это уходит в MinecraftInternalPlugin
        // и начинает флудить UnsupportedOperationException в ServerSchedulerReportingWrapper.
        Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            if (!player.isOnline) return@Runnable

            val cfg = MiniGamesCore.configuration
            val tournamentEnabled = cfg.get(ConfigKeys.TOURNAMENT_ENABLED)
            val preloginStrict = cfg.get(ConfigKeys.TOURNAMENT_PRELOGIN_STRICT)

            if (tournamentEnabled && !preloginStrict) {
                val bypassPerm = cfg.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION).trim()
                val bypass = bypassPerm.isNotBlank() && player.hasPermission(bypassPerm)

                if (!bypass) {
                    val gate = TournamentManager.checkAccess(player.uniqueId, player.name)
                    if (!gate.allowed) {
                        player.kick(TournamentManager.denyKickMessageComponent(gate.denyReason))
                        return@Runnable
                    }
                }
            }

            val helpKey = if (tournamentEnabled) "messages.join.help_tournament" else "messages.join.help"

            if (Messages.has(helpKey)) {
                player.sendMessage(Messages.prefixedComponent(helpKey))
                return@Runnable
            }

            // Fallback for old installations without messages config.
            player.sendMessage(
                Component.text("Основные команды:\n", NamedTextColor.YELLOW)
                    .append(
                        Component.text(
                            "/ready - быстро присоединиться к первой свободной команде\n",
                            NamedTextColor.YELLOW
                        )
                    )
                    .append(
                        Component.text(
                            "/teamselect - выбрать команду (или используйте предмет в хотбаре)\n",
                            NamedTextColor.YELLOW
                        )
                    )
                    .append(Component.text("/unready - выйти из очереди/ожидания\n", NamedTextColor.YELLOW))
                    .append(Component.text("/lobby - вернуться в лобби миниигр", NamedTextColor.YELLOW))
            )
        }, 20L)
    }
}
