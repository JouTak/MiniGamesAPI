package ru.joutak.minigames.tournament.seeding

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * Optional stage seeding based on an externally curated list.
 *
 * File: plugins/<HostPlugin>/minigamesapi/seeded_teams.yml
 *
 * If this file matches (event_id, to_stage), tournament gate will allow ONLY these teams.
 * Matchmaking will also prefer this order when assigning teams into waiting instances.
 */
object TournamentSeedingManager {

    private const val FILE_NAME = "seeded_teams.yml"

    data class SeededTeam(
        val teamKey: String,
        val seed: Int,
        val rating: Double? = null,
        val matchesCount: Int? = null,
    )

    data class SeededTeamsFile(
        val eventId: String,
        val fromStage: String,
        val toStage: String,
        val generatedAtMs: Long,
        val teams: List<SeededTeam>,
    )

    @Volatile
    private var cache: SeededTeamsFile? = null

    @Volatile
    private var cacheLastModified: Long = -1L

    private fun file(plugin: JavaPlugin): File {
        return File(plugin.dataFolder, "minigamesapi/$FILE_NAME")
    }

    fun load(plugin: JavaPlugin): SeededTeamsFile? {
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
            val fromStage = yaml.getString("from_stage").orEmpty().trim()
            val toStage = yaml.getString("to_stage").orEmpty().trim()
            val generatedAt = yaml.getLong("generated_at")

            val list = yaml.getMapList("teams") ?: emptyList()
            val teams = list.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val teamKey = (m["team_key"] as? String)?.trim().orEmpty()
                if (teamKey.isBlank()) return@mapNotNull null

                val seed = when (val v = m["seed"]) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull() ?: 0
                    else -> 0
                }
                if (seed <= 0) return@mapNotNull null

                val rating = when (val v = m["rating"]) {
                    is Number -> v.toDouble()
                    is String -> v.toDoubleOrNull()
                    else -> null
                }

                val matches = when (val v = m["matches"]) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }

                SeededTeam(teamKey = teamKey, seed = seed, rating = rating, matchesCount = matches)
            }.sortedBy { it.seed }

            if (eventId.isBlank() || fromStage.isBlank() || toStage.isBlank() || teams.isEmpty()) {
                cache = null
                cacheLastModified = mod
                null
            } else {
                val loaded = SeededTeamsFile(
                    eventId = eventId,
                    fromStage = fromStage,
                    toStage = toStage,
                    generatedAtMs = generatedAt,
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

    fun save(plugin: JavaPlugin, data: SeededTeamsFile): Boolean {
        val f = file(plugin)
        f.parentFile?.mkdirs()

        val yaml = YamlConfiguration()
        yaml.set("event_id", data.eventId)
        yaml.set("from_stage", data.fromStage)
        yaml.set("to_stage", data.toStage)
        yaml.set("generated_at", data.generatedAtMs)

        val list = data.teams
            .sortedBy { it.seed }
            .map {
                linkedMapOf<String, Any>(
                    "team_key" to it.teamKey,
                    "seed" to it.seed,
                ).also { m ->
                    if (it.rating != null) m["rating"] = it.rating
                    if (it.matchesCount != null) m["matches"] = it.matchesCount
                }
            }

        yaml.set("teams", list)

        return try {
            yaml.save(f)
            cache = data
            cacheLastModified = f.lastModified()
            true
        } catch (_: Throwable) {
            false
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

    /** Returns true if there is a seed list for this stage and the team is inside it. Returns null if list does not apply. */
    fun isTeamAllowed(plugin: JavaPlugin, eventId: String, stage: String, teamKey: String): Boolean? {
        val seeded = load(plugin) ?: return null
        if (seeded.eventId != eventId) return null
        if (seeded.toStage != stage) return null
        return seeded.teams.any { it.teamKey == teamKey }
    }

    /** Seed number for team_key if seeded_teams.yml applies to this stage. */
    fun getSeed(plugin: JavaPlugin, eventId: String, stage: String, teamKey: String): Int? {
        val seeded = load(plugin) ?: return null
        if (seeded.eventId != eventId) return null
        if (seeded.toStage != stage) return null
        return seeded.teams.firstOrNull { it.teamKey == teamKey }?.seed
    }

    fun getApplicable(plugin: JavaPlugin, eventId: String, stage: String): SeededTeamsFile? {
        val seeded = load(plugin) ?: return null
        if (seeded.eventId != eventId) return null
        if (seeded.toStage != stage) return null
        return seeded
    }
}
