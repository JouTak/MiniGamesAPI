package ru.joutak.minigames

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.ceremony.CeremonyManager
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
        // Tournament progress must update even if results storage is disabled.
        TournamentManager.applyMatchResult(result)
        CeremonyManager.handleMatchEnded(result)
        return ResultsManager.recordMatch(result)
    }

    fun hasPlayerWon(
        eventId: String,
        stage: String,
        modeKey: String,
        playerUuid: UUID,
    ): CompletableFuture<Boolean> {
        return ResultsManager.hasPlayerWon(eventId, stage, modeKey, playerUuid)
    }

    fun getTopPlayerIntMetric(
        modeKey: String,
        metricKey: String,
        limit: Int,
        eventId: String? = null,
        stage: String? = null,
    ): CompletableFuture<List<TopPlayerIntMetric>> {
        return ResultsManager.getTopPlayerIntMetric(modeKey, metricKey, limit, eventId, stage)
    }
}
