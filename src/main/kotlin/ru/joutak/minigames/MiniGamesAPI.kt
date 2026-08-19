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
import ru.joutak.minigames.results.model.Metric
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

    fun recordMatchResult(result: MatchResult): CompletableFuture<Boolean> =
        recordMatchResultInternal(result, emptyMap())

    private fun recordMatchResultInternal(
        result: MatchResult,
        teamKeysByTeamId: Map<Int, String>,
    ): CompletableFuture<Boolean> {
        runOnMainThread { Bukkit.getPluginManager().callEvent(MatchResultRecordingEvent(result)) }

        val resultsFuture = ResultsManager.recordMatch(result)

        // Recalculation must run after persistence, otherwise the just-finished match may be absent
        // from the qualifier snapshot. Tournament progress still updates when storage is disabled
        // or when recording failed.
        val tournamentFuture = if (ResultsManager.isEnabled()) {
            resultsFuture.handle { _, _ -> null }
                .thenCompose { TournamentManager.applyMatchResult(result, teamKeysByTeamId) }
        } else {
            TournamentManager.applyMatchResult(result, teamKeysByTeamId)
        }

        if (TournamentManager.isEnabled() && TournamentManager.isPostMatchKickParticipantsEnabled()) {
            tournamentFuture.thenAccept { ok ->
                if (ok) {
                    scheduleKickMatchParticipants(result)
                }
            }
        }

        return resultsFuture
    }

    /**
     * Records a result and injects tournament competitor keys into team metrics.
     * Existing `team_key` metrics win over values supplied by [teamKeysByTeamId].
     */
    fun recordMatchResult(
        result: MatchResult,
        teamKeysByTeamId: Map<Int, String>,
    ): CompletableFuture<Boolean> {
        if (teamKeysByTeamId.isEmpty()) return recordMatchResult(result)

        val enriched = result.copy(
            teams = result.teams.map { team ->
                if (team.metrics.any { it.key == "team_key" && !it.valueText.isNullOrBlank() }) {
                    team
                } else {
                    val teamKey = teamKeysByTeamId[team.teamId]?.trim().orEmpty()
                    if (teamKey.isBlank()) team
                    else team.copy(metrics = team.metrics + Metric.text("team_key", teamKey))
                }
            }
        )
        return recordMatchResultInternal(enriched, teamKeysByTeamId)
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

    fun getTeamStyle(teamNumber: Int): TeamStyle = TeamStyleProvider.get(teamNumber)

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

    fun findActiveMatchInstance(player: Player): GameInstance? {
        val uuid = player.uniqueId
        return MatchmakingManager.getActiveInstances()
            .firstOrNull { it.started && it.hasActivePlayer(uuid) }
    }

    fun getCurrentTeamStyle(player: Player): TeamStyle? {
        val activeIndex = findActiveMatchInstance(player)?.getActiveTeamIndex(player.uniqueId)
        val teamIndex = activeIndex ?: getPlayerTeamInLobby(player) ?: return null
        return getTeamStyle(teamIndex + 1)
    }

    fun allowVoiceSpectator(player: Player, instance: GameInstance) {
        voiceSpectatorRegistry?.allow(player, instance)
    }

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
