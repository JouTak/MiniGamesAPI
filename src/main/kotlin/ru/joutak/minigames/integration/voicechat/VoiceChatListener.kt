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
