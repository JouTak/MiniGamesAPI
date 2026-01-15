package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

@Deprecated("Legacy participants events; not used by current Spartakiad flow (SQLite ParticipantStorage).")
class ParticipantsListReloadEvent(
    val participants: Iterable<String>,
) : Event() {
    companion object {
        @JvmStatic
        private val handlersList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlersList
    }

    override fun getHandlers(): HandlerList = handlersList
}
