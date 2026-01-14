package ru.joutak.minigames.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.joutak.minigames.MiniGamesCore

object PlayerJoinListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // НЕ используем player.scheduler.* (EntityScheduler): на Purpur это уходит в MinecraftInternalPlugin
        // и начинает флудить UnsupportedOperationException в ServerSchedulerReportingWrapper.
        Bukkit.getScheduler().runTaskLater(MiniGamesCore.plugin, Runnable {
            if (!player.isOnline) return@Runnable
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
