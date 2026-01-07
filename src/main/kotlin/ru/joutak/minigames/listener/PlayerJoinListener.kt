package ru.joutak.minigames.listener

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import ru.joutak.minigames.MiniGamesCore

object PlayerJoinListener : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        player.scheduler.runDelayed(
            MiniGamesCore.plugin,
            { _ ->
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
            },
            null,
            20L
        )
    }
}
