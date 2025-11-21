package ru.joutak.splatoon.listeners

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
                        .append(Component.text("/ready - встать в очередь\n", NamedTextColor.YELLOW))
                        .append(Component.text("/unready - выйти из очереди\n", NamedTextColor.YELLOW))
                        .append(Component.text("/lobby - вернуться в лобби миниигр", NamedTextColor.YELLOW))
                )
            },
            null,      // retired (можно оставить null)
            20L        // задержка в тиках
        )
    }
}