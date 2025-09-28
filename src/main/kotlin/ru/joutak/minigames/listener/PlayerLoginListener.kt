package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerLoginEvent
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.config.ConfigKeys

object PlayerLoginListener : Listener {
    @EventHandler
    fun onPlayerLogin(event: PlayerLoginEvent) {
        if (!MiniGamesPlugin.instance.getConfiguration().get(ConfigKeys.ENABLED)) {
            return
        }
    }
}
