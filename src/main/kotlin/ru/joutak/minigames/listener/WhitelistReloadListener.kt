package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.event.WhitelistReloadEvent

object WhitelistReloadListener : Listener {
    @EventHandler
    fun onWhitelistReload(event: WhitelistReloadEvent) {
        MiniGamesPlugin.instance.logger.warning(
            "Файл с участниками был перезагружен:\n${
                event.whitelist.joinToString(
                    "\n"
                )
            }"
        )
    }
}
