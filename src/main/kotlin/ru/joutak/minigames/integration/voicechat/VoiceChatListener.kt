package ru.joutak.minigames.integration.voicechat

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.event.GameInstanceEndedEvent
import ru.joutak.minigames.event.GameInstanceReadyEvent
import ru.joutak.minigames.event.MatchResultRecordingEvent

object VoiceChatListener : Listener {

    /**
     * Run before mode handlers — some modes clear [GameInstance.teams][ru.joutak.minigames.domain.GameInstance.teams]
     * during their own match-start logic, so we need to read the rosters first.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onReady(event: GameInstanceReadyEvent) {
        if (!TeamGroupManager.isReady()) return
        runCatching { TeamGroupManager.assignTeamsToGroups(event.instance) }
            .onFailure {
                MiniGamesCore.plugin.logger.warning(
                    "[MiniGamesAPI] Voice group assignment failed: ${it.message}"
                )
            }
    }

    /**
     * Primary teardown path. Fires when the mode calls
     * `MiniGamesAPI.recordMatchResult(...)` — i.e. as soon as the match has
     * logically finished and the mode is in CLEANUP. The user-facing effect:
     * voice groups disappear once the ceremony has ended (or earlier — at the
     * mode's discretion).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onResultRecording(event: MatchResultRecordingEvent) {
        if (!TeamGroupManager.isReady()) return
        runCatching { TeamGroupManager.dissolveGroupsForResult(event.result) }
            .onFailure {
                MiniGamesCore.plugin.logger.warning(
                    "[MiniGamesAPI] Voice group teardown on result-recording failed: ${it.message}"
                )
            }
    }

    /**
     * Safety net for modes that don't call `recordMatchResult` (or for late
     * leftovers). [TeamGroupManager.dissolveGroups] is idempotent on a per-instance
     * basis, so a double dissolve here is a no-op.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEnded(event: GameInstanceEndedEvent) {
        if (!TeamGroupManager.isReady()) return
        runCatching { TeamGroupManager.dissolveGroups(event.instance) }
            .onFailure {
                MiniGamesCore.plugin.logger.warning(
                    "[MiniGamesAPI] Voice group dissolve failed: ${it.message}"
                )
            }
    }
}
