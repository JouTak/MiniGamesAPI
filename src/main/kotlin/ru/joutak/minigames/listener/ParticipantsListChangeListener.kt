package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesPlugin
import ru.joutak.minigames.event.ParticipantsListChangeEvent

object ParticipantsListChangeListener : Listener {
    @EventHandler
    fun onParticipantsListChange(event: ParticipantsListChangeEvent) {
        MiniGamesPlugin.instance.logger.warning("Файл с участниками был изменен.")
//        MiniGamesPlugin.instance
//            .spartakiadManager
//            .participantManager
//            .reload()
    }
}
