package ru.joutak.minigames.tournament

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.Config
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.config.Messages
import ru.joutak.minigames.managers.MatchmakingManager
import ru.joutak.minigames.results.model.MatchResult
import ru.joutak.minigames.results.ResultsConfig
import ru.joutak.minigames.tournament.advance.TournamentAdvanceManager
import ru.joutak.minigames.tournament.plan.TournamentMatchPlanManager
import ru.joutak.minigames.tournament.seeding.TournamentSeedingManager
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager
import ru.joutak.minigames.tournament.model.TournamentDenyReason
import ru.joutak.minigames.tournament.model.TournamentGateResult
import ru.joutak.minigames.tournament.model.TournamentTeamCaptain
import ru.joutak.minigames.tournament.model.TournamentTeamProgress
import ru.joutak.minigames.tournament.storage.JdbcTournamentStorage
import ru.joutak.minigames.tournament.storage.TournamentStorage
import kotlin.math.max
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val TEAM_CAP_RESERVATION_TTL_MS: Long = 60_000L
private const val TEAM_UI_CACHE_TTL_MS: Long = 20_000L

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

    data class TeamUiInfo(
        val teamKey: String,
        val displayName: String?,
        val attemptsLeft: Int?,
        val won: Boolean?,
        val onlineCount: Int,
        val forceReady: Boolean,
    )

    private data class TeamUiCache(
        @Volatile var displayName: String? = null,
        @Volatile var attemptsLeft: Int? = null,
        @Volatile var won: Boolean? = null,
        @Volatile var lastFetchMs: Long = 0L,
        @Volatile var inFlight: Boolean = false,
    )

    private val teamUiCache = ConcurrentHashMap<String, TeamUiCache>()

    @Volatile
    private var bypassUuids: Set<UUID> = emptySet()


    enum class TournamentProgressMode {
        STANDARD,
        ELO,
    }

    private fun getTournamentProgressMode(): TournamentProgressMode {
        val raw = try {
            configuration.get(ConfigKeys.TOURNAMENT_MODE).trim().lowercase()
        } catch (_: Throwable) {
            "standard"
        }

        return when {
            raw == "standard" || raw == "elimination" -> TournamentProgressMode.STANDARD
            raw == "elo" || raw == "qualifier" || raw == "rating" -> TournamentProgressMode.ELO
            raw.contains("elo") || raw.contains("qual") || raw.contains("rating") -> TournamentProgressMode.ELO
            else -> TournamentProgressMode.STANDARD
        }
    }

    fun isEloTournamentMode(): Boolean {
        if (!enabled) return false
        return getTournamentProgressMode() == TournamentProgressMode.ELO
    }


    fun isPostMatchKickParticipantsEnabled(): Boolean {
        if (!enabled) return false
        return when (getTournamentProgressMode()) {
            TournamentProgressMode.ELO -> configuration.get(ConfigKeys.TOURNAMENT_ELO_POST_MATCH_KICK_PARTICIPANTS)
            TournamentProgressMode.STANDARD -> configuration.get(ConfigKeys.TOURNAMENT_POST_MATCH_KICK_PARTICIPANTS)
        }
    }

    fun getPostMatchKickDelayTicks(): Int {
        if (!enabled) return 0
        return when (getTournamentProgressMode()) {
            TournamentProgressMode.ELO -> configuration.get(ConfigKeys.TOURNAMENT_ELO_POST_MATCH_KICK_DELAY_TICKS)
            TournamentProgressMode.STANDARD -> configuration.get(ConfigKeys.TOURNAMENT_POST_MATCH_KICK_DELAY_TICKS)
        }
    }

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
            teamUiCache.clear()
            pendingSlotsByPlayer.clear()
            pendingCountByTeamKey.clear()
            return
        }

        forceReadyTeams.clear()
        preLoginTeamKeyCache.clear()
        onlineTeamKeyByPlayer.clear()
        onlineCountByTeamKey.clear()
        teamUiCache.clear()
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
        teamUiCache.clear()
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

                val participating = teamKeyByTeamId.values.toSet()
                val cacheNow = System.currentTimeMillis()

                if (getTournamentProgressMode() == TournamentProgressMode.ELO) {
                    val minAttempts = configuration.get(ConfigKeys.TOURNAMENT_ELO_MIN_ATTEMPTS)
                    val baseAttempts = max(defaultAttempts, minAttempts)

                    for (teamKey in participating) {
                        s.getOrCreateProgress(eventId, stage, teamKey, baseAttempts)
                        val updated = try {
                            s.ensureMinAttempts(eventId, stage, teamKey, minAttempts)
                        } catch (_: Throwable) {
                            s.getProgress(eventId, stage, teamKey)
                        }

                        if (updated != null) {
                            val c = teamUiCache[teamKey]
                            if (c != null) {
                                c.attemptsLeft = updated.attemptsLeft
                                c.won = updated.won
                                c.lastFetchMs = cacheNow
                            }
                        }

                        // forceReady should not be sticky between matches
                        forceReadyTeams.remove("$eventId|$teamKey")
                    }

                    val autoRecalc = configuration.get(ConfigKeys.TOURNAMENT_ELO_AUTO_RECALC)
                    val announce = configuration.get(ConfigKeys.TOURNAMENT_ELO_ANNOUNCE_AFTER_MATCH)

                    if (autoRecalc && TournamentQualifierManager.isInitialized()) {
                        val teamKeyByTeamIdSnapshot = teamKeyByTeamId.toMap()
                        TournamentQualifierManager.recalcAsync().thenAccept {
                            if (announce) {
                                announceEloAfterMatch(result, teamKeyByTeamIdSnapshot)
                            }
                        }
                    }

                    return@supplyAsync true
                }

                // Standard: decrement attempts for all participating teams.
                val becameIneligible = HashMap<String, TournamentDenyReason>()
                for (teamKey in participating) {
                    s.getOrCreateProgress(eventId, stage, teamKey, defaultAttempts)
                    val updated = s.decrementAttempts(eventId, stage, teamKey, 1)
                    if (updated != null) {
                        val c = teamUiCache[teamKey]
                        if (c != null) {
                            c.attemptsLeft = updated.attemptsLeft
                            c.won = updated.won
                            c.lastFetchMs = cacheNow
                        }


                        if (updated.attemptsLeft <= 0) {
                            becameIneligible[teamKey] = TournamentDenyReason.NO_ATTEMPTS
                        }
                    }

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
                        val c = teamUiCache[winnerKey]
                        if (c != null) {
                            c.won = true
                            c.lastFetchMs = System.currentTimeMillis()
                        }

                        becameIneligible[winnerKey] = TournamentDenyReason.WINNER
                    }
                }

                if (becameIneligible.isNotEmpty()) {
                    val delayTicks = configuration.get(ConfigKeys.TOURNAMENT_POST_MATCH_ENFORCE_DELAY_TICKS).toLong()
                    val snapshot = HashMap(becameIneligible)
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        enforceIneligibleTeams(snapshot)
                    }, delayTicks)
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

    fun getTeamUiInfo(teamKey: String): TeamUiInfo {
        val c = teamUiCache[teamKey]
        return TeamUiInfo(
            teamKey = teamKey,
            displayName = c?.displayName,
            attemptsLeft = c?.attemptsLeft,
            won = c?.won,
            onlineCount = getOnlineCount(teamKey),
            forceReady = isTeamForceReady(teamKey),
        )
    }

    fun getCachedTeamDisplayName(teamKey: String): String? = teamUiCache[teamKey]?.displayName

    fun scheduleTeamUiRefresh(teamKey: String, force: Boolean = false) {
        if (!enabled) return
        if (teamKey.isBlank()) return

        val s = storage ?: return
        if (!initOk) return

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        val stage = configuration.get(ConfigKeys.TOURNAMENT_STAGE).trim()
        if (eventId.isBlank() || stage.isBlank()) return

        val now = System.currentTimeMillis()
        val cache = teamUiCache.computeIfAbsent(teamKey) { TeamUiCache() }
        if (!force && now - cache.lastFetchMs < TEAM_UI_CACHE_TTL_MS) return
        if (cache.inFlight) return
        cache.inFlight = true

        CompletableFuture.runAsync({
            try {
                val name = try { s.getTeamDisplayName(eventId, teamKey) } catch (_: Throwable) { null }
                val defaultAttempts = configuration.get(ConfigKeys.TOURNAMENT_DEFAULT_ATTEMPTS)
                val progress = try { s.getProgress(eventId, stage, teamKey) } catch (_: Throwable) { null }
                    ?: try { s.getOrCreateProgress(eventId, stage, teamKey, defaultAttempts) } catch (_: Throwable) { null }

                if (!name.isNullOrBlank()) {
                    cache.displayName = name
                }
                if (progress != null) {
                    cache.attemptsLeft = progress.attemptsLeft
                    cache.won = progress.won
                }
                cache.lastFetchMs = System.currentTimeMillis()
            } finally {
                cache.inFlight = false
            }
        }, executor)
    }

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
        scheduleTeamUiRefresh(teamKey)
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

    /**
     * Lightweight eligibility check used by tournament matchmaking:
     * teams with 0 attempts or already won should not be assigned to instances.
     * Uses cached UI/progress values (no DB queries).
     */
    fun isTeamEligibleForQueue(teamKey: String): Boolean {
        if (!enabled) return true
        if (teamKey.isBlank()) return false


        if (isEloTournamentMode()) {
            // Elo tournament doesn't exclude teams by attempts/win state.
            scheduleTeamUiRefresh(teamKey)
            return true
        }

        val c = teamUiCache[teamKey]
        if (c == null) {
            // Try to refresh asynchronously, but don't block matchmaking.
            scheduleTeamUiRefresh(teamKey)
            return true
        }

        if (c.won == true) return false
        val attempts = c.attemptsLeft
        if (attempts != null && attempts <= 0) return false

        // Opportunistically refresh stale cache.
        scheduleTeamUiRefresh(teamKey)
        return true
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

    data class RosterReloadStats(
        val ok: Boolean,
        val scannedPlayers: Int,
        val bypassPlayers: Int,
        val participants: Int,
        val notParticipants: Int,
        val message: String,
    )

    /**
     * Reloads tournament roster (team_key mapping) for ONLINE players, without plugin reload.
     *
     * - Clears caches: pre-login cache, pending reservations, team UI cache.
     * - Re-resolves team_key for all online non-bypass players from tournament storage.
     * - Removes players that are no longer participants from queues/waiting teams.
     *
     * Must be called from the main thread.
     */
    fun reloadOnlineRosterAsync(onComplete: (RosterReloadStats) -> Unit) {
        if (!enabled) {
            onComplete(RosterReloadStats(ok = false, scannedPlayers = 0, bypassPlayers = 0, participants = 0, notParticipants = 0, message = "Tournament is disabled"))
            return
        }

        val s = storage
        if (!initOk || s == null) {
            onComplete(RosterReloadStats(ok = false, scannedPlayers = 0, bypassPlayers = 0, participants = 0, notParticipants = 0, message = "Tournament storage is not initialized"))
            return
        }

        val eventId = configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID).trim()
        if (eventId.isBlank()) {
            onComplete(RosterReloadStats(ok = false, scannedPlayers = 0, bypassPlayers = 0, participants = 0, notParticipants = 0, message = "tournament.event_id is empty"))
            return
        }

        // Snapshot online players on main thread (Bukkit API).
        val snapshot = Bukkit.getOnlinePlayers().map { it.uniqueId to it.name }

        CompletableFuture.supplyAsync({
            val resolved = HashMap<UUID, String>()
            var bypass = 0
            var notParticipant = 0

            for ((uuid, name) in snapshot) {
                if (uuid in bypassUuids) {
                    bypass++
                    continue
                }

                val tk = try {
                    s.findTeamKey(eventId, uuid, name)
                } catch (_: Throwable) {
                    null
                }

                if (tk.isNullOrBlank()) {
                    notParticipant++
                } else {
                    resolved[uuid] = tk
                }
            }

            Triple(resolved, bypass, notParticipant)
        }, executor).whenComplete { triple, throwable ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (throwable != null) {
                    onComplete(
                        RosterReloadStats(
                            ok = false,
                            scannedPlayers = snapshot.size,
                            bypassPlayers = 0,
                            participants = 0,
                            notParticipants = 0,
                            message = "Failed to reload roster: ${throwable.message}",
                        )
                    )
                    return@Runnable
                }

                val (resolved, bypass, notParticipant) = triple

                // Clear caches.
                preLoginTeamKeyCache.clear()
                pendingSlotsByPlayer.clear()
                pendingCountByTeamKey.clear()
                teamUiCache.clear()

                // Rebuild online mapping from scratch.
                onlineTeamKeyByPlayer.clear()
                onlineCountByTeamKey.clear()

                // Apply resolved team keys.
                for ((uuid, teamKey) in resolved) {
                    markOnline(uuid, teamKey)
                }

                // Remove players that are no longer participants from queues/waiting teams.
                if (notParticipant > 0) {
                    for ((uuid, _) in snapshot) {
                        if (uuid in bypassUuids) continue
                        if (resolved.containsKey(uuid)) continue
                        val p = Bukkit.getPlayer(uuid) ?: continue
                        MatchmakingManager.removePlayer(p)
                    }
                }

                // Rebuild waiting assignments to reflect new team->player mapping.
                if (MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_ENABLED)) {
                    MatchmakingManager.rebuildTournamentWaitingAssignments()
                }

                onComplete(
                    RosterReloadStats(
                        ok = true,
                        scannedPlayers = snapshot.size,
                        bypassPlayers = bypass,
                        participants = resolved.size,
                        notParticipants = notParticipant,
                        message = "Roster reloaded",
                    )
                )
            })
        }
    }

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

            val seededAllowed = TournamentSeedingManager.isTeamAllowed(plugin, eventId, stage, teamKey)
            if (seededAllowed != null) {
                if (!seededAllowed) {
                    return TournamentGateResult(false, teamKey = teamKey, denyReason = TournamentDenyReason.NOT_QUALIFIED)
                }
            } else {
                val advanceAllowed = TournamentAdvanceManager.isTeamAllowed(plugin, eventId, stage, teamKey)
                if (advanceAllowed == false) {
                    return TournamentGateResult(false, teamKey = teamKey, denyReason = TournamentDenyReason.NOT_QUALIFIED)
                }
            }

            val mode = getTournamentProgressMode()

            // Optional hardcoded match plan (standard mode only): if present for this stage,
            // only teams from ACTIVE planned matches are allowed to participate.
            if (mode == TournamentProgressMode.STANDARD) {
                val allowedByPlan = TournamentMatchPlanManager.isTeamAllowed(plugin, eventId, stage, teamKey)
                if (allowedByPlan == false) {
                    return TournamentGateResult(false, teamKey = teamKey, denyReason = TournamentDenyReason.NOT_QUALIFIED)
                }
            }
            val minAttempts = if (mode == TournamentProgressMode.ELO) {
                configuration.get(ConfigKeys.TOURNAMENT_ELO_MIN_ATTEMPTS)
            } else {
                0
            }
            val baseAttempts = if (mode == TournamentProgressMode.ELO) {
                max(defaultAttempts, minAttempts)
            } else {
                defaultAttempts
            }

            val progress = s.getOrCreateProgress(eventId, stage, teamKey, baseAttempts)

            if (mode == TournamentProgressMode.ELO) {
                if (progress.attemptsLeft < minAttempts) {
                    try {
                        s.ensureMinAttempts(eventId, stage, teamKey, minAttempts)
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
                // Ensure UI/scoreboard gets a chance to refresh soon.
                scheduleTeamUiRefresh(teamKey)
                return TournamentGateResult(true, teamKey = teamKey)
            }

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

   
    private fun enforceIneligibleTeams(ineligibleByTeam: Map<String, TournamentDenyReason>) {
        if (!enabled) return
        if (ineligibleByTeam.isEmpty()) return

        val bypassPerm = configuration.get(ConfigKeys.TOURNAMENT_BYPASS_PERMISSION)
        val kickEnabled = configuration.get(ConfigKeys.TOURNAMENT_POST_MATCH_KICK_PARTICIPANTS)
        val kickDelayTicks = configuration.get(ConfigKeys.TOURNAMENT_POST_MATCH_KICK_DELAY_TICKS).toLong().coerceAtLeast(0L)

        for (player in Bukkit.getOnlinePlayers()) {
            if (!player.isOnline) continue
            if (isBypassUuid(player.uniqueId) || (bypassPerm.isNotBlank() && player.hasPermission(bypassPerm))) continue

            val teamKey = getCachedTeamKey(player.uniqueId) ?: continue
            val reason = ineligibleByTeam[teamKey] ?: continue

            // Remove from matchmaking/queue to prevent further games.
            MatchmakingManager.removePlayer(player)

            if (!kickEnabled) continue

            if (kickDelayTicks > 0) {
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (!player.isOnline) return@Runnable
                    val tk = getCachedTeamKey(player.uniqueId)
                    if (tk != null && ineligibleByTeam.containsKey(tk)) {
                        player.kick(denyKickMessageComponent(reason))
                    }
                }, kickDelayTicks)
            } else {
                player.kick(denyKickMessageComponent(reason))
            }
        }
    }

    private fun announceEloAfterMatch(result: ru.joutak.minigames.results.model.MatchResult, teamKeyByTeamId: Map<Int, String>) {
        if (!enabled) return
        if (getTournamentProgressMode() != TournamentProgressMode.ELO) return
        if (!TournamentQualifierManager.isInitialized()) return

        val entry = TournamentQualifierManager.getLastAudit().firstOrNull { it.matchId == result.matchId }
        val teamAudits = entry?.teams?.associateBy { it.teamKey } ?: emptyMap()
        val skippedReason = if (entry == null) {
            "нет записи аудита (recalc не включил этот матч — проверь event_id/stage и team_metrics)"
        } else if (entry.skipped) {
            entry.skippedReason ?: "skipped"
        } else {
            null
        }

        Bukkit.getScheduler().runTask(plugin, Runnable {
            for (pr in result.players) {
                val uuid = pr.playerUuid
                val player = Bukkit.getPlayer(uuid) ?: continue
                if (!player.isOnline) continue

                val tid = pr.teamId ?: continue
                val teamKey = teamKeyByTeamId[tid]?.trim().orEmpty()
                if (teamKey.isBlank()) continue

                val feedback = Messages.feedbackLinkComponent()

                if (skippedReason != null) {
                    val msg = Messages.prefixedComponent(
                        "messages.tournament.elo_rating_update_skipped",
                        mapOf("reason" to skippedReason)
                    )
                    if (msg != Component.empty()) {
                        player.sendMessage(msg)
                    } else {
                        player.sendMessage(Component.text("Elo: skipped ($skippedReason)"))
                    }
                    if (feedback != Component.empty()) {
                        player.sendMessage(feedback)
                    }
                    continue
                }

                val ta = teamAudits[teamKey] ?: continue

                val placeholders = mapOf(
                    "team_key" to teamKey,
                    "rating" to formatInt(ta.ratingAfter),
                    "delta" to formatSignedInt(ta.delta),
                    "place" to ta.place.toString(),
                    "paint_percent" to formatPercent(ta.paintPercent)
                )

                val msg = Messages.prefixedComponent("messages.tournament.elo_rating_update", placeholders)
                if (msg != Component.empty()) {
                    player.sendMessage(msg)
                } else {
                    player.sendMessage(Component.text("Elo: ${placeholders["rating"]} (${placeholders["delta"]})"))
                }

                if (feedback != Component.empty()) {
                    player.sendMessage(feedback)
                }
            }
        })
    }

    private fun formatInt(v: Double): String {
        val r = kotlin.math.round(v).toInt()
        return r.toString()
    }

    private fun formatSignedInt(v: Double): String {
        val r = kotlin.math.round(v).toInt()
        return if (r >= 0) "+$r" else r.toString()
    }

    private fun formatPercent(v: Double): String {
        val r = kotlin.math.round(v * 10.0) / 10.0
        return if (r == kotlin.math.round(r)) r.toInt().toString() else r.toString()
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
