package ru.joutak.minigames.tournament.advance

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import ru.joutak.minigames.tournament.qualifier.TournamentQualifierManager
import java.io.File

object TournamentAdvanceManager {

    private const val FILE_NAME = "advanced_teams.yml"

    data class AdvanceResult(
        val ok: Boolean,
        val message: String,
        val file: AdvancedTeamsFile? = null,
    )

    @Volatile
    private var cache: AdvancedTeamsFile? = null

    @Volatile
    private var cacheLastModified: Long = -1L

    private fun file(plugin: JavaPlugin): File {
        return File(plugin.dataFolder, "minigamesapi/$FILE_NAME")
    }

    fun load(plugin: JavaPlugin): AdvancedTeamsFile? {
        val f = file(plugin)
        if (!f.exists()) {
            cache = null
            cacheLastModified = -1L
            return null
        }

        val mod = f.lastModified()
        val c = cache
        if (c != null && mod == cacheLastModified) return c

        return try {
            val yaml = YamlConfiguration.loadConfiguration(f)
            val eventId = yaml.getString("event_id").orEmpty()
            val fromStage = yaml.getString("from_stage").orEmpty()
            val toStage = yaml.getString("to_stage").orEmpty()
            val generatedAt = yaml.getLong("generated_at")
            val take = yaml.getInt("take")
            val teams = (yaml.getStringList("teams") ?: emptyList()).filter { it.isNotBlank() }

            if (eventId.isBlank() || fromStage.isBlank() || toStage.isBlank() || teams.isEmpty()) {
                cache = null
                cacheLastModified = mod
                null
            } else {
                val loaded = AdvancedTeamsFile(
                    eventId = eventId,
                    fromStage = fromStage,
                    toStage = toStage,
                    generatedAtMs = generatedAt,
                    take = if (take <= 0) teams.size else take,
                    teams = teams,
                )
                cache = loaded
                cacheLastModified = mod
                loaded
            }
        } catch (_: Throwable) {
            cache = null
            cacheLastModified = mod
            null
        }
    }

    fun clear(plugin: JavaPlugin): Boolean {
        val f = file(plugin)
        cache = null
        cacheLastModified = -1L
        return try {
            if (f.exists()) f.delete() else true
        } catch (_: Throwable) {
            false
        }
    }

    fun save(plugin: JavaPlugin, data: AdvancedTeamsFile): Boolean {
        val f = file(plugin)
        f.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        yaml.set("event_id", data.eventId)
        yaml.set("from_stage", data.fromStage)
        yaml.set("to_stage", data.toStage)
        yaml.set("generated_at", data.generatedAtMs)
        yaml.set("take", data.take)
        yaml.set("teams", data.teams)

        return try {
            yaml.save(f)
            cache = data
            cacheLastModified = f.lastModified()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun advanceAuto(plugin: JavaPlugin): AdvanceResult {
        val cfg = TournamentQualifierManager.getConfig()

        val toStage = cfg.advanceToStage.trim()
        if (toStage.isBlank()) {
            return AdvanceResult(false, "Set advance.to_stage in tournament_qualifier.yml")
        }

        val snapshot = TournamentQualifierManager.getSnapshot()
            ?: return AdvanceResult(false, "No qualifier snapshot. Run /tournament qualifier recalc")

        val eligible = snapshot.rows.filter { it.matchesCount >= cfg.minMatches }
        val t = eligible.size

        val chosen = cfg.defaultAdvanceThresholds
            .sortedByDescending { it.minTeams }
            .firstOrNull { t >= it.minTeams }

        val take = when {
            chosen == null -> 0
            chosen.take <= 0 -> 0
            else -> chosen.take
        }

        return advanceTop(plugin, take)
    }

    fun advanceTop(plugin: JavaPlugin, take: Int): AdvanceResult {
        val cfg = TournamentQualifierManager.getConfig()

        val toStage = cfg.advanceToStage.trim()
        if (toStage.isBlank()) {
            return AdvanceResult(false, "Set advance.to_stage in tournament_qualifier.yml")
        }

        val snapshot = TournamentQualifierManager.getSnapshot()
            ?: return AdvanceResult(false, "No qualifier snapshot. Run /tournament qualifier recalc")

        val eligible = snapshot.rows.filter { it.matchesCount >= cfg.minMatches }
        if (eligible.isEmpty()) {
            return AdvanceResult(false, "No eligible teams (need min_matches=${cfg.minMatches})")
        }

        val n = if (take <= 0) eligible.size else take.coerceAtMost(eligible.size)
        val top = eligible.take(n).map { it.teamKey }

        val data = AdvancedTeamsFile(
            eventId = cfg.eventId,
            fromStage = cfg.stage,
            toStage = toStage,
            generatedAtMs = System.currentTimeMillis(),
            take = n,
            teams = top,
        )

        val ok = save(plugin, data)
        if (!ok) return AdvanceResult(false, "Failed to save advanced_teams.yml")

        return AdvanceResult(true, "Advanced $n teams to stage '$toStage'", data)
    }

    /** Returns true if there is a list for this stage and the team is inside it. Returns null if list does not apply. */
    fun isTeamAllowed(plugin: JavaPlugin, eventId: String, stage: String, teamKey: String): Boolean? {
        val adv = load(plugin) ?: return null
        if (adv.eventId != eventId) return null
        if (adv.toStage != stage) return null
        return adv.teams.contains(teamKey)
    }
}
