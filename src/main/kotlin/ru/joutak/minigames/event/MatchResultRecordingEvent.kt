package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.joutak.minigames.results.model.MatchResult

/**
 * Fired at the very start of [ru.joutak.minigames.MiniGamesAPI.recordMatchResult],
 * before tournament progress is applied or results are persisted.
 *
 * Intended for cross-cutting integrations that need to react to "match has just
 * ended" — e.g. tearing down per-match voice groups during cleanup. Using this
 * event keeps mode plugins decoupled from such integrations: they just continue
 * to call `recordMatchResult` as before.
 */
class MatchResultRecordingEvent(val result: MatchResult) : Event() {
    companion object {
        // Static HandlerList accessor name avoids the JVM signature collision that
        // happens when a Kotlin property called `handlers` clashes with the Bukkit
        // `getHandlers()` instance method (see GameInstanceReadyEvent for context).
        private val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST
}
