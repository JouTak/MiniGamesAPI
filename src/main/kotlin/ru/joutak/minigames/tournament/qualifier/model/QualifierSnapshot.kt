package ru.joutak.minigames.tournament.qualifier.model

import java.util.UUID

/** In-memory snapshot of qualifier standings. */
data class QualifierSnapshot(
    val eventId: String,
    val stage: String,
    val generatedAtMs: Long,
    val consideredUntilMs: Long?,
    val matchesConsidered: Int,
    val matchesSkipped: Int,
    val rows: List<QualifierTeamRow>,
)

data class QualifierTeamRow(
    val teamKey: String,
    val matchesCount: Int,
    val completedMatches: Int,
    val leftMatches: Int,
    val eloRating: Int,
    val avgPlace: Double,
    val avgScore: Double?,
    val bestScore: Double?,
    val lastMatchAtMs: Long,
)

/**
 * Audit entry for a processed match in qualifier recalculation.
 *
 * Placements come from the game mode. Equal placements are Elo draws.
 */
data class QualifierMatchAudit(
    val matchId: UUID,
    val endedAtMs: Long,
    val skipped: Boolean = false,
    val skippedReason: String? = null,
    val teams: List<QualifierMatchTeamAudit> = emptyList(),
)

data class QualifierMatchTeamAudit(
    val teamKey: String,
    val place: Int,
    val score: Double?,
    val completionStatus: String,
    val ratingBefore: Double,
    val delta: Double,
    val ratingAfter: Double,
)
