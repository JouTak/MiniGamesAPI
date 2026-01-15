package ru.joutak.minigames.tournament

import net.kyori.adventure.text.Component
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.results.ResultsConfig
import ru.joutak.minigames.tournament.model.TournamentDenyReason
import ru.joutak.minigames.tournament.model.TournamentGateResult
import ru.joutak.minigames.tournament.storage.JdbcTournamentStorage
import ru.joutak.minigames.tournament.storage.TournamentStorage
import java.io.File
import java.util.UUID

object TournamentManager {

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var initOk: Boolean = false

    private lateinit var plugin: JavaPlugin
    private lateinit var configuration: Config

    private var storage: TournamentStorage? = null

    @Volatile
    private var bypassUuids: Set<UUID> = emptySet()

    fun initialize(
        plugin: JavaPlugin,
        configuration: Config,
        resultsFile: File,
    ) {
        this.plugin = plugin
        this.configuration = configuration

        enabled = configuration.get(ConfigKeys.TOURNAMENT_ENABLED)
        if (!enabled) {
            initOk = false
            storage = null
            bypassUuids = emptySet()
            return
        }

        bypassUuids = parseBypassUuids(configuration.get(ConfigKeys.TOURNAMENT_BYPASS_UUIDS))

        val cfg = ResultsConfig(resultsFile)
        val url = cfg.jdbcUrl().trim()
        if (url.isBlank()) {
            initOk = false
            storage = null
            plugin.logger.severe("Tournament enabled, but results.jdbc.url is empty. Tournament gate will deny all non-bypass players.")
            return
        }

        val configuredDriver = cfg.jdbcDriver().trim()
        val resolvedDriver = if (configuredDriver.isNotBlank()) configuredDriver else guessDriverForUrl(url)

        if (resolvedDriver.isNotBlank()) {
            try {
                Class.forName(resolvedDriver)
            } catch (t: Throwable) {
                initOk = false
                storage = null
                plugin.logger.severe("Tournament enabled, but JDBC driver '$resolvedDriver' could not be loaded for url: $url")
                plugin.logger.severe(t.stackTraceToString())
                return
            }
        }

        try {
            val s = JdbcTournamentStorage(
                jdbcUrl = url,
                username = cfg.jdbcUsername(),
                password = cfg.jdbcPassword(),
                connectTimeoutSeconds = cfg.connectTimeoutSeconds(),
            )

            s.ensureSchema(cfg.schemaAutoCreate())

            storage = s
            initOk = true

            plugin.logger.info(
                "Tournament mode enabled: event_id=${configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID)}, " +
                    "stage=${configuration.get(ConfigKeys.TOURNAMENT_STAGE)}, " +
                    "previous_stage=${configuration.get(ConfigKeys.TOURNAMENT_PREVIOUS_STAGE)}"
            )
        } catch (t: Throwable) {
            initOk = false
            storage = null
            plugin.logger.severe("Failed to initialize tournament storage: ${t.message}")
            plugin.logger.severe(t.stackTraceToString())
        }
    }

    fun shutdown() {
        enabled = false
        initOk = false
        bypassUuids = emptySet()
        try {
            storage?.close()
        } catch (_: Throwable) {
        }
        storage = null
    }

    fun isEnabled(): Boolean = enabled

    fun isBypassUuid(uuid: UUID): Boolean = uuid in bypassUuids

    fun checkAccess(uuid: UUID, name: String): TournamentGateResult {
        if (!enabled) return TournamentGateResult(true)
        if (uuid in bypassUuids) return TournamentGateResult(true)

        val s = storage
        if (!initOk || s == null) {
            return TournamentGateResult(false, denyReason = TournamentDenyReason.ERROR)
        }

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        val stage = configuration.get(ConfigKeys.TOURNAMENT_STAGE).trim()
        val prevStage = configuration.get(ConfigKeys.TOURNAMENT_PREVIOUS_STAGE).trim()
        val defaultAttempts = configuration.get(ConfigKeys.TOURNAMENT_DEFAULT_ATTEMPTS)

        try {
            val teamKey = s.findTeamKey(eventId, uuid, name)
                ?: return TournamentGateResult(false, denyReason = TournamentDenyReason.NOT_PARTICIPANT)

            if (prevStage.isNotBlank()) {
                val prev = s.getProgress(eventId, prevStage, teamKey)
                if (prev == null || !prev.won) {
                    return TournamentGateResult(false, teamKey = teamKey, denyReason = TournamentDenyReason.NOT_QUALIFIED)
                }
            }

            val progress = s.getOrCreateProgress(eventId, stage, teamKey, defaultAttempts)

            if (progress.won) {
                return TournamentGateResult(false, teamKey = teamKey, denyReason = TournamentDenyReason.WINNER)
            }

            if (progress.attemptsLeft <= 0) {
                return TournamentGateResult(false, teamKey = teamKey, denyReason = TournamentDenyReason.NO_ATTEMPTS)
            }

            return TournamentGateResult(true, teamKey = teamKey)
        } catch (t: Throwable) {
            plugin.logger.severe("Tournament gate check failed for $name/$uuid: ${t.message}")
            plugin.logger.fine(t.stackTraceToString())
            return TournamentGateResult(false, denyReason = TournamentDenyReason.ERROR)
        }
    }

    fun denyKickMessageLegacy(reason: TournamentDenyReason?): String {
        val path = messagePathFor(reason)
        return Messages.prefixedLegacyString(path)
    }

    fun denyKickMessageComponent(reason: TournamentDenyReason?): Component {
        val path = messagePathFor(reason)
        return Messages.prefixedComponent(path)
    }

    private fun messagePathFor(reason: TournamentDenyReason?): String {
        return when (reason) {
            TournamentDenyReason.NOT_PARTICIPANT -> "messages.tournament.not_participant"
            TournamentDenyReason.NO_ATTEMPTS -> "messages.tournament.no_attempts"
            TournamentDenyReason.WINNER -> "messages.tournament.winner"
            TournamentDenyReason.NOT_QUALIFIED -> "messages.tournament.not_qualified"
            TournamentDenyReason.ERROR, null -> "messages.tournament.error"
        }
    }

    private fun parseBypassUuids(list: List<String>): Set<UUID> {
        if (list.isEmpty()) return emptySet()
        val out = HashSet<UUID>()
        for (raw in list) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            try {
                out.add(UUID.fromString(s))
            } catch (_: Throwable) {
                // ignore
            }
        }
        return out
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
}
