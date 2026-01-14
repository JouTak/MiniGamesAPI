package ru.joutak.minigames.event

import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import ru.joutak.minigames.domain.GameInstance

class GameInstanceReadyEvent(val instance: GameInstance) : Event() {
    companion object {
        /**
         * Bukkit event system expects a static handler list.
         *
         * IMPORTANT: do not name this backing field `handlers`.
         * In Kotlin such a property can generate a JVM accessor named `getHandlers()`,
         * which may clash with Bukkit's Event#getHandlers and lead to recursion/StackOverflow.
         */
        private val HANDLER_LIST = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLER_LIST
    }

    override fun getHandlers(): HandlerList = HANDLER_LIST
}
