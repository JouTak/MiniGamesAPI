package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.event.WhitelistReloadEvent

object WhitelistReloadListener : Listener {
    @EventHandler
    fun onWhitelistReload(event: WhitelistReloadEvent) {
        MiniGamesCore.plugin.logger.warning(
            "Файл с участниками был перезагружен:\n${
                event.whitelist.joinToString(
                    "\n"
                )
            }"
        )
    }
}
