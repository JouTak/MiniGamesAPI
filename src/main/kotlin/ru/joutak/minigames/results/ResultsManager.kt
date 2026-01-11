package ru.joutak.minigames.results

import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.model.TopPlayerIntMetric
import ru.joutak.minigames.results.storage.JdbcResultsStorage
import ru.joutak.minigames.results.storage.ResultsStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ResultsManager {

    @Volatile
    private var initialized: Boolean = false

    @Volatile
    private var enabled: Boolean = false

    private var storage: ResultsStorage? = null

    private lateinit var config: ResultsConfig
    private lateinit var plugin: JavaPlugin
    private lateinit var modeKeyProvider: () -> String

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "results-writer").apply { isDaemon = true }
    }

    fun initialize(
        plugin: JavaPlugin,
        resultsFile: File,
        modeKeyProvider: () -> String,
    ) {
        if (initialized) {
            plugin.logger.info("ResultsManager already initialized, skipping.")
            return
        }
        initialized = true

        this.plugin = plugin
        this.modeKeyProvider = modeKeyProvider

        config = ResultsConfig(resultsFile)

        val cfgEnabled = config.enabled()
        val url = config.jdbcUrl()

        if (!cfgEnabled) {
            enabled = false
            plugin.logger.info("Results storage is disabled (results.enabled=false).")
            return
        }

        if (url.isBlank()) {
            enabled = false
            plugin.logger.warning("Results storage enabled, but results.jdbc.url is empty. Disabling results storage.")
            return
        }

        try {
            storage = JdbcResultsStorage(
                jdbcUrl = url,
                username = config.jdbcUsername(),
                password = config.jdbcPassword(),
                driverClass = config.jdbcDriver(),
                connectTimeoutSeconds = config.connectTimeoutSeconds(),
                autoCreateSchema = config.schemaAutoCreate(),
            )
            enabled = true
            plugin.logger.info("Results storage enabled (JDBC url set).")
        } catch (t: Throwable) {
            enabled = false
            storage = null
            plugin.logger.severe("Failed to initialize results storage: ${t.message}")
            plugin.logger.severe(t.stackTraceToString())
        }
    }

    fun isEnabled(): Boolean = enabled

    fun recordMatch(result: MatchResult): CompletableFuture<Boolean> {
        if (!enabled) return CompletableFuture.completedFuture(false)

        val s = storage ?: return CompletableFuture.completedFuture(false)
        val modeKey = safeModeKey()
        val serverId = config.serverId()

        val normalized = result.copy(
            modeKey = modeKey,
            serverId = serverId,
        )

        return CompletableFuture.supplyAsync({
            try {
                s.recordMatch(normalized)
            } catch (t: Throwable) {
                plugin.logger.severe("Failed to record match ${normalized.matchId}: ${t.message}")
                plugin.logger.severe(t.stackTraceToString())
                false
            }
        }, executor)
    }

    fun hasPlayerWon(
        eventId: String,
        stage: String,
        modeKey: String,
        playerUuid: UUID,
    ): CompletableFuture<Boolean> {
        if (!enabled) return CompletableFuture.completedFuture(false)
        val s = storage ?: return CompletableFuture.completedFuture(false)

        return CompletableFuture.supplyAsync({
            try {
                s.hasPlayerWon(eventId, stage, modeKey, playerUuid)
            } catch (t: Throwable) {
                plugin.logger.severe("Failed to query hasPlayerWon: ${t.message}")
                plugin.logger.severe(t.stackTraceToString())
                false
            }
        }, executor)
    }

    fun getTopPlayerIntMetric(
        modeKey: String,
        metricKey: String,
        limit: Int,
        eventId: String? = null,
        stage: String? = null,
    ): CompletableFuture<List<TopPlayerIntMetric>> {
        if (!enabled) return CompletableFuture.completedFuture(emptyList())
        val s = storage ?: return CompletableFuture.completedFuture(emptyList())

        return CompletableFuture.supplyAsync({
            try {
                s.getTopPlayerIntMetric(modeKey, metricKey, limit, eventId, stage)
            } catch (t: Throwable) {
                plugin.logger.severe("Failed to query top metric: ${t.message}")
                plugin.logger.severe(t.stackTraceToString())
                emptyList()
            }
        }, executor)
    }

    private fun safeModeKey(): String {
        return try {
            val key = modeKeyProvider().trim()
            if (key.isNotBlank()) key else "minigame"
        } catch (_: Throwable) {
            "minigame"
        }
    }

    fun shutdown() {
        try {
            storage?.close()
        } catch (_: Throwable) {
        }

        executor.shutdown()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }
}
