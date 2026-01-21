package ru.joutak.minigames.tournament.plan

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Optional hardcoded match composition for a tournament stage (standard mode only).
 *
 * File: plugins/<HostPlugin>/minigamesapi/match_plan.yml
 *
 * Structure (example):
 *
 * event_id: season3
 * stage: semifinals
 * generated_at: 0
 * matches:
 *   semi_1:
 *     active: true
 *     teams: [TEAM_A, TEAM_D, TEAM_E, TEAM_H]
 *   semi_2:
 *     active: true
 *     teams:
 *       - TEAM_B
 *       - TEAM_C
 *       - TEAM_F
 *       - TEAM_G
 *   last_chance:
 *     active: false
 *     teams: []
 */
object TournamentMatchPlanManager {

    private const val FILE_NAME = "match_plan.yml"

    data class PlannedMatch(
        val matchId: String,
        val active: Boolean,
        /** Slot-based mapping (index -> team_key). Null means "empty slot". */
        val teams: List<String?>,
    )

    data class MatchPlanFile(
        val eventId: String,
        val stage: String,
        val generatedAtMs: Long,
        /** If true, only one planned match is assigned to lobbies at a time. */
        val serial: Boolean,
        /** If true, admins may start a planned match with fewer teams using /forcerun. */
        val allowPartialStart: Boolean,
        /** Minimum number of ready teams required to expose a planned match in serial mode. */
        val minTeamsToStart: Int,
        val matches: List<PlannedMatch>,
    )

    @Volatile
    private var cache: MatchPlanFile? = null

    @Volatile
    private var cacheLastModified: Long = -1L

    private fun file(plugin: JavaPlugin): File {
        return File(plugin.dataFolder, "minigamesapi/$FILE_NAME")
    }

    fun load(plugin: JavaPlugin): MatchPlanFile? {
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
            val eventId = yaml.getString("event_id").orEmpty().trim()
            val stage = yaml.getString("stage").orEmpty().trim()
            val generatedAt = yaml.getLong("generated_at")

            val serial = yaml.getBoolean("serial", false)
            val allowPartialStart = yaml.getBoolean("allow_partial_start", false)
            val minTeamsToStart = yaml.getInt("min_teams_to_start", 4).coerceAtLeast(1)

            val matches = parseMatches(yaml.getConfigurationSection("matches"))

            val hasAnyTeam = matches.any { it.teams.any { k -> !k.isNullOrBlank() } }
            if (eventId.isBlank() || stage.isBlank() || !hasAnyTeam) {
                cache = null
                cacheLastModified = mod
                null
            } else {
                val loaded = MatchPlanFile(
                    eventId = eventId,
                    stage = stage,
                    generatedAtMs = generatedAt,
                    serial = serial,
                    allowPartialStart = allowPartialStart,
                    minTeamsToStart = minTeamsToStart,
                    matches = matches,
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

    fun reload(plugin: JavaPlugin): MatchPlanFile? {
        cache = null
        cacheLastModified = -1L
        return load(plugin)
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

    fun getApplicable(plugin: JavaPlugin, eventId: String, stage: String): MatchPlanFile? {
        val plan = load(plugin) ?: return null
        if (plan.eventId != eventId) return null
        if (plan.stage != stage) return null
        return plan
    }

    /** Returns true if match plan applies and team is present in any ACTIVE planned match. Returns null if plan does not apply. */
    fun isTeamAllowed(plugin: JavaPlugin, eventId: String, stage: String, teamKey: String): Boolean? {
        val plan = getApplicable(plugin, eventId, stage) ?: return null
        return plan.matches.any { it.active && it.teams.any { k -> k == teamKey } }
    }

    fun getActiveMatchIdForTeam(plugin: JavaPlugin, eventId: String, stage: String, teamKey: String): String? {
        val plan = getApplicable(plugin, eventId, stage) ?: return null
        return plan.matches.firstOrNull { it.active && it.teams.any { k -> k == teamKey } }?.matchId
    }

    private fun parseMatches(section: ConfigurationSection?): List<PlannedMatch> {
        if (section == null) return emptyList()

        val ids = section.getKeys(false).toList().sorted()
        val out = ArrayList<PlannedMatch>(ids.size)

        for (id in ids) {
            val s = section.getConfigurationSection(id) ?: continue
            val active = s.getBoolean("active", true)
            val rawTeams = try {
                s.getList("teams")
            } catch (_: Throwable) {
                null
            }

            val teams = ArrayList<String?>((rawTeams?.size ?: 0) + 4)
            for (t in rawTeams.orEmpty()) {
                val key = t?.toString()?.trim()
                if (key.isNullOrBlank()) {
                    teams.add(null)
                    continue
                }
                if (key == "-" || key.equals("dummy", ignoreCase = true) || key.equals("null", ignoreCase = true) || key == "~") {
                    teams.add(null)
                    continue
                }
                teams.add(key)
            }

            out.add(
                PlannedMatch(
                    matchId = id.trim(),
                    active = active,
                    teams = teams,
                )
            )
        }

        return out.filter { it.matchId.isNotBlank() }
    }
}
