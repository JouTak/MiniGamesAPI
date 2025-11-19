package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.event.ParticipantsListChangeEvent

object ParticipantsListChangeListener : Listener {
    @EventHandler
    fun onParticipantsListChange(event: ParticipantsListChangeEvent) {
        MiniGamesCore.plugin.logger.warning("Файл с участниками был изменен.")
//        MiniGamesCore.plugin
//            .spartakiadManager
//            .participantManager
//            .reload()
    }
}
