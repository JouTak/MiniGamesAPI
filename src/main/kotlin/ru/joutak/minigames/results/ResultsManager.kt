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
    val url = config.jdbcUrl().trim()

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

    warnIfMySqlOptionsUsedWithMariaDb(plugin, url)

    val autoCreateSchema = config.schemaAutoCreate()
    val connectTimeoutSeconds = config.connectTimeoutSeconds()
    val serverId = config.serverId()

    val configuredDriver = config.jdbcDriver().trim()
    val resolvedDriver = if (configuredDriver.isNotBlank()) configuredDriver else guessDriverForUrl(url)
    val driverLabel = if (resolvedDriver.isBlank()) "<auto>" else resolvedDriver

    plugin.logger.info(
        "Results JDBC config: server_id=$serverId, auto_create_schema=$autoCreateSchema, " +
            "connect_timeout=${connectTimeoutSeconds}s, driver=$driverLabel, url=$url"
    )

    if (resolvedDriver.isNotBlank() && !tryLoadDriver(plugin, resolvedDriver, url)) {
        enabled = false
        storage = null
        plugin.logger.severe("Failed to initialize results storage: JDBC driver '$resolvedDriver' not found in classpath.")
        plugin.logger.severe(driverInstallHint(url))
        return
    }

    try {
        storage = JdbcResultsStorage(
            jdbcUrl = url,
            username = config.jdbcUsername(),
            password = config.jdbcPassword(),
            driverClass = resolvedDriver,
            connectTimeoutSeconds = connectTimeoutSeconds,
            autoCreateSchema = autoCreateSchema,
        )
        enabled = true
        plugin.logger.info("Results storage enabled (JDBC url set).")
    } catch (t: Throwable) {
        enabled = false
        storage = null
        plugin.logger.severe("Failed to initialize results storage: ${t.message}")
        if ((t.message ?: "").contains("No suitable driver", ignoreCase = true)) {
            plugin.logger.severe(driverInstallHint(url))
        }
        plugin.logger.severe(t.stackTraceToString())

    }
}
    fun isEnabled(): Boolean = enabled


    private fun warnIfMySqlOptionsUsedWithMariaDb(plugin: JavaPlugin, url: String) {
    if (!url.startsWith("jdbc:mariadb:", ignoreCase = true)) return

    val lower = url.lowercase()
    val hasAllowPublicKeyRetrieval = "allowpublickeyretrieval=" in lower
    val hasServerTimezone = "servertimezone=" in lower

    if (hasAllowPublicKeyRetrieval || hasServerTimezone) {
        plugin.logger.warning(
            "MariaDB JDBC URL contains MySQL-specific options (allowPublicKeyRetrieval/serverTimezone). " +
                "If connection fails with 'Unknown option', remove these params or use jdbc:mysql with MySQL driver."
        )
    }
}

private fun guessDriverForUrl(url: String): String {
    val lower = url.lowercase()
    return when {
        lower.startsWith("jdbc:mariadb:") -> "org.mariadb.jdbc.Driver"
        lower.startsWith("jdbc:mysql:") -> "com.mysql.cj.jdbc.Driver"
        lower.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
        lower.startsWith("jdbc:sqlite:") -> "org.sqlite.JDBC"
        else -> ""
    }
}

private fun tryLoadDriver(plugin: JavaPlugin, driverClass: String, url: String): Boolean {
    return try {
        Class.forName(driverClass)
        true
    } catch (_: Throwable) {
        plugin.logger.severe("JDBC driver class '$driverClass' could not be loaded for url: $url")
        false
    }
}

private fun driverInstallHint(url: String): String {
    val lower = url.lowercase()
    val artifact = when {
        lower.startsWith("jdbc:mariadb:") -> "org.mariadb.jdbc:mariadb-java-client"
        lower.startsWith("jdbc:mysql:") -> "com.mysql:mysql-connector-j"
        lower.startsWith("jdbc:postgresql:") -> "org.postgresql:postgresql"
        lower.startsWith("jdbc:sqlite:") -> "org.xerial:sqlite-jdbc"
        else -> "<jdbc-driver-artifact>"
    }

    return "JDBC driver is missing. Add dependency '$artifact' to the host plugin (where MiniGamesCore.initialize() is called) " +
        "and make sure it is on that plugin classpath (shade into jar or Paper libraries)."

}


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
