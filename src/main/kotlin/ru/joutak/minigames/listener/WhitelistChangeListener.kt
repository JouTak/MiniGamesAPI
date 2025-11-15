package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.event.WhitelistChangeEvent

object WhitelistChangeListener : Listener {
    @EventHandler
    fun onWhitelistChange(event: WhitelistChangeEvent) {
        MiniGamesCore.plugin.logger.warning("Файл с участниками был изменен.")
        MiniGamesCore
            .spartakiadManager
            .whitelistManager
            .reload()
    }
}
