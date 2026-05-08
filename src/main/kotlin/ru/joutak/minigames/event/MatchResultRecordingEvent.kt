package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.joutak.minigames.results.model.MatchResult

/** Fired at the start of [ru.joutak.minigames.MiniGamesAPI.recordMatchResult], before persistence. */
class MatchResultRecordingEvent(val result: MatchResult) : Event() {
    companion object {
        private val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST
}
