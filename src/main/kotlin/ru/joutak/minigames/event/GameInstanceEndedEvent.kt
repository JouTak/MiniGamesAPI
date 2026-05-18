package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.joutak.minigames.domain.GameInstance

/**
 * Fired when the last active participant leaves a started [GameInstance],
 * so the instance has logically returned to the "not started" state.
 *
 * Intended for cross-cutting integrations (e.g. voice chat) that need to
 * unwind per-match state without each mode having to call them explicitly.
 */
class GameInstanceEndedEvent(val instance: GameInstance) : Event() {
    companion object {
        // See GameInstanceReadyEvent for why this is not named `handlers`.
        private val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST
}
