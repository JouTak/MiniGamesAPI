package ru.joutak.minigames.tournament.qualifier

import org.bukkit.configuration.file.YamlConfiguration

data class TournamentQualifierConfig(
    val eventId: String,
    val stage: String,
    val minMatches: Int,
    val eloStartRating: Int,
    val eloProvisionalMatches: Int,
    val eloKProvisional: Int,
    val eloKStable: Int,
    val eloScale: Int,
    val lockingMode: LockingMode,
    val locked: Boolean,
    val lockedAt: Long,
    val lockedMatchId: String,
    val defaultAdvanceThresholds: List<AdvanceThreshold>,
    /** Next tournament stage that should be restricted by advanced_teams.yml. */
    val advanceToStage: String,
) {

    enum class LockingMode {
        TIMESTAMP,
        MATCH_ID,
        ;

        companion object {
            fun fromConfig(raw: String?): LockingMode {
                return when (raw?.trim()?.lowercase()) {
                    "match_id" -> MATCH_ID
                    "timestamp" -> TIMESTAMP
                    else -> TIMESTAMP
                }
            }
        }
    }

    data class AdvanceThreshold(
        val minTeams: Int,
        val take: Int,
    )

    companion object {
        val DEFAULT: TournamentQualifierConfig = TournamentQualifierConfig(
            eventId = "spartakiad_2026",
            stage = "qualifier_splatoon",
            minMatches = 3,
            eloStartRating = 1000,
            eloProvisionalMatches = 10,
            eloKProvisional = 24,
            eloKStable = 16,
            eloScale = 400,
            lockingMode = LockingMode.TIMESTAMP,
            locked = false,
            lockedAt = 0L,
            lockedMatchId = "",
            defaultAdvanceThresholds = listOf(
                AdvanceThreshold(minTeams = 16, take = 16),
                AdvanceThreshold(minTeams = 8, take = 8),
                AdvanceThreshold(minTeams = 0, take = 0),
            ),
            advanceToStage = "",
        )

        fun fromYaml(yaml: YamlConfiguration): TournamentQualifierConfig {
            val eventId = yaml.getString("event_id")?.trim().orEmpty()
            val stage = yaml.getString("stage")?.trim().orEmpty()

            val minMatches = yaml.getInt("min_matches", DEFAULT.minMatches).coerceAtLeast(0)

            val eloStart = yaml.getInt("elo.start_rating", DEFAULT.eloStartRating)
            val provMatches = yaml.getInt("elo.k_placement.provisional_matches", DEFAULT.eloProvisionalMatches)
                .coerceAtLeast(0)
            val kProv = yaml.getInt("elo.k_placement.k_provisional", DEFAULT.eloKProvisional)
            val kStable = yaml.getInt("elo.k_placement.k_stable", DEFAULT.eloKStable)
            val scale = yaml.getInt("elo.scale", DEFAULT.eloScale).coerceAtLeast(1)

            val lockMode = LockingMode.fromConfig(yaml.getString("locking.mode"))
            val locked = yaml.getBoolean("locking.locked", DEFAULT.locked)
            val lockedAt = yaml.getLong("locking.locked_at", DEFAULT.lockedAt).coerceAtLeast(0L)
            val lockedMatchId = yaml.getString("locking.locked_match_id")?.trim().orEmpty()

            val thresholds = ArrayList<AdvanceThreshold>()
            val rawList = yaml.getMapList("advance.default_thresholds")
            for (raw in rawList) {
                val minTeams = (raw["min_teams"] as? Number)?.toInt() ?: continue
                val take = (raw["take"] as? Number)?.toInt() ?: continue
                thresholds.add(AdvanceThreshold(minTeams = minTeams, take = take))
            }

            val finalThresholds = if (thresholds.isEmpty()) DEFAULT.defaultAdvanceThresholds else thresholds

            val toStage = yaml.getString("advance.to_stage")?.trim().orEmpty()

            return TournamentQualifierConfig(
                eventId = if (eventId.isNotBlank()) eventId else DEFAULT.eventId,
                stage = if (stage.isNotBlank()) stage else DEFAULT.stage,
                minMatches = minMatches,
                eloStartRating = eloStart,
                eloProvisionalMatches = provMatches,
                eloKProvisional = kProv,
                eloKStable = kStable,
                eloScale = scale,
                lockingMode = lockMode,
                locked = locked,
                lockedAt = lockedAt,
                lockedMatchId = lockedMatchId,
                defaultAdvanceThresholds = finalThresholds,
                advanceToStage = toStage,
            )
        }
    }
}
