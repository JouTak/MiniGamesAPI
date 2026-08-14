package ru.joutak.minigames.tournament.qualifier

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.MiniGamesCore
import ru.joutak.minigames.config.ConfigKeys
import ru.joutak.minigames.tournament.TournamentManager
import ru.joutak.minigames.results.ResultsManager
import ru.joutak.minigames.results.model.MatchTeamsSnapshot
import ru.joutak.minigames.results.model.Metric
import ru.joutak.minigames.results.model.CompletionStatus
import ru.joutak.minigames.tournament.qualifier.model.QualifierMatchAudit
import ru.joutak.minigames.tournament.qualifier.model.QualifierMatchTeamAudit
import ru.joutak.minigames.tournament.qualifier.model.QualifierSnapshot
import ru.joutak.minigames.tournament.qualifier.model.QualifierTeamRow
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.round

object TournamentQualifierManager {

    private const val TEAM_KEY_METRIC_KEY = "team_key"

    private data class EffectiveContext(val eventId: String, val stage: String, val overridden: Boolean)

    private fun resolveEffectiveContext(cfg: TournamentQualifierConfig): EffectiveContext {
        var eventId = cfg.eventId.trim()
        var stage = cfg.stage.trim()
        var overridden = false

        if (TournamentManager.isEloTournamentMode()) {
            val tEvent = runCatching { MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_EVENT_ID) }.getOrNull()?.trim().orEmpty()
            val tStage = runCatching { MiniGamesCore.configuration.get(ConfigKeys.TOURNAMENT_STAGE) }.getOrNull()?.trim().orEmpty()
            if (tEvent.isNotBlank() && tStage.isNotBlank()) {
                overridden = (eventId != tEvent || stage != tStage)
                eventId = tEvent
                stage = tStage
            }
        }

