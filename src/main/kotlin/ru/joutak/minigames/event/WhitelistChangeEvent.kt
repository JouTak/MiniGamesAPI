package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class WhitelistChangeEvent : Event() {
    companion object {
        @JvmStatic
        private val handlersList = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = handlersList
    }

    override fun getHandlers(): HandlerList = handlersList
}
