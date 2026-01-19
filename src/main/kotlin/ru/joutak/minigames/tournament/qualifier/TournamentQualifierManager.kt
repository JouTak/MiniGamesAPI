package ru.joutak.minigames.tournament.qualifier

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.results.ResultsManager
import ru.joutak.minigames.results.model.MatchTeamsSnapshot
import ru.joutak.minigames.results.model.Metric
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

    @Volatile
    private var recalcInFlight: Boolean = false

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
        if (recalcInFlight) return CompletableFuture.completedFuture(lastSnapshot)

        recalcInFlight = true

        return CompletableFuture.supplyAsync({
            try {
                val cfg = config
                val eventId = cfg.eventId.trim()
                val stage = cfg.stage.trim()
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
                lastError = null
                snapshot
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                plugin.logger.severe("Qualifier recalc failed: ${t.message}")
                plugin.logger.fine(t.stackTraceToString())
                null
            } finally {
                recalcInFlight = false
            }
        }, executor)
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
            ?: return listOf(Component.text("No qualifier snapshot. Run /tournament qualifier recalc", NamedTextColor.RED))

        val minMatches = config.minMatches
        val rows = if (includeIncomplete) snap.rows else snap.rows.filter { it.matchesCount >= minMatches }
        if (rows.isEmpty()) {
            return listOf(Component.text("No teams to display", NamedTextColor.DARK_GRAY))
        }

        val out = ArrayList<Component>(minOf(limit, rows.size) + 4)
        out.add(Component.text("Qualifier rating (stage=${snap.stage})", NamedTextColor.AQUA))
        out.add(Component.text("# team_key | elo | matches | avgPlace | bestPaint", NamedTextColor.GRAY))

        val shown = rows.take(limit.coerceAtLeast(1))
        for ((idx, row) in shown.withIndex()) {
            val need = (minMatches - row.matchesCount).coerceAtLeast(0)
            val suffix = if (need > 0) "  NOT QUALIFIED (need $need more)" else ""

            out.add(
                Component.text(
                    "${idx + 1}. ${row.teamKey} | ${row.eloRating} | ${row.matchesCount} | ${formatDouble(row.avgPlace)} | ${formatDouble(row.bestPaint)}$suffix",
                    if (need > 0) NamedTextColor.DARK_GRAY else NamedTextColor.WHITE,
                )
            )
        }

        if (rows.size > shown.size) {
            out.add(Component.text("... +${rows.size - shown.size} more", NamedTextColor.DARK_GRAY))
        }

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

        private val allowFallbackToScore = cfg.allowFallbackToScore
        private val allowSingleTeamMatches = cfg.allowSingleTeamMatches
        private val paintKey = cfg.paintPercentKey.trim().ifBlank { "paint_percent" }
        private val format = cfg.paintPercentFormat

        // per team aggregates
        private val rating = HashMap<String, Double>()
        private val matches = HashMap<String, Int>()
        private val sumPlace = HashMap<String, Double>()
        private val sumPaint = HashMap<String, Double>()
        private val bestPaint = HashMap<String, Double>()
        private val lastMatchAt = HashMap<String, Long>()

        private val audit = ArrayList<QualifierMatchAudit>(256)

        fun getAudit(): List<QualifierMatchAudit> = audit.toList()

        fun applyMatch(snapshot: MatchTeamsSnapshot): Boolean {
            val teams = ArrayList<MatchTeamInput>(snapshot.teams.size)
            var hadAnyTeam = false

            for (t in snapshot.teams) {
                hadAnyTeam = true
                val teamKey = metricText(t.metrics, TEAM_KEY_METRIC_KEY)?.trim().orEmpty()
                if (teamKey.isBlank()) continue

                val paintRaw: Double? = metricDouble(t.metrics, paintKey)
                val paintResolved: Double? = paintRaw ?: if (allowFallbackToScore) t.score else null
                val paint: Double = paintResolved ?: continue

                val paint0100 = when (format) {
                    TournamentQualifierConfig.PaintPercentFormat.ZERO_TO_1 -> paint * 100.0
                    TournamentQualifierConfig.PaintPercentFormat.ZERO_TO_100 -> paint
                }

                teams.add(MatchTeamInput(teamKey, paint0100.coerceIn(0.0, 100.0)))
            }

            if (teams.size < 2 && !(teams.size == 1 && allowSingleTeamMatches)) {
                val reason = if (!hadAnyTeam || snapshot.teams.size < 2) {
                    "<2 teams"
                } else {
                    "missing team_key/paint_percent"
                }
                audit.add(
                    QualifierMatchAudit(
                        matchId = snapshot.matchId,
                        endedAtMs = snapshot.endedAtMs,
                        skipped = true,
                        skippedReason = reason,
                    )
                )
                return false
            }

            // stable order: paint desc, team_key asc
            teams.sortWith(compareByDescending<MatchTeamInput> { it.paintPercent }.thenBy { it.teamKey })

            for (i in teams.indices) {
                teams[i] = teams[i].copy(place = i + 1)
            }

            // init state
            for (t in teams) {
                rating.putIfAbsent(t.teamKey, startRating)
                matches.putIfAbsent(t.teamKey, 0)
                sumPlace.putIfAbsent(t.teamKey, 0.0)
                sumPaint.putIfAbsent(t.teamKey, 0.0)
                bestPaint.putIfAbsent(t.teamKey, Double.NEGATIVE_INFINITY)
            }

            val ratingBefore = teams.associate { it.teamKey to (rating[it.teamKey] ?: startRating) }

            // compute deltas based on ratings BEFORE this match
            val delta = HashMap<String, Double>()
            for (i in teams.indices) {
                val a = teams[i]
                val ra = ratingBefore[a.teamKey] ?: startRating
                val ma = matches[a.teamKey] ?: 0
                val k = if (ma < provisionalMatches) kProvisional else kStable

                var d = 0.0
                for (j in teams.indices) {
                    if (i == j) continue
                    val b = teams[j]
                    val rb = ratingBefore[b.teamKey] ?: startRating

                    val s = if (a.place < b.place) 1.0 else 0.0
                    val e = 1.0 / (1.0 + Math.pow(10.0, (rb - ra) / scale))
                    d += k * (s - e)
                }

                delta[a.teamKey] = d
            }

            // apply deltas + aggregates
            val auditTeams = ArrayList<QualifierMatchTeamAudit>(teams.size)
            for (t in teams) {
                val before = ratingBefore[t.teamKey] ?: startRating
                val d = delta[t.teamKey] ?: 0.0
                val after = before + d
                rating[t.teamKey] = after

                val nextMatches = (matches[t.teamKey] ?: 0) + 1
                matches[t.teamKey] = nextMatches

                sumPlace[t.teamKey] = (sumPlace[t.teamKey] ?: 0.0) + t.place.toDouble()
                sumPaint[t.teamKey] = (sumPaint[t.teamKey] ?: 0.0) + t.paintPercent

                val prevBest = bestPaint[t.teamKey] ?: Double.NEGATIVE_INFINITY
                if (t.paintPercent > prevBest) bestPaint[t.teamKey] = t.paintPercent

                lastMatchAt[t.teamKey] = maxOf(lastMatchAt[t.teamKey] ?: 0L, snapshot.endedAtMs)

                auditTeams.add(
                    QualifierMatchTeamAudit(
                        teamKey = t.teamKey,
                        place = t.place,
                        paintPercent = t.paintPercent,
                        ratingBefore = before,
                        delta = d,
                        ratingAfter = after,
                    )
                )
            }

            audit.add(
                QualifierMatchAudit(
                    matchId = snapshot.matchId,
                    endedAtMs = snapshot.endedAtMs,
                    skipped = false,
                    teams = auditTeams,
                )
            )
            return true
        }

        fun buildSnapshot(
            eventId: String,
            stage: String,
            generatedAtMs: Long,
            consideredUntilMs: Long?,
            matchesConsidered: Int,
            matchesSkipped: Int,
        ): QualifierSnapshot {
            val rows = ArrayList<QualifierTeamRow>(rating.size)
            for ((teamKey, r) in rating) {
                val m = matches[teamKey] ?: 0
                if (m <= 0) continue

                val avgPlace = (sumPlace[teamKey] ?: 0.0) / m
                val avgPaint = (sumPaint[teamKey] ?: 0.0) / m
                val best = bestPaint[teamKey] ?: 0.0
                val lastAt = lastMatchAt[teamKey] ?: 0L

                rows.add(
                    QualifierTeamRow(
                        teamKey = teamKey,
                        matchesCount = m,
                        eloRating = round(r).toInt(),
                        avgPlace = avgPlace,
                        avgPaint = avgPaint,
                        bestPaint = best,
                        lastMatchAtMs = lastAt,
                    )
                )
            }

            // sorting requirements
            rows.sortWith(
                compareByDescending<QualifierTeamRow> { it.eloRating }
                    .thenBy { it.avgPlace }
                    .thenByDescending { it.avgPaint }
                    .thenByDescending { it.bestPaint }
                    .thenBy { it.teamKey }
            )

            return QualifierSnapshot(
                eventId = eventId,
                stage = stage,
                generatedAtMs = generatedAtMs,
                consideredUntilMs = consideredUntilMs,
                matchesConsidered = matchesConsidered,
                matchesSkipped = matchesSkipped,
                rows = rows,
            )
        }

        private data class MatchTeamInput(
            val teamKey: String,
            val paintPercent: Double,
            val place: Int = -1,
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

        private fun metricDouble(metrics: List<Metric>, key: String): Double? {
            if (metrics.isEmpty()) return null
            for (m in metrics) {
                if (m.key == key) {
                    val r = m.valueReal
                    if (r != null) return r
                    val i = m.valueInt
                    if (i != null) return i.toDouble()
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
            out.appendLine("rank,team_key,elo,matches,avg_place,avg_paint,best_paint,last_match_at_ms,qualified")
            for ((idx, row) in snapshot.rows.withIndex()) {
                val qualified = row.matchesCount >= minMatches
                out.appendLine(
                    listOf(
                        (idx + 1).toString(),
                        row.teamKey,
                        row.eloRating.toString(),
                        row.matchesCount.toString(),
                        formatDouble(row.avgPlace),
                        formatDouble(row.avgPaint),
                        formatDouble(row.bestPaint),
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
                    "avg_paint" to row.avgPaint,
                    "best_paint" to row.bestPaint,
                    "last_match_at" to row.lastMatchAtMs,
                    "qualified" to (row.matchesCount >= minMatches),
                )
            )
        }

        y.set("rows", rows)
        y.save(file)
    }

    private fun writeAuditCsv(file: File, audit: List<QualifierMatchAudit>) {
        file.bufferedWriter().use { out ->
            out.appendLine("match_id,ended_at_ms,skipped,skipped_reason,team_key,place,paint_percent,rating_before,delta,rating_after")
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
                            formatDouble(t.paintPercent),
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
                        "paint_percent" to t.paintPercent,
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

data:
  paint_percent_key: "paint_percent"
  allow_fallback_to_score: false
  paint_percent_format: "0_100" # 0_100 | 0_1

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
