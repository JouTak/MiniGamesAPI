package ru.joutak.minigames

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.domain.GameInstance
import ru.joutak.minigames.domain.TeamStyle
import ru.joutak.minigames.domain.TeamStyleProvider
import ru.joutak.minigames.domain.VoiceSpectatorRegistry
import ru.joutak.minigames.event.MatchResultRecordingEvent
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.results.ResultsManager
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.TopPlayerIntMetric
import ru.joutak.minigames.tournament.TournamentManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture

object MiniGamesAPI {
    lateinit var plugin: JavaPlugin
        internal set

    lateinit var config: Config
        internal set

    /**
     * Bound at runtime by `VoiceChatIntegration.tryInitialize` (only on servers
     * with SimpleVoiceChat installed). Stays null otherwise — see
     * [VoiceSpectatorRegistry] for the rationale.
     */
    @Volatile
    var voiceSpectatorRegistry: VoiceSpectatorRegistry? = null
        internal set

    fun initialize(plugin: JavaPlugin, config: Config) {
        this.plugin = plugin
        this.config = config
    }

    // Добавьте этот метод для совместимости
    fun getDataFolder(): File = plugin.dataFolder

    /**
     * Results storage (shared database) is optional and can be disabled in results.yml.
     */
    fun isResultsEnabled(): Boolean = ResultsManager.isEnabled()

    fun recordMatchResult(result: MatchResult): CompletableFuture<Boolean> {
        // Notify cross-cutting integrations (voice chat group teardown, etc.)
        // that the match has just ended. Fire on the main thread — listeners
        // may touch Bukkit state. Must come BEFORE async DB / tournament work.
        runOnMainThread { Bukkit.getPluginManager().callEvent(MatchResultRecordingEvent(result)) }

        // Tournament progress must update even if results storage is disabled.
        // IMPORTANT: modes are responsible for any post-game ceremony/teleports.
        val tournamentFuture = TournamentManager.applyMatchResult(result)

        if (TournamentManager.isEnabled() && TournamentManager.isPostMatchKickParticipantsEnabled()) {
            tournamentFuture.thenAccept { ok ->
                if (ok) {
                    scheduleKickMatchParticipants(result)
                }
            }
        }

        return ResultsManager.recordMatch(result)
    }

    fun isTournamentEnabled(): Boolean = TournamentManager.isEnabled()

    private fun scheduleKickMatchParticipants(result: MatchResult) {
        val delay = TournamentManager.getPostMatchKickDelayTicks().toLong().coerceAtLeast(0L)
        val bypassPerm = config.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val msg = Messages.prefixedComponent("messages.tournament.post_match_kick")
            for (p in result.players) {
                val pl = Bukkit.getPlayer(p.playerUuid) ?: continue
                if (!pl.isOnline) continue
                if (TournamentManager.isBypassUuid(pl.uniqueId)) continue
                if (bypassPerm.isNotBlank() && pl.hasPermission(bypassPerm)) continue
                pl.kick(msg)
            }
        }, delay)
    }

    fun hasPlayerWon(
        eventId: String,
        stage: String,
        modeKey: String,
        playerUuid: UUID,
    ): CompletableFuture<Boolean> {
        return ResultsManager.hasPlayerWon(eventId, stage, modeKey, playerUuid)
    }

    /**
     * Returns the configured visual style for a team (1-based number).
     *
     * The style is loaded from MiniGamesAPI's `config.yml` (`teamselect.teams.<n>`).
     * Modes should call this instead of hardcoding their own per-mode team metadata,
     * so admins can change names/colors centrally.
     */
    fun getTeamStyle(teamNumber: Int): TeamStyle = TeamStyleProvider.get(teamNumber)

    /**
     * Convenience: returns styles for teams 1..[count], in order.
     */
    fun getTeamStyles(count: Int): List<TeamStyle> = TeamStyleProvider.getAll(count)

    fun getTopPlayerIntMetric(
        modeKey: String,
        metricKey: String,
        limit: Int,
        eventId: String? = null,
        stage: String? = null,
    ): CompletableFuture<List<TopPlayerIntMetric>> {
        return ResultsManager.getTopPlayerIntMetric(modeKey, metricKey, limit, eventId, stage)
    }

    fun getPlayerTeamInLobby(player: Player): Int? {
        val uuid = player.uniqueId
        val instance = MatchmakingManager.getActiveInstances().firstOrNull{
            !it.started && it.hasWaitingPlayer(uuid)
        } ?: return null

        for ((teamIndex, teamPlayers) in instance.teams.withIndex()) {
            if (teamPlayers.any {it.uniqueId == uuid}){
                return teamIndex
            }
        }
        return null
    }

    /**
     * Returns the started instance the [player] is currently an active match
     * participant of, or null. Useful for modes that need an instance handle
     * for [allowVoiceSpectator] without storing it themselves.
     */
    fun findActiveMatchInstance(player: Player): GameInstance? {
        val uuid = player.uniqueId
        return MatchmakingManager.getActiveInstances()
            .firstOrNull { it.started && it.hasActivePlayer(uuid) }
    }

    /**
     * Mark [player] as allowed to enter ANY of [instance]'s per-team voice
     * groups. No-op when SimpleVoiceChat is not installed.
     */
    fun allowVoiceSpectator(player: Player, instance: GameInstance) {
        voiceSpectatorRegistry?.allow(player, instance)
    }

    /** Inverse of [allowVoiceSpectator]. No-op when SVC is not installed. */
    fun revokeVoiceSpectator(player: Player, instance: GameInstance) {
        voiceSpectatorRegistry?.revoke(player, instance)
    }

    private fun runOnMainThread(task: () -> Unit) {
        if (Bukkit.isPrimaryThread()) {
            task()
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable { task() })
        }
    }
}
