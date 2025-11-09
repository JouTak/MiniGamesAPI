package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.event.WhitelistChangeEvent

object WhitelistChangeListener : Listener {
    @EventHandler
    fun onWhitelistChange(event: WhitelistChangeEvent) {
        MiniGamesPlugin.instance.logger.warning("Файл с участниками был изменен.")
        MiniGamesPlugin.instance
            .spartakiadManager
            .whitelistManager
            .reload()
    }
}