        return EffectiveContext(eventId, stage, overridden)
    }


    @Volatile
    private var initialized: Boolean = false

    private lateinit var plugin: JavaPlugin
    private lateinit var file: File

    @Volatile
    private var config: TournamentQualifierConfig = TournamentQualifierConfig.DEFAULT

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var lastSnapshot: QualifierSnapshot? = null

    @Volatile
    private var lastAudit: List<QualifierMatchAudit> = emptyList()

    data class TeamEloInfo(val rating: Int, val delta: Int?)

    @Volatile
    private var teamEloUiCache: Map<String, TeamEloInfo> = emptyMap()

    @Volatile
    private var recalcInFlight: Boolean = false

    private val recalcLock = Any()
    private var pendingRecalcFuture: CompletableFuture<QualifierSnapshot?>? = null

    @Volatile
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "tournament-qualifier").apply { isDaemon = true }
    }

    fun initialize(plugin: JavaPlugin, file: File) {
        this.plugin = plugin
        this.file = file

        if (executor.isShutdown || executor.isTerminated) {
            executor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "tournament-qualifier").apply { isDaemon = true }
            }
        }

        initialized = true
        reload()
    }

    fun isInitialized(): Boolean = initialized

    fun getConfig(): TournamentQualifierConfig = config

    fun getLastError(): String? = lastError

    fun getSnapshot(): QualifierSnapshot? = lastSnapshot

    fun getLastAudit(): List<QualifierMatchAudit> = lastAudit

    fun getTeamEloInfo(teamKey: String): TeamEloInfo? {
        val key = teamKey.trim().lowercase()
        if (key.isEmpty()) return null
        return teamEloUiCache[key]
    }

    fun isRecalcInFlight(): Boolean = recalcInFlight

    fun reload(): Boolean {
        if (!initialized) return false

        try {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                // MiniGamesCore should normally create it, but keep a safe fallback.
                file.writeText(DEFAULT_YAML)
            }

            val yaml = YamlConfiguration.loadConfiguration(file)
            val defaults = loadDefaultYaml()

            val changed = applyMissingKeysFromDefaults(yaml, defaults)
            if (changed) {
                yaml.save(file)
            }

            config = TournamentQualifierConfig.fromYaml(yaml)
            lastError = null
            return true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            plugin.logger.severe("Failed to reload tournament_qualifier.yml: ${t.message}")
            plugin.logger.fine(t.stackTraceToString())
            return false
        }
    }

    fun lock(): Boolean {
        if (!initialized) return false

        return updateYamlAndReload { yaml ->
            val mode = TournamentQualifierConfig.LockingMode.fromConfig(yaml.getString("locking.mode"))
            yaml.set("locking.locked", true)
            if (mode == TournamentQualifierConfig.LockingMode.TIMESTAMP) {
                yaml.set("locking.locked_at", System.currentTimeMillis())
            }
            true
        }
    }

    fun unlock(): Boolean {
        if (!initialized) return false

        return updateYamlAndReload { yaml ->
            yaml.set("locking.locked", false)
            true
        }
    }

    /**
     * Recalculate qualifier standings from ResultsStorage.
     * Runs fully async, safe to call from commands.
     */
    fun recalcAsync(): CompletableFuture<QualifierSnapshot?> {
        if (!initialized) return CompletableFuture.completedFuture(null)

        synchronized(recalcLock) {
            if (recalcInFlight) {
                pendingRecalcFuture?.let { return it }
                return CompletableFuture<QualifierSnapshot?>().also { pendingRecalcFuture = it }
            }
            recalcInFlight = true
        }

        return scheduleRecalc()
    }

    private fun scheduleRecalc(): CompletableFuture<QualifierSnapshot?> {
        val current = CompletableFuture.supplyAsync({
            try {
                val cfg = config
                val ctx = resolveEffectiveContext(cfg)
                val eventId = ctx.eventId.trim()
                val stage = ctx.stage.trim()
                if (eventId.isBlank() || stage.isBlank()) {
                    lastError = "event_id/stage is blank"
                    return@supplyAsync null
                }

                if (!ResultsManager.isEnabled()) {
                    lastError = "Results storage disabled"
                    return@supplyAsync null
                }

                val cutoffMs = resolveCutoffMs(cfg)

                val calc = QualifierCalculator(cfg)
                val batchSize = 500

                var offset = 0
                var considered = 0
                var skipped = 0

                while (true) {
                    val batch = ResultsManager.loadMatchTeamsWithMetrics(
                        eventId = eventId,
                        stage = stage,
                        endedAtMaxInclusive = cutoffMs,
                        limit = batchSize,
                        offset = offset,
                    ).join()

                    if (batch.isEmpty()) break

                    for (m in batch) {
                        val applied = calc.applyMatch(m)
                        if (applied) {
                            considered++
                        } else {
                            skipped++
                        }
                    }

                    offset += batch.size
                }

                val snapshot = calc.buildSnapshot(
                    eventId = eventId,
                    stage = stage,
                    generatedAtMs = System.currentTimeMillis(),
                    consideredUntilMs = cutoffMs,
                    matchesConsidered = considered,
                    matchesSkipped = skipped,
                )

                lastSnapshot = snapshot
                lastAudit = calc.getAudit()
                teamEloUiCache = buildTeamEloUiCache(snapshot, lastAudit)
                lastError = null
                snapshot
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                plugin.logger.severe("Qualifier recalc failed: ${t.message}")
                plugin.logger.fine(t.stackTraceToString())
                null
            }
        }, executor)

        current.whenComplete { _, _ ->
            val pending = synchronized(recalcLock) {
                pendingRecalcFuture.also {
                    pendingRecalcFuture = null
                    if (it == null) recalcInFlight = false
                }
            }

            if (pending != null) {
                scheduleRecalc().whenComplete { snapshot, error ->
                    if (error != null) pending.completeExceptionally(error)
                    else pending.complete(snapshot)
                }
            }
        }

        return current
    }

    private fun buildTeamEloUiCache(snapshot: QualifierSnapshot, audit: List<QualifierMatchAudit>): Map<String, TeamEloInfo> {
        val ratingByTeam = HashMap<String, Int>()
        for (r in snapshot.rows) {
            val k = r.teamKey.trim().lowercase()
            if (k.isNotEmpty()) ratingByTeam[k] = r.eloRating
        }

        val deltaByTeam = HashMap<String, Int>()
        for (a in audit) {
            if (a.skipped) continue
            for (t in a.teams) {
                val k = t.teamKey.trim().lowercase()
                if (k.isNotEmpty()) deltaByTeam[k] = round(t.delta).toInt()
            }
        }

        val out = HashMap<String, TeamEloInfo>()
        for ((k, rating) in ratingByTeam) {
            out[k] = TeamEloInfo(rating = rating, delta = deltaByTeam[k])
        }
        return out
    }

    fun exportRatingAndAudit(): Pair<List<File>, String> {
        if (!initialized) return Pair(emptyList(), "Qualifier is not initialized")

        val snap = lastSnapshot ?: return Pair(emptyList(), "No qualifier snapshot. Run /tournament qualifier recalc")
        val audit = lastAudit

        val exportsDir = File(plugin.dataFolder, "minigamesapi/exports")
        exportsDir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = "${snap.eventId}_${snap.stage}_$stamp"

        val ratingCsv = File(exportsDir, "rating_$prefix.csv")
        val ratingYml = File(exportsDir, "rating_$prefix.yml")
        val auditCsv = File(exportsDir, "audit_$prefix.csv")
        val auditYml = File(exportsDir, "audit_$prefix.yml")

        try {
            writeRatingCsv(ratingCsv, snap, config.minMatches)
            writeRatingYaml(ratingYml, snap, config.minMatches)
            writeAuditCsv(auditCsv, audit)
            writeAuditYaml(auditYml, snap, audit)
        } catch (t: Throwable) {
            return Pair(emptyList(), "Export failed: ${t.message}")
        }

        return Pair(listOf(ratingCsv, ratingYml, auditCsv, auditYml), "Exported to ${exportsDir.path}")
    }

    data class ExportResult(
        val ok: Boolean,
        val path: String = "",
        val message: String = "",
    )

    /** Legacy-ish command helpers kept for TournamentCommand. */
    fun statusLines(): List<Component> = buildStatusLines()

    fun getRatingLines(limit: Int, includeIncomplete: Boolean): List<Component> {
        val snap = lastSnapshot
            ?: return listOf(Component.text("No qualifier snapshot. Run /itmocraft qualifier recalc", NamedTextColor.RED))

        val minMatches = config.minMatches
        val rows = if (includeIncomplete) snap.rows else snap.rows.filter { it.completedMatches >= minMatches }
        if (rows.isEmpty()) {
            return listOf(Component.text("No teams to display", NamedTextColor.DARK_GRAY))
        }

        val out = ArrayList<Component>(minOf(limit, rows.size) + 4)
        out.add(Component.text("Qualifier rating (stage=${snap.stage})", NamedTextColor.AQUA))
        out.add(Component.text("# team_key | elo | completed/total | avgPlace | bestScore", NamedTextColor.GRAY))

        val shown = rows.take(limit.coerceAtLeast(1))
        for ((idx, row) in shown.withIndex()) {
            val need = (minMatches - row.completedMatches).coerceAtLeast(0)
            val suffix = if (need > 0) "  NOT QUALIFIED (need $need more)" else ""

            out.add(
                Component.text(
                    "${idx + 1}. ${row.teamKey} | ${row.eloRating} | ${row.completedMatches}/${row.matchesCount} | ${formatDouble(row.avgPlace)} | ${formatNullable(row.bestScore)}$suffix",
                    if (need > 0) NamedTextColor.DARK_GRAY else NamedTextColor.WHITE,
                )
            )
        }

        if (rows.size > shown.size) {
            out.add(Component.text("... +${rows.size - shown.size} more", NamedTextColor.DARK_GRAY))
        }

        return out
    }

    /**
     * Returns lines for a specific team standing in the current qualifier snapshot.
     * Useful for public commands (players tracking their team position).
     */
    fun getTeamStandingLines(teamKey: String, includeIncomplete: Boolean, context: Int = 2): List<Component> {
        val snap = lastSnapshot
            ?: return listOf(Component.text("No qualifier snapshot. Run /itmocraft qualifier recalc", NamedTextColor.RED))

        val key = teamKey.trim()
        if (key.isEmpty()) {
            return listOf(Component.text("team_key is empty", NamedTextColor.RED))
        }

        val minMatches = config.minMatches
        val rows = if (includeIncomplete) snap.rows else snap.rows.filter { it.completedMatches >= minMatches }
        if (rows.isEmpty()) return listOf(Component.text("No teams to display", NamedTextColor.DARK_GRAY))

        val idx = rows.indexOfFirst { it.teamKey.equals(key, ignoreCase = true) }
        if (idx < 0) {
            return listOf(Component.text("Team not found in rating: $key", NamedTextColor.RED))
        }

        val out = ArrayList<Component>(8)
        out.add(Component.text("Qualifier rating (stage=${snap.stage})", NamedTextColor.AQUA))
        out.add(Component.text("# team_key | elo | completed/total | avgPlace | bestScore", NamedTextColor.GRAY))

        val from = (idx - context).coerceAtLeast(0)
        val to = (idx + context).coerceAtMost(rows.size - 1)
        for (i in from..to) {
            val row = rows[i]
            val need = (minMatches - row.completedMatches).coerceAtLeast(0)
            val suffix = if (need > 0) "  NOT QUALIFIED (need $need more)" else ""
            val color = if (i == idx) NamedTextColor.YELLOW else if (need > 0) NamedTextColor.DARK_GRAY else NamedTextColor.WHITE
            out.add(
                Component.text(
                    "${i + 1}. ${row.teamKey} | ${row.eloRating} | ${row.completedMatches}/${row.matchesCount} | ${formatDouble(row.avgPlace)} | ${formatNullable(row.bestScore)}$suffix",
                    color,
                )
            )
        }

        if (from > 0) out.add(2, Component.text("...", NamedTextColor.DARK_GRAY))
        if (to < rows.size - 1) out.add(Component.text("...", NamedTextColor.DARK_GRAY))
        return out
    }

    fun exportRating(): ExportResult {
        if (!initialized) return ExportResult(false, message = "Qualifier is not initialized")
        val snap = lastSnapshot ?: return ExportResult(false, message = "No qualifier snapshot. Run /tournament qualifier recalc")

        val exportsDir = File(plugin.dataFolder, "minigamesapi/exports")
        exportsDir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = "${snap.eventId}_${snap.stage}_$stamp"
        val ratingCsv = File(exportsDir, "rating_$prefix.csv")
        val ratingYml = File(exportsDir, "rating_$prefix.yml")

        return try {
            writeRatingCsv(ratingCsv, snap, config.minMatches)
            writeRatingYaml(ratingYml, snap, config.minMatches)
            ExportResult(true, path = exportsDir.path, message = "OK")
        } catch (t: Throwable) {
            ExportResult(false, path = exportsDir.path, message = t.message ?: t.javaClass.simpleName)
        }
    }

    fun exportAudit(): ExportResult {
        if (!initialized) return ExportResult(false, message = "Qualifier is not initialized")
        val snap = lastSnapshot ?: return ExportResult(false, message = "No qualifier snapshot. Run /tournament qualifier recalc")
        val audit = lastAudit

        val exportsDir = File(plugin.dataFolder, "minigamesapi/exports")
        exportsDir.mkdirs()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = "${snap.eventId}_${snap.stage}_$stamp"
        val auditCsv = File(exportsDir, "audit_$prefix.csv")
        val auditYml = File(exportsDir, "audit_$prefix.yml")

        return try {
            writeAuditCsv(auditCsv, audit)
            writeAuditYaml(auditYml, snap, audit)
            ExportResult(true, path = exportsDir.path, message = "OK")
        } catch (t: Throwable) {
            ExportResult(false, path = exportsDir.path, message = t.message ?: t.javaClass.simpleName)
        }
    }

    fun buildStatusLines(): List<Component> {
        val cfg = config
        val lines = ArrayList<Component>(12)

        lines.add(Component.text("Tournament qualifier", NamedTextColor.AQUA))
        lines.add(Component.text("file: ${file.path}", NamedTextColor.GRAY))
        lines.add(Component.text("event_id: ${cfg.eventId}", NamedTextColor.WHITE))
        lines.add(Component.text("stage: ${cfg.stage}", NamedTextColor.WHITE))

        val ctx = resolveEffectiveContext(cfg)
        if (ctx.overridden) {
            lines.add(Component.text("effective_event_id: ${ctx.eventId}", NamedTextColor.YELLOW))
            lines.add(Component.text("effective_stage: ${ctx.stage}", NamedTextColor.YELLOW))
        }

        lines.add(Component.text("min_matches: ${cfg.minMatches}", NamedTextColor.WHITE))
        lines.add(
            Component.text(
                "locking: mode=${cfg.lockingMode.name.lowercase()}, locked=${cfg.locked}, locked_at=${cfg.lockedAt}, locked_match_id=${cfg.lockedMatchId}",
                NamedTextColor.WHITE,
            )
        )
        lines.add(Component.text("advance.to_stage: ${cfg.advanceToStage}", NamedTextColor.WHITE))

        val snap = lastSnapshot
        if (snap != null) {
            lines.add(
                Component.text(
                    "last_recalc: teams=${snap.rows.size}, matches=${snap.matchesConsidered}, skipped=${snap.matchesSkipped}, generated_at=${snap.generatedAtMs}",
                    NamedTextColor.GREEN,
                )
            )
        } else {
            lines.add(Component.text("last_recalc: <none>", NamedTextColor.DARK_GRAY))
        }

        if (recalcInFlight) {
            lines.add(Component.text("recalc: running", NamedTextColor.YELLOW))
        }

        val err = lastError
        if (!err.isNullOrBlank()) {
            lines.add(Component.text("last_error: $err", NamedTextColor.RED))
        }

        return lines
    }

    private fun resolveCutoffMs(cfg: TournamentQualifierConfig): Long? {
        if (!cfg.locked) return null

        return when (cfg.lockingMode) {
            TournamentQualifierConfig.LockingMode.TIMESTAMP -> {
                if (cfg.lockedAt > 0) cfg.lockedAt else null
            }

            TournamentQualifierConfig.LockingMode.MATCH_ID -> {
                val raw = cfg.lockedMatchId.trim()
                val id = try {
                    if (raw.isBlank()) null else UUID.fromString(raw)
                } catch (_: Throwable) {
                    null
                } ?: return null

                ResultsManager.getMatchEndedAtMs(id).join()
            }
        }
    }

    private class QualifierCalculator(private val cfg: TournamentQualifierConfig) {

        private val startRating = cfg.eloStartRating.toDouble()
        private val scale = cfg.eloScale.toDouble().coerceAtLeast(1.0)
        private val provisionalMatches = cfg.eloProvisionalMatches.coerceAtLeast(0)
        private val kProvisional = cfg.eloKProvisional.toDouble().coerceAtLeast(0.0)
        private val kStable = cfg.eloKStable.toDouble().coerceAtLeast(0.0)

        private val rating = HashMap<String, Double>()
        private val matches = HashMap<String, Int>()
        private val completedMatches = HashMap<String, Int>()
        private val leftMatches = HashMap<String, Int>()
        private val sumPlace = HashMap<String, Double>()
        private val sumScore = HashMap<String, Double>()
        private val scoreCount = HashMap<String, Int>()
        private val bestScore = HashMap<String, Double>()
        private val lastMatchAt = HashMap<String, Long>()
        private val audit = ArrayList<QualifierMatchAudit>(256)

        fun getAudit(): List<QualifierMatchAudit> = audit.toList()

        fun applyMatch(snapshot: MatchTeamsSnapshot): Boolean {
            val entries = snapshot.teams.mapNotNull { team ->
                val teamKey = metricText(team.metrics, TEAM_KEY_METRIC_KEY)?.trim().orEmpty()
                val place = team.placement
                if (teamKey.isBlank() || place == null || place <= 0) null
                else RatingEntry(teamKey, place, team.score, team.completionStatus)
            }

            if (entries.size != snapshot.teams.size || entries.size < 2 || entries.map { it.teamKey }.toSet().size != entries.size) {
                val reason = when {
                    entries.size < 2 -> "<2 rated competitors"
                    entries.map { it.teamKey }.toSet().size != entries.size -> "duplicate team_key"
                    else -> "missing team_key/placement"
                }
                audit += QualifierMatchAudit(snapshot.matchId, snapshot.endedAtMs, true, reason)
                return false
            }

            for (entry in entries) {
                rating.putIfAbsent(entry.teamKey, startRating)
                matches.putIfAbsent(entry.teamKey, 0)
                completedMatches.putIfAbsent(entry.teamKey, 0)
                leftMatches.putIfAbsent(entry.teamKey, 0)
                sumPlace.putIfAbsent(entry.teamKey, 0.0)
            }

            val before = entries.associate { it.teamKey to (rating[it.teamKey] ?: startRating) }
            val divisor = (entries.size - 1).toDouble()
            val deltas = HashMap<String, Double>()

            for (a in entries) {
                val ra = before[a.teamKey] ?: startRating
                val k = if ((matches[a.teamKey] ?: 0) < provisionalMatches) kProvisional else kStable
                var sum = 0.0
                for (b in entries) {
                    if (a === b) continue
                    val rb = before[b.teamKey] ?: startRating
                    val actual = actualScore(a, b)
                    val expected = 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / scale))
                    sum += actual - expected
                }
                deltas[a.teamKey] = k * sum / divisor
            }

            val auditEntries = ArrayList<QualifierMatchTeamAudit>(entries.size)
            for (entry in entries) {
                val ratingBefore = before[entry.teamKey] ?: startRating
                val delta = deltas[entry.teamKey] ?: 0.0
                val ratingAfter = ratingBefore + delta
                rating[entry.teamKey] = ratingAfter
                matches[entry.teamKey] = (matches[entry.teamKey] ?: 0) + 1
                if (entry.completionStatus == CompletionStatus.FINISHED) {
                    completedMatches[entry.teamKey] = (completedMatches[entry.teamKey] ?: 0) + 1
                } else {
                    leftMatches[entry.teamKey] = (leftMatches[entry.teamKey] ?: 0) + 1
                }
                sumPlace[entry.teamKey] = (sumPlace[entry.teamKey] ?: 0.0) + entry.place
                entry.score?.let { score ->
                    sumScore[entry.teamKey] = (sumScore[entry.teamKey] ?: 0.0) + score
                    scoreCount[entry.teamKey] = (scoreCount[entry.teamKey] ?: 0) + 1
                    bestScore[entry.teamKey] = maxOf(bestScore[entry.teamKey] ?: Double.NEGATIVE_INFINITY, score)
                }
                lastMatchAt[entry.teamKey] = maxOf(lastMatchAt[entry.teamKey] ?: 0L, snapshot.endedAtMs)
                auditEntries += QualifierMatchTeamAudit(
                    teamKey = entry.teamKey,
                    place = entry.place,
                    score = entry.score,
                    completionStatus = entry.completionStatus.name,
                    ratingBefore = ratingBefore,
                    delta = delta,
                    ratingAfter = ratingAfter,
                )
            }

            audit += QualifierMatchAudit(snapshot.matchId, snapshot.endedAtMs, teams = auditEntries)
            return true
        }

        private fun actualScore(a: RatingEntry, b: RatingEntry): Double {
            if (a.completionStatus != b.completionStatus) {
                return if (a.completionStatus == CompletionStatus.FINISHED) 1.0 else 0.0
            }
            return when {
                a.place < b.place -> 1.0
                a.place > b.place -> 0.0
                else -> 0.5
            }
        }

        fun buildSnapshot(
            eventId: String,
            stage: String,
            generatedAtMs: Long,
            consideredUntilMs: Long?,
            matchesConsidered: Int,
            matchesSkipped: Int,
        ): QualifierSnapshot {
            val rows = rating.mapNotNull { (teamKey, value) ->
                val count = matches[teamKey] ?: 0
                if (count <= 0) return@mapNotNull null
                val scores = scoreCount[teamKey] ?: 0
                QualifierTeamRow(
                    teamKey = teamKey,
                    matchesCount = count,
                    completedMatches = completedMatches[teamKey] ?: 0,
                    leftMatches = leftMatches[teamKey] ?: 0,
                    eloRating = round(value).toInt(),
                    avgPlace = (sumPlace[teamKey] ?: 0.0) / count,
                    avgScore = if (scores > 0) (sumScore[teamKey] ?: 0.0) / scores else null,
                    bestScore = bestScore[teamKey],
                    lastMatchAtMs = lastMatchAt[teamKey] ?: 0L,
                )
            }.sortedWith(
                compareByDescending<QualifierTeamRow> { it.eloRating }
                    .thenBy { it.avgPlace }
                    .thenByDescending { it.avgScore ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.bestScore ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.teamKey }
            )

            return QualifierSnapshot(eventId, stage, generatedAtMs, consideredUntilMs, matchesConsidered, matchesSkipped, rows)
        }

        private data class RatingEntry(
            val teamKey: String,
            val place: Int,
            val score: Double?,
            val completionStatus: CompletionStatus,
        )

        private fun metricText(metrics: List<Metric>, key: String): String? {
            if (metrics.isEmpty()) return null
            for (m in metrics) {
                if (m.key == key) {
                    val t = m.valueText
                    if (!t.isNullOrBlank()) return t
                }
            }
            return null
        }

    }

    private fun updateYamlAndReload(mutator: (YamlConfiguration) -> Boolean): Boolean {
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            val defaults = loadDefaultYaml()
            val changedMissing = applyMissingKeysFromDefaults(yaml, defaults)
            val changed = mutator(yaml) || changedMissing
            if (changed) yaml.save(file)
            config = TournamentQualifierConfig.fromYaml(yaml)
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            plugin.logger.severe("Failed to update tournament_qualifier.yml: ${t.message}")
            plugin.logger.fine(t.stackTraceToString())
            false
        }
    }

    private fun writeRatingCsv(file: File, snapshot: QualifierSnapshot, minMatches: Int) {
        file.bufferedWriter().use { out ->
            out.appendLine("rank,team_key,elo,matches,completed_matches,left_matches,avg_place,avg_score,best_score,last_match_at_ms,qualified")
            for ((idx, row) in snapshot.rows.withIndex()) {
                val qualified = row.completedMatches >= minMatches
                out.appendLine(
                    listOf(
                        (idx + 1).toString(),
                        row.teamKey,
                        row.eloRating.toString(),
                        row.matchesCount.toString(),
                        row.completedMatches.toString(),
                        row.leftMatches.toString(),
                        formatDouble(row.avgPlace),
                        formatNullable(row.avgScore),
                        formatNullable(row.bestScore),
                        row.lastMatchAtMs.toString(),
                        qualified.toString(),
                    ).joinToString(",")
                )
            }
        }
    }

    private fun writeRatingYaml(file: File, snapshot: QualifierSnapshot, minMatches: Int) {
        val y = YamlConfiguration()
        y.set("event_id", snapshot.eventId)
        y.set("stage", snapshot.stage)
        y.set("generated_at", snapshot.generatedAtMs)
        y.set("considered_until", snapshot.consideredUntilMs)
        y.set("min_matches", minMatches)
        y.set("matches_considered", snapshot.matchesConsidered)
        y.set("matches_skipped", snapshot.matchesSkipped)

        val rows = ArrayList<Map<String, Any>>(snapshot.rows.size)
        for (row in snapshot.rows) {
            rows.add(
                linkedMapOf(
                    "team_key" to row.teamKey,
                    "elo" to row.eloRating,
                    "matches" to row.matchesCount,
                    "avg_place" to row.avgPlace,
                    "completed_matches" to row.completedMatches,
                    "left_matches" to row.leftMatches,
                    "avg_score" to (row.avgScore ?: ""),
                    "best_score" to (row.bestScore ?: ""),
                    "last_match_at" to row.lastMatchAtMs,
                    "qualified" to (row.completedMatches >= minMatches),
                )
            )
        }

        y.set("rows", rows)
        y.save(file)
    }

    private fun writeAuditCsv(file: File, audit: List<QualifierMatchAudit>) {
        file.bufferedWriter().use { out ->
            out.appendLine("match_id,ended_at_ms,skipped,skipped_reason,team_key,place,score,completion_status,rating_before,delta,rating_after")
            for (m in audit) {
                if (m.skipped) {
                    out.appendLine(
                        listOf(
                            m.matchId.toString(),
                            m.endedAtMs.toString(),
                            "true",
                            (m.skippedReason ?: "").replace(',', ' '),
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                            "",
                        ).joinToString(",")
                    )
                    continue
                }

                for (t in m.teams) {
                    out.appendLine(
                        listOf(
                            m.matchId.toString(),
                            m.endedAtMs.toString(),
                            "false",
                            "",
                            t.teamKey,
                            t.place.toString(),
                            formatNullable(t.score),
                            t.completionStatus,
                            formatDouble(t.ratingBefore),
                            formatDouble(t.delta),
                            formatDouble(t.ratingAfter),
                        ).joinToString(",")
                    )
                }
            }
        }
    }

    private fun writeAuditYaml(file: File, snapshot: QualifierSnapshot, audit: List<QualifierMatchAudit>) {
        val y = YamlConfiguration()
        y.set("event_id", snapshot.eventId)
        y.set("stage", snapshot.stage)
        y.set("generated_at", snapshot.generatedAtMs)
        y.set("considered_until", snapshot.consideredUntilMs)

        val matches = ArrayList<Map<String, Any>>(audit.size)
        for (m in audit) {
            val teams = ArrayList<Map<String, Any>>(m.teams.size)
            for (t in m.teams) {
                teams.add(
                    linkedMapOf(
                        "team_key" to t.teamKey,
                        "place" to t.place,
                        "score" to (t.score ?: ""),
                        "completion_status" to t.completionStatus,
                        "rating_before" to t.ratingBefore,
                        "delta" to t.delta,
                        "rating_after" to t.ratingAfter,
                    )
                )
            }

            val row = linkedMapOf<String, Any>(
                "match_id" to m.matchId.toString(),
                "ended_at" to m.endedAtMs,
                "skipped" to m.skipped,
                "skipped_reason" to (m.skippedReason ?: ""),
                "teams" to teams,
            )
            matches.add(row)
        }

        y.set("matches", matches)
        y.save(file)
    }

    private fun formatDouble(v: Double): String {
        return String.format(Locale.US, "%.2f", v)
    }

    private fun formatNullable(v: Double?): String = v?.let(::formatDouble) ?: ""

    private fun loadDefaultYaml(): YamlConfiguration {
        val stream = plugin.javaClass.classLoader.getResourceAsStream("minigamesapi/tournament_qualifier.yml")
        if (stream != null) {
            stream.use {
                return YamlConfiguration.loadConfiguration(it.reader())
            }
        }

        val y = YamlConfiguration()
        y.load(StringReader(DEFAULT_YAML))
        return y
    }

    private fun applyMissingKeysFromDefaults(target: YamlConfiguration, defaults: YamlConfiguration): Boolean {
        var changed = false
        for (path in defaults.getKeys(true)) {
            if (path.isBlank()) continue
            if (defaults.isConfigurationSection(path)) continue
            if (target.contains(path)) continue

            target.set(path, defaults.get(path))
            changed = true
        }
        return changed
    }

    private const val DEFAULT_YAML: String = """
event_id: "spartakiad_2026"
stage: "qualifier_splatoon"

min_matches: 3

elo:
  start_rating: 1000
  k_placement:
    provisional_matches: 10
    k_provisional: 24
    k_stable: 16
  scale: 400

locking:
  mode: "timestamp"   # timestamp | match_id
  locked: false
  locked_at: 0
  locked_match_id: ""

advance:
  to_stage: "semifinal_cw"
  default_thresholds:
    - { min_teams: 16, take: 16 }
    - { min_teams: 8,  take: 8  }
    - { min_teams: 0,  take: 0  } # 0 = take all
"""
}
