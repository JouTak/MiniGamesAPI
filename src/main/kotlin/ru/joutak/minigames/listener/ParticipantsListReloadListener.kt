package ru.joutak.minigames.listener

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.event.ParticipantsListReloadEvent

@Deprecated("Legacy participants listeners; not registered by MiniGamesCore.")
object ParticipantsListReloadListener : Listener {
    @EventHandler
    fun onParticipantsListReload(event: ParticipantsListReloadEvent) {
        MiniGamesCore.plugin.logger.warning("Файл с участниками был перезагружен:\n${event.participants.joinToString("\n")}")
    }
}
