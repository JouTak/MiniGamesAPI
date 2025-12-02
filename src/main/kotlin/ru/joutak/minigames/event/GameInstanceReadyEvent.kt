package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.joutak.minigames.domain.GameInstance

class GameInstanceReadyEvent(val instance: GameInstance) : Event() {
    companion object {
        private val handlers = HandlerList()
        @JvmStatic
        fun getHandlerList() = handlers
    }

    override fun getHandlers(): HandlerList = handlers
}
