package ru.joutak.minigames.tournament

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.ResultsConfig
import ru.joutak.minigames.tournament.model.TournamentDenyReason
import ru.joutak.minigames.tournament.model.TournamentGateResult
import ru.joutak.minigames.tournament.model.TournamentTeamCaptain
import ru.joutak.minigames.tournament.storage.JdbcTournamentStorage
import ru.joutak.minigames.tournament.storage.TournamentStorage
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TEAM_CAP_RESERVATION_TTL_MS: Long = 60_000L

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

    private val forceReadyTeams: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Stores team_key resolved during pre-login (AsyncPlayerPreLoginEvent) to avoid a second DB query on join.
    private val preLoginTeamKeyCache = ConcurrentHashMap<UUID, String>()

    private data class PendingSlot(
        val teamKey: String,
        val reservedAtMs: Long,
    )

    /**
     * Pre-login slot reservations (strict mode). Protects against races when multiple members of the same team join.
     */
    private val pendingSlotsByPlayer = ConcurrentHashMap<UUID, PendingSlot>()
    private val pendingCountByTeamKey = ConcurrentHashMap<String, Int>()
    private val teamCapLock = Any()

    @Volatile
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tournament-updater").apply { isDaemon = true }
    }

    // Online participants (non-bypass) mapped to their tournament team_key.
    private val onlineTeamKeyByPlayer = ConcurrentHashMap<UUID, String>()

    // Online participant count per team_key.
    private val onlineCountByTeamKey = ConcurrentHashMap<String, Int>()

    @Volatile
    private var bypassUuids: Set<UUID> = emptySet()

    fun initialize(
        plugin: JavaPlugin,
        configuration: Config,
        resultsFile: File,
    ) {
        this.plugin = plugin
        this.configuration = configuration

        if (executor.isShutdown || executor.isTerminated) {
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "tournament-updater").apply { isDaemon = true }
            }
        }

        enabled = configuration.get(ConfigKeys.TOURNAMENT_ENABLED)
        if (!enabled) {
            initOk = false
            storage = null
            bypassUuids = emptySet()
            forceReadyTeams.clear()
            preLoginTeamKeyCache.clear()
            onlineTeamKeyByPlayer.clear()
            onlineCountByTeamKey.clear()
            pendingSlotsByPlayer.clear()
            pendingCountByTeamKey.clear()
            return
        }

        forceReadyTeams.clear()
        preLoginTeamKeyCache.clear()
        onlineTeamKeyByPlayer.clear()
        onlineCountByTeamKey.clear()
        pendingSlotsByPlayer.clear()
        pendingCountByTeamKey.clear()

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
        preLoginTeamKeyCache.clear()
        onlineTeamKeyByPlayer.clear()
        onlineCountByTeamKey.clear()
        pendingSlotsByPlayer.clear()
        pendingCountByTeamKey.clear()
        try {
            storage?.close()
        } catch (_: Throwable) {
        }
        storage = null

        executor.shutdown()
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /**
     * Applies match outcome to the tournament progress: decrements attempts for all participating teams
     * and marks winner team as won.
     *
     * This method is async-safe and does not touch Bukkit API.
     */
    fun applyMatchResult(result: MatchResult): CompletableFuture<Boolean> {
        if (!enabled) return CompletableFuture.completedFuture(false)
        if (!initOk) return CompletableFuture.completedFuture(false)
        val s = storage ?: return CompletableFuture.completedFuture(false)

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        val stage = configuration.get(ConfigKeys.TOURNAMENT_STAGE).trim()
        if (eventId.isBlank() || stage.isBlank()) return CompletableFuture.completedFuture(false)

        val defaultAttempts = configuration.get(ConfigKeys.TOURNAMENT_DEFAULT_ATTEMPTS)

        return CompletableFuture.supplyAsync({
            try {
                val teamKeyByTeamId = HashMap<Int, String>()

                // Map teamId -> teamKey using any player in that team.
                for (p in result.players) {
                    val teamId = p.teamId ?: continue
                    if (teamKeyByTeamId.containsKey(teamId)) continue

                    val cached = onlineTeamKeyByPlayer[p.playerUuid]
                    if (!cached.isNullOrBlank()) {
                        teamKeyByTeamId[teamId] = cached
                        continue
                    }

                    val cachedPreLogin = preLoginTeamKeyCache[p.playerUuid]
                    if (!cachedPreLogin.isNullOrBlank()) {
                        teamKeyByTeamId[teamId] = cachedPreLogin
                        continue
                    }

                    val name = p.playerName?.trim().orEmpty()
                    // Important: even if playerName is missing in results, UUID is usually already bound
                    // in tournament_team_member by the tournament gate (pre-login). That prevents attempts
                    // abuse by disconnecting all players before results are recorded.
                    val resolved = try {
                        s.findTeamKey(eventId, p.playerUuid, name)
                    } catch (_: Throwable) {
                        null
                    }
                    if (!resolved.isNullOrBlank()) {
                        teamKeyByTeamId[teamId] = resolved
                    }
                }

                if (teamKeyByTeamId.isEmpty()) return@supplyAsync false

                // Decrement attempts for all participating teams.
                val participating = teamKeyByTeamId.values.toSet()
                for (teamKey in participating) {
                    s.getOrCreateProgress(eventId, stage, teamKey, defaultAttempts)
                    s.decrementAttempts(eventId, stage, teamKey, 1)

                    // forceReady should not be sticky between matches
                    forceReadyTeams.remove("$eventId|$teamKey")
                }

                // Determine winner teamId from teams list first.
                val winnerTeamId = result.teams.firstOrNull { it.isWinner || it.placement == 1 }?.teamId
                    ?: result.players.firstOrNull { it.isWinner }?.teamId

                if (winnerTeamId != null) {
                    val winnerKey = teamKeyByTeamId[winnerTeamId]
                    if (!winnerKey.isNullOrBlank()) {
                        s.getOrCreateProgress(eventId, stage, winnerKey, defaultAttempts)
                        s.setWon(eventId, stage, winnerKey, true)
                    }
                }

                true
            } catch (t: Throwable) {
                plugin.logger.severe("Failed to apply tournament match result: ${t.message}")
                plugin.logger.fine(t.stackTraceToString())
                false
            }
        }, executor)
    }

    /**
     * Caches team_key resolved on AsyncPlayerPreLoginEvent.
     * Thread-safe: may be called from async thread.
     */
    fun rememberPreLoginTeamKey(uuid: UUID, teamKey: String) {
        if (!enabled) return
        if (teamKey.isBlank()) return
        preLoginTeamKeyCache[uuid] = teamKey
    }

    /**
     * Consumes (and removes) cached team_key from pre-login stage.
     */
    fun consumePreLoginTeamKey(uuid: UUID): String? = preLoginTeamKeyCache.remove(uuid)

    /**
     * Strict-mode prelogin reservation: try to reserve a slot for the given team.
     * Must be callable from async thread.
     */
    fun tryReserveTeamSlot(uuid: UUID, teamKey: String): Boolean {
        if (!enabled) return false
        if (teamKey.isBlank()) return false

        val now = System.currentTimeMillis()
        synchronized(teamCapLock) {
            cleanupExpiredPendingUnsafe(now)

            // Already reserved.
            val existing = pendingSlotsByPlayer[uuid]
            if (existing != null) return existing.teamKey == teamKey

            val maxOnline = configuration.get(ConfigKeys.TOURNAMENT_MAX_ONLINE_PER_TEAM).coerceAtLeast(1)
            val online = onlineCountByTeamKey[teamKey] ?: 0
            val pending = pendingCountByTeamKey[teamKey] ?: 0
            if (online + pending >= maxOnline) return false

            pendingSlotsByPlayer[uuid] = PendingSlot(teamKey, now)
            pendingCountByTeamKey[teamKey] = pending + 1
            return true
        }
    }

    /**
     * Converts a reserved strict-mode slot into an online slot on PlayerJoin.
     * Returns the teamKey if there was a reservation.
     */
    fun finalizeJoinFromPending(uuid: UUID): String? {
        if (!enabled) return null

        val slot = synchronized(teamCapLock) {
            cleanupExpiredPendingUnsafe(System.currentTimeMillis())

            val s = pendingSlotsByPlayer.remove(uuid) ?: return@synchronized null
            pendingCountByTeamKey.compute(s.teamKey) { _, v ->
                val next = (v ?: 0) - 1
                if (next <= 0) null else next
            }
            s
        } ?: return null

        // Prevent stale cache growth.
        preLoginTeamKeyCache.remove(uuid)

        markOnline(uuid, slot.teamKey)
        return slot.teamKey
    }

    /**
     * Releases pending strict-mode reservation (bypass or login failure cleanup).
     */
    fun releasePending(uuid: UUID) {
        if (!enabled) return
        synchronized(teamCapLock) {
            cleanupExpiredPendingUnsafe(System.currentTimeMillis())
            releasePendingUnsafe(uuid)
        }
        preLoginTeamKeyCache.remove(uuid)
    }

    private fun releasePendingUnsafe(uuid: UUID) {
        val slot = pendingSlotsByPlayer.remove(uuid) ?: return
        pendingCountByTeamKey.compute(slot.teamKey) { _, v ->
            val next = (v ?: 0) - 1
            if (next <= 0) null else next
        }
    }

    private fun cleanupExpiredPendingUnsafe(now: Long) {
        val toRemove = pendingSlotsByPlayer.entries
            .filter { now - it.value.reservedAtMs > TEAM_CAP_RESERVATION_TTL_MS }
            .map { it.key }
        for (uuid in toRemove) {
            releasePendingUnsafe(uuid)
        }
    }

    /**
     * Resolves and caches tournament team_key for the player (non-bypass).
     * May query DB; call from main thread rarely (e.g. once on join).
     */
    fun resolveTeamKey(uuid: UUID, name: String): String? {
        if (!enabled) return null

        onlineTeamKeyByPlayer[uuid]?.let { return it }
        preLoginTeamKeyCache[uuid]?.let { return it }

        val s = storage ?: return null
        if (!initOk) return null

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) return null

        return try {
            s.findTeamKey(eventId, uuid, name)?.also { key ->
                if (key.isNotBlank()) {
                    onlineTeamKeyByPlayer[uuid] = key
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns cached team_key for an online participant, if known.
     */
    fun getCachedTeamKey(uuid: UUID): String? = onlineTeamKeyByPlayer[uuid]

    /**
     * Online count of players for a given team_key.
     */
    fun getOnlineCount(teamKey: String): Int = onlineCountByTeamKey[teamKey] ?: 0

    /**
     * Marks a player as online participant of a given team_key (updates counters).
     */
    fun markOnline(uuid: UUID, teamKey: String) {
        if (!enabled) return
        if (teamKey.isBlank()) return

        val prev = onlineTeamKeyByPlayer.put(uuid, teamKey)
        if (prev != null && prev != teamKey) {
            // Decrement previous key count if it changed (should not happen normally).
            onlineCountByTeamKey.compute(prev) { _, v ->
                val next = (v ?: 0) - 1
                if (next <= 0) null else next
            }
        }

        onlineCountByTeamKey.compute(teamKey) { _, v -> (v ?: 0) + 1 }
    }

    /**
     * Unmarks player from online participant tracking.
     */
    fun unmarkOnline(uuid: UUID) {
        val key = onlineTeamKeyByPlayer.remove(uuid) ?: return
        onlineCountByTeamKey.compute(key) { _, v ->
            val next = (v ?: 0) - 1
            if (next <= 0) null else next
        }
    }

    fun teamFullOnlineKickMessageLegacy(): String {
        val maxOnline = configuration.get(ConfigKeys.TOURNAMENT_MAX_ONLINE_PER_TEAM).coerceAtLeast(1)
        return Messages.prefixedLegacyString(
            "messages.tournament.team_full_online",
            mapOf("max" to maxOnline.toString())
        )
    }

    /**
     * Clears all cached participant team keys (used after major reshuffles).
     */
    fun clearPreLoginCache(uuid: UUID) {
        preLoginTeamKeyCache.remove(uuid)
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
