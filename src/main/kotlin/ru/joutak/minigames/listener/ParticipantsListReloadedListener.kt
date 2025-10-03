package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.event.ParticipantsListReloadedEvent

object ParticipantsListReloadedListener : Listener {
    @EventHandler
    fun onParticipantsListReload(event: ParticipantsListReloadedEvent) {
        MiniGamesPlugin.instance.logger.warning("Файл с участниками был перезагружен:\n${event.participants.joinToString("\n")}")
    }
}
