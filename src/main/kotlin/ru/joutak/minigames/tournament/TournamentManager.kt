package ru.joutak.minigames.tournament

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.results.ResultsConfig
import ru.joutak.minigames.tournament.model.TournamentDenyReason
import ru.joutak.minigames.tournament.model.TournamentGateResult
import ru.joutak.minigames.tournament.model.TournamentTeamCaptain
import ru.joutak.minigames.tournament.storage.JdbcTournamentStorage
import ru.joutak.minigames.tournament.storage.TournamentStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TournamentManager {

    enum class ForceReadyDenyReason {
        NOT_PARTICIPANT,
        ONLY_CAPTAIN,
        ERROR,
    }

    data class ForceReadyToggleResult(
        val allowed: Boolean,
        val enabled: Boolean = false,
        val changed: Boolean = false,
        val teamKey: String? = null,
        val reason: ForceReadyDenyReason? = null,
    )

    @Volatile
    private var enabled: Boolean = false

    @Volatile
    private var initOk: Boolean = false

    private lateinit var plugin: JavaPlugin
    private lateinit var configuration: Config

    private var storage: TournamentStorage? = null

    private val forceReadyTeams: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    private val pendingLock = Any()

    private data class PendingEntry(
        val teamId: String,
        val teamKey: String,
        val createdAtMillis: Long,
    )

    private val pendingPlayersByTeam = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val pendingByPlayer: MutableMap<UUID, PendingEntry> = ConcurrentHashMap()

    private val onlinePlayersByTeam = ConcurrentHashMap<String, MutableSet<UUID>>()
    private val onlineTeamByPlayer: MutableMap<UUID, String> = ConcurrentHashMap()

    private const val PENDING_TTL_MILLIS: Long = 2 * 60 * 1000L

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
            forceReadyTeams.clear()
            pendingByPlayer.clear()
            pendingPlayersByTeam.clear()
            onlinePlayersByTeam.clear()
            onlineTeamByPlayer.clear()
            return
        }

        forceReadyTeams.clear()
        pendingByPlayer.clear()
        pendingPlayersByTeam.clear()
        onlinePlayersByTeam.clear()
        onlineTeamByPlayer.clear()

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
        forceReadyTeams.clear()
        pendingByPlayer.clear()
        pendingPlayersByTeam.clear()
        onlinePlayersByTeam.clear()
        onlineTeamByPlayer.clear()
        try {
            storage?.close()
        } catch (_: Throwable) {
        }
        storage = null
    }

    /**
     * Toggles "force ready" flag for the player's team.
     * Used to allow starting with incomplete roster; separated from /ready for safety.
     */
    fun toggleForceReady(playerUuid: UUID, playerName: String): ForceReadyToggleResult {
        if (!enabled) return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)

        val s = storage
        if (!initOk || s == null) {
            return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)
        }

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) {
            return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)
        }

        return try {
            val teamKey = s.findTeamKey(eventId, playerUuid, playerName)
                ?: return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.NOT_PARTICIPANT)

            if (!canManageForceReady(s, eventId, teamKey, playerUuid, playerName)) {
                return ForceReadyToggleResult(false, teamKey = teamKey, reason = ForceReadyDenyReason.ONLY_CAPTAIN)
            }

            val key = "$eventId|$teamKey"
            val nowEnabled = if (forceReadyTeams.contains(key)) {
                forceReadyTeams.remove(key)
                false
            } else {
                forceReadyTeams.add(key)
                true
            }

            ForceReadyToggleResult(
                allowed = true,
                enabled = nowEnabled,
                changed = true,
                teamKey = teamKey,
            )
        } catch (t: Throwable) {
            plugin.logger.severe("Tournament forceready failed for $playerName/$playerUuid: ${t.message}")
            plugin.logger.fine(t.stackTraceToString())
            ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)
        }
    }

    /** Clears "force ready" for player's team (used by /unready in tournament mode). */
    fun clearForceReady(playerUuid: UUID, playerName: String): ForceReadyToggleResult {
        if (!enabled) return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)

        val s = storage
        if (!initOk || s == null) {
            return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)
        }

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) {
            return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)
        }

        return try {
            val teamKey = s.findTeamKey(eventId, playerUuid, playerName)
                ?: return ForceReadyToggleResult(false, reason = ForceReadyDenyReason.NOT_PARTICIPANT)

            if (!canManageForceReady(s, eventId, teamKey, playerUuid, playerName)) {
                return ForceReadyToggleResult(false, teamKey = teamKey, reason = ForceReadyDenyReason.ONLY_CAPTAIN)
            }

            val key = "$eventId|$teamKey"
            val changed = forceReadyTeams.remove(key)

            ForceReadyToggleResult(
                allowed = true,
                enabled = false,
                changed = changed,
                teamKey = teamKey,
            )
        } catch (t: Throwable) {
            plugin.logger.severe("Tournament clear forceready failed for $playerName/$playerUuid: ${t.message}")
            plugin.logger.fine(t.stackTraceToString())
            ForceReadyToggleResult(false, reason = ForceReadyDenyReason.ERROR)
        }
    }

    fun isTeamForceReady(teamKey: String): Boolean {
        if (!enabled) return false
        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) return false
        return forceReadyTeams.contains("$eventId|$teamKey")
    }

    private fun canManageForceReady(
        storage: TournamentStorage,
        eventId: String,
        teamKey: String,
        actorUuid: UUID,
        actorName: String,
    ): Boolean {
        val cap = try {
            storage.getTeamCaptain(eventId, teamKey)
        } catch (_: Throwable) {
            null
        } ?: return true

        // If captain is online, only captain can toggle. If captain is offline (or not set), any team member may.
        if (cap.uuid != null) {
            val online = Bukkit.getPlayer(cap.uuid)?.isOnline == true
            if (online) return cap.uuid == actorUuid
        }

        if (cap.name != null) {
            val online = Bukkit.getPlayerExact(cap.name)?.isOnline == true
            if (online) return cap.name.equals(actorName, ignoreCase = true)
        }

        return true
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



    /**
     * Reserves a login slot for this team (online + pending prelogins) to prevent >N members from joining.
     * Used only when tournament.prelogin.strict=true.
     */
    fun tryReserveTeamSlot(uuid: UUID, teamKey: String): Boolean {
        if (!enabled) return true
        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) return true

        val teamId = "$eventId|$teamKey"
        val maxOnline = configuration.get(ConfigKeys.TOURNAMENT_MAX_ONLINE_PER_TEAM).coerceAtLeast(1)
        val now = System.currentTimeMillis()

        synchronized(pendingLock) {
            cleanupExpiredPendingLocked(now)
            // Drop duplicates for fast relogs.
            releasePendingLocked(uuid)

            val online = onlinePlayersByTeam[teamId]?.size ?: 0
            val pending = pendingPlayersByTeam[teamId]?.size ?: 0

            if (online + pending >= maxOnline) {
                return false
            }

            val entry = PendingEntry(teamId = teamId, teamKey = teamKey, createdAtMillis = now)
            pendingByPlayer[uuid] = entry
            pendingPlayersByTeam.computeIfAbsent(teamId) { ConcurrentHashMap.newKeySet<UUID>() }.add(uuid)
            return true
        }
    }

    /** Moves reserved player from pending to online; returns teamKey if reservation existed. */
    fun finalizeJoinFromPending(uuid: UUID): String? {
        if (!enabled) return null
        synchronized(pendingLock) {
            val entry = pendingByPlayer.remove(uuid) ?: return null
            pendingPlayersByTeam[entry.teamId]?.remove(uuid)
            if (pendingPlayersByTeam[entry.teamId]?.isEmpty() == true) {
                pendingPlayersByTeam.remove(entry.teamId)
            }

            onlineTeamByPlayer[uuid] = entry.teamId
            onlinePlayersByTeam.computeIfAbsent(entry.teamId) { ConcurrentHashMap.newKeySet<UUID>() }.add(uuid)
            return entry.teamKey
        }
    }

    /** Registers player as online for the given team (used when prelogin strict is disabled). */
    fun registerOnline(uuid: UUID, teamKey: String) {
        if (!enabled) return
        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) return

        val teamId = "$eventId|$teamKey"
        synchronized(pendingLock) {
            // If we had a pending reservation (e.g. strict gate was enabled earlier), drop it.
            releasePendingLocked(uuid)
            onlineTeamByPlayer[uuid] = teamId
            onlinePlayersByTeam.computeIfAbsent(teamId) { ConcurrentHashMap.newKeySet<UUID>() }.add(uuid)
        }
    }

    fun unregisterOnline(uuid: UUID) {
        if (!enabled) return
        synchronized(pendingLock) {
            releasePendingLocked(uuid)
            val teamId = onlineTeamByPlayer.remove(uuid) ?: return
            onlinePlayersByTeam[teamId]?.remove(uuid)
            if (onlinePlayersByTeam[teamId]?.isEmpty() == true) {
                onlinePlayersByTeam.remove(teamId)
            }
        }
    }

    fun releasePending(uuid: UUID) {
        if (!enabled) return
        synchronized(pendingLock) {
            releasePendingLocked(uuid)
        }
    }

    fun isTeamOnlineFull(teamKey: String): Boolean {
        if (!enabled) return false
        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) return false

        val teamId = "$eventId|$teamKey"
        val maxOnline = configuration.get(ConfigKeys.TOURNAMENT_MAX_ONLINE_PER_TEAM).coerceAtLeast(1)
        val now = System.currentTimeMillis()

        synchronized(pendingLock) {
            cleanupExpiredPendingLocked(now)
            val online = onlinePlayersByTeam[teamId]?.size ?: 0
            val pending = pendingPlayersByTeam[teamId]?.size ?: 0
            return online + pending >= maxOnline
        }
    }

    fun teamFullOnlineKickMessageLegacy(): String {
        val key = if (Messages.has("messages.tournament.team_full_online")) {
            "messages.tournament.team_full_online"
        } else {
            "messages.tournament.error"
        }
        return Messages.prefixedLegacyString(key)
    }

    fun teamFullOnlineKickMessageComponent(): Component {
        val key = if (Messages.has("messages.tournament.team_full_online")) {
            "messages.tournament.team_full_online"
        } else {
            "messages.tournament.error"
        }
        return Messages.prefixedComponent(key)
    }

    private fun cleanupExpiredPendingLocked(now: Long) {
        val it = pendingByPlayer.entries.iterator()
        while (it.hasNext()) {
            val (uuid, entry) = it.next()
            if (now - entry.createdAtMillis > PENDING_TTL_MILLIS) {
                it.remove()
                pendingPlayersByTeam[entry.teamId]?.remove(uuid)
                if (pendingPlayersByTeam[entry.teamId]?.isEmpty() == true) {
                    pendingPlayersByTeam.remove(entry.teamId)
                }
            }
        }
    }

    private fun releasePendingLocked(uuid: UUID) {
        val entry = pendingByPlayer.remove(uuid) ?: return
        pendingPlayersByTeam[entry.teamId]?.remove(uuid)
        if (pendingPlayersByTeam[entry.teamId]?.isEmpty() == true) {
            pendingPlayersByTeam.remove(entry.teamId)
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
